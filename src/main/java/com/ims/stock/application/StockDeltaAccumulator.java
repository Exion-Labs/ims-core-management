package com.ims.stock.application;

import com.ims.stock.domain.model.*;
import com.ims.stock.infrastructure.persistence.*;
import com.ims.shared.exception.*;

import com.ims.common.events.PartCreatedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Transaction-scoped accumulator that collects PartStockSummary counter deltas per Part
 * and flushes them once per part in deterministic UUID order via an HQL bulk update inside
 * TransactionSynchronization.beforeCommit Ã¢â‚¬â€ preventing lock contention and deadlocks under high concurrency.
 */
@Component
public class StockDeltaAccumulator {

    private static final String TX_RESOURCE_KEY = StockDeltaAccumulator.class.getName() + ".DELTAS";

    public static final class PartDelta {
        private BigDecimal inStock = BigDecimal.ZERO;
        private BigDecimal allocatedToBuilds = BigDecimal.ZERO;
        private BigDecimal allocatedToSales = BigDecimal.ZERO;
        private BigDecimal onOrder = BigDecimal.ZERO;
        private BigDecimal inProduction = BigDecimal.ZERO;

        public void addInStock(BigDecimal delta) {
            if (delta != null) {
                this.inStock = this.inStock.add(delta);
            }
        }

        public void addAllocatedToBuilds(BigDecimal delta) {
            if (delta != null) {
                this.allocatedToBuilds = this.allocatedToBuilds.add(delta);
            }
        }

        public void addAllocatedToSales(BigDecimal delta) {
            if (delta != null) {
                this.allocatedToSales = this.allocatedToSales.add(delta);
            }
        }

        public void addOnOrder(BigDecimal delta) {
            if (delta != null) {
                this.onOrder = this.onOrder.add(delta);
            }
        }

        public void addInProduction(BigDecimal delta) {
            if (delta != null) {
                this.inProduction = this.inProduction.add(delta);
            }
        }

        public boolean isNonZero() {
            return inStock.compareTo(BigDecimal.ZERO) != 0
                    || allocatedToBuilds.compareTo(BigDecimal.ZERO) != 0
                    || allocatedToSales.compareTo(BigDecimal.ZERO) != 0
                    || onOrder.compareTo(BigDecimal.ZERO) != 0
                    || inProduction.compareTo(BigDecimal.ZERO) != 0;
        }
    }

    private final PartStockSummaryRepository summaryRepository;

    public StockDeltaAccumulator(PartStockSummaryRepository summaryRepository) {
        this.summaryRepository = summaryRepository;
    }

    @EventListener
    public void onPartCreated(PartCreatedEvent event) {
        ensureSummaryRowExists(event.partId());
    }

    public PartStockSummary ensureSummaryRowExists(UUID partId) {
        return summaryRepository.findByPartId(partId).orElseGet(() -> {
            PartStockSummary s = new PartStockSummary();
            s.setPartId(partId);
            return summaryRepository.saveAndFlush(s);
        });
    }

    public void addInStockDelta(UUID partId, BigDecimal delta) {
        if (delta == null || delta.compareTo(BigDecimal.ZERO) == 0) {
            return;
        }
        getOrRegisterPartDelta(partId).addInStock(delta);
    }

    public void addAllocatedToBuildsDelta(UUID partId, BigDecimal delta) {
        if (delta == null || delta.compareTo(BigDecimal.ZERO) == 0) {
            return;
        }
        getOrRegisterPartDelta(partId).addAllocatedToBuilds(delta);
    }

    public void addAllocatedToSalesDelta(UUID partId, BigDecimal delta) {
        if (delta == null || delta.compareTo(BigDecimal.ZERO) == 0) {
            return;
        }
        getOrRegisterPartDelta(partId).addAllocatedToSales(delta);
    }

    public void addOnOrderDelta(UUID partId, BigDecimal delta) {
        if (delta == null || delta.compareTo(BigDecimal.ZERO) == 0) {
            return;
        }
        getOrRegisterPartDelta(partId).addOnOrder(delta);
    }

    public void addInProductionDelta(UUID partId, BigDecimal delta) {
        if (delta == null || delta.compareTo(BigDecimal.ZERO) == 0) {
            return;
        }
        getOrRegisterPartDelta(partId).addInProduction(delta);
    }

    /**
     * Immediately flushes any accumulated deltas in the current transaction (sorted by partId)
     * so subsequent reads within the same transaction see the updated counters.
     */
    @SuppressWarnings("unchecked")
    public void flushCurrentTransactionDeltas() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TreeMap<UUID, PartDelta> map = (TreeMap<UUID, PartDelta>) TransactionSynchronizationManager.getResource(TX_RESOURCE_KEY);
        if (map == null || map.isEmpty()) {
            return;
        }
        TreeMap<UUID, PartDelta> snapshot = new TreeMap<>(map);
        map.clear();

        for (Map.Entry<UUID, PartDelta> entry : snapshot.entrySet()) {
            UUID partId = entry.getKey();
            PartDelta d = entry.getValue();
            if (!d.isNonZero()) {
                continue;
            }
            int updated = summaryRepository.applyDeltas(
                    partId,
                    d.inStock,
                    d.allocatedToBuilds,
                    d.allocatedToSales,
                    d.onOrder,
                    d.inProduction
            );
            if (updated == 0) {
                PartStockSummary created = new PartStockSummary();
                created.setPartId(partId);
                created.setInStock(d.inStock);
                created.setAllocatedToBuilds(d.allocatedToBuilds);
                created.setAllocatedToSales(d.allocatedToSales);
                created.setOnOrder(d.onOrder);
                created.setInProduction(d.inProduction);
                summaryRepository.saveAndFlush(created);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private PartDelta getOrRegisterPartDelta(UUID partId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("StockDeltaAccumulator requires an active transaction");
        }
        TreeMap<UUID, PartDelta> map = (TreeMap<UUID, PartDelta>) TransactionSynchronizationManager.getResource(TX_RESOURCE_KEY);
        if (map == null) {
            map = new TreeMap<>();
            TransactionSynchronizationManager.bindResource(TX_RESOURCE_KEY, map);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    if (!readOnly) {
                        flushCurrentTransactionDeltas();
                    }
                }

                @Override
                public void afterCompletion(int status) {
                    if (TransactionSynchronizationManager.hasResource(TX_RESOURCE_KEY)) {
                        TransactionSynchronizationManager.unbindResource(TX_RESOURCE_KEY);
                    }
                }
            });
        }
        return map.computeIfAbsent(partId, k -> new PartDelta());
    }
}
