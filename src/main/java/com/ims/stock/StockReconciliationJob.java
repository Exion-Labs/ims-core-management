package com.ims.stock;

import com.ims.config.tenant.TenantContext;
import com.ims.part.Part;
import com.ims.part.PartService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Recomputes PartStockSummary counters from the authoritative StockItem rows for a tenant,
 * corrects any drift, and emits a Micrometer counter metric when drift is detected.
 */
@Service
public class StockReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(StockReconciliationJob.class);

    public record DriftEntry(
            UUID partId,
            String ipn,
            BigDecimal summaryInStock,
            BigDecimal actualInStock
    ) {}

    public record ReconciliationReport(
            UUID tenantId,
            int partsChecked,
            int driftsFoundAndCorrected,
            List<DriftEntry> drifts
    ) {}

    private final PartService partService;
    private final StockItemRepository stockItemRepository;
    private final PartStockSummaryRepository summaryRepository;
    private final StockDeltaAccumulator deltaAccumulator;
    private final TransactionTemplate requiresNewTx;
    private final Counter driftCounter;

    public StockReconciliationJob(
            PartService partService,
            StockItemRepository stockItemRepository,
            PartStockSummaryRepository summaryRepository,
            StockDeltaAccumulator deltaAccumulator,
            PlatformTransactionManager transactionManager,
            MeterRegistry meterRegistry
    ) {
        this.partService = partService;
        this.stockItemRepository = stockItemRepository;
        this.summaryRepository = summaryRepository;
        this.deltaAccumulator = deltaAccumulator;
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.driftCounter = Counter.builder("ims.stock.reconciliation.drift.count")
                .description("Number of PartStockSummary counter drifts detected and corrected")
                .register(meterRegistry);
    }

    public ReconciliationReport reconcileTenant(UUID tenantId) {
        return TenantContext.callUncheckedAs(tenantId, () ->
                requiresNewTx.execute(status -> reconcileCurrentTenant())
        );
    }

    @Transactional
    public ReconciliationReport reconcileCurrentTenant() {
        UUID tenantId = TenantContext.require();
        deltaAccumulator.flushCurrentTransactionDeltas();

        var partsPage = partService.searchParts(
                new PartService.PartSearchFilter(
                        null, null, true, null, null, null, null, null, null, null, null, null, null, null, null, null
                ),
                PageRequest.of(0, 10_000)
        );

        List<DriftEntry> drifts = new ArrayList<>();
        int checked = 0;

        for (PartService.PartView part : partsPage.getContent()) {
            checked++;
            BigDecimal actualInStock = stockItemRepository.computeActualInStockForPart(part.id());
            PartStockSummary summary = summaryRepository.findByPartId(part.id()).orElseGet(() -> {
                PartStockSummary created = new PartStockSummary();
                created.setPartId(part.id());
                created.setInStock(actualInStock);
                return summaryRepository.saveAndFlush(created);
            });

            if (summary.getInStock().compareTo(actualInStock) != 0) {
                drifts.add(new DriftEntry(part.id(), part.ipn(), summary.getInStock(), actualInStock));
                summary.setInStock(actualInStock);
                summaryRepository.saveAndFlush(summary);
                driftCounter.increment();
                log.warn("Detected & corrected stock summary drift on tenant={} part={} ({}): summary={} actual={}",
                        tenantId, part.id(), part.ipn(), summary.getInStock(), actualInStock);
            }
        }

        return new ReconciliationReport(tenantId, checked, drifts.size(), drifts);
    }
}
