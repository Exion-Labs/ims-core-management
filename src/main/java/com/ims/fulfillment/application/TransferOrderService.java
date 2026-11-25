package com.ims.fulfillment.application;

import com.ims.catalog.spi.CatalogReadPort;
import com.ims.common.audit.AuditService;
import com.ims.common.outbox.OutboxWriter;
import com.ims.common.sequence.ReferenceSequenceService;
import com.ims.entitlement.spi.Feature;
import com.ims.entitlement.spi.RequiresFeature;
import com.ims.fulfillment.domain.model.TransferOrder;
import com.ims.fulfillment.domain.model.TransferOrderLineItem;
import com.ims.fulfillment.infrastructure.persistence.TransferOrderLineItemRepository;
import com.ims.fulfillment.infrastructure.persistence.TransferOrderRepository;
import com.ims.shared.exception.BusinessRuleException;
import com.ims.shared.exception.ResourceNotFoundException;
import com.ims.shared.exception.ValidationException;
import com.ims.stock.spi.StockOperationsPort;
import com.ims.stock.spi.StockReadPort;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiresFeature(Feature.TRANSFERS)
public class TransferOrderService {

    public record TransferOrderLineView(
            UUID id,
            UUID transferOrderId,
            UUID stockItemId,
            UUID partId,
            String partIpn,
            String partName,
            String serialNumber,
            String batchCode,
            BigDecimal quantity,
            boolean received,
            String notes
    ) {}

    public record TransferOrderView(
            UUID id,
            String reference,
            UUID sourceLocationId,
            String sourceLocationPath,
            UUID destinationLocationId,
            String destinationLocationPath,
            String status,
            String description,
            LocalDate targetDate,
            LocalDate completionDate,
            String notes,
            List<TransferOrderLineView> lines,
            Instant createdAt,
            Instant updatedAt
    ) {}

    public record CreateTransferOrderCommand(
            UUID sourceLocationId,
            UUID destinationLocationId,
            String description,
            LocalDate targetDate,
            String notes
    ) {}

    public record AddTransferLineCommand(
            UUID stockItemId,
            BigDecimal quantity,
            String notes
    ) {}

    private final TransferOrderRepository transferOrderRepository;
    private final TransferOrderLineItemRepository lineItemRepository;
    private final StockReadPort stockReadPort;
    private final StockOperationsPort stockOperationsPort;
    private final CatalogReadPort catalogReadPort;
    private final ReferenceSequenceService sequenceService;
    private final AuditService auditService;
    private final OutboxWriter outboxWriter;

    public TransferOrderService(
            TransferOrderRepository transferOrderRepository,
            TransferOrderLineItemRepository lineItemRepository,
            StockReadPort stockReadPort,
            StockOperationsPort stockOperationsPort,
            CatalogReadPort catalogReadPort,
            ReferenceSequenceService sequenceService,
            AuditService auditService,
            OutboxWriter outboxWriter
    ) {
        this.transferOrderRepository = transferOrderRepository;
        this.lineItemRepository = lineItemRepository;
        this.stockReadPort = stockReadPort;
        this.stockOperationsPort = stockOperationsPort;
        this.catalogReadPort = catalogReadPort;
        this.sequenceService = sequenceService;
        this.auditService = auditService;
        this.outboxWriter = outboxWriter;
    }

    @Transactional(readOnly = true)
    public Page<TransferOrderView> searchTransferOrders(String q, String status, Pageable pageable) {
        Specification<TransferOrder> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (q != null && !q.isBlank()) {
                String pattern = "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("reference")), pattern),
                        cb.like(cb.lower(cb.coalesce(root.get("description"), "")), pattern)
                ));
            }
            if (status != null && !status.isBlank()) {
                predicates.add(cb.equal(root.get("status"), status.trim().toUpperCase(Locale.ROOT)));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        return transferOrderRepository.findAll(spec, pageable).map(to -> toView(to, false));
    }

    @Transactional(readOnly = true)
    public TransferOrderView getTransferOrder(UUID id) {
        return toView(requireTransferOrder(id), true);
    }

    @Transactional
    public TransferOrderView createTransferOrder(CreateTransferOrderCommand cmd) {
        if (cmd.sourceLocationId() == null || cmd.destinationLocationId() == null) {
            throw new ValidationException("sourceLocationId and destinationLocationId are required");
        }
        if (cmd.sourceLocationId().equals(cmd.destinationLocationId())) {
            throw new BusinessRuleException("Source and destination locations must differ");
        }
        StockReadPort.LocationView src = stockReadPort.requireLocation(cmd.sourceLocationId());
        StockReadPort.LocationView dest = stockReadPort.requireLocation(cmd.destinationLocationId());
        if (dest.structural()) {
            throw new BusinessRuleException("Destination location cannot be structural");
        }
        TransferOrder to = new TransferOrder();
        to.setReference(sequenceService.nextReference("TRF"));
        to.setSourceLocationId(src.id());
        to.setDestinationLocationId(dest.id());
        to.setStatus("PENDING");
        to.setDescription(cmd.description());
        to.setTargetDate(cmd.targetDate());
        to.setNotes(cmd.notes());
        TransferOrder saved = transferOrderRepository.saveAndFlush(to);
        auditService.record("TransferOrder", saved.getId(), "CREATE", Map.of("reference", saved.getReference()), null);
        return toView(saved, true);
    }

    @Transactional
    public TransferOrderView addLineItem(UUID transferOrderId, AddTransferLineCommand cmd) {
        TransferOrder to = requireTransferOrder(transferOrderId);
        if (!"PENDING".equals(to.getStatus())) {
            throw new BusinessRuleException("Can only add lines to PENDING transfer orders");
        }
        StockReadPort.StockItemSnapshot item = stockReadPort.requireStockItemSnapshot(cmd.stockItemId());
        BigDecimal qty = cmd.quantity() != null ? cmd.quantity() : item.quantity();
        if (qty.compareTo(BigDecimal.ZERO) <= 0 || qty.compareTo(item.quantity()) > 0) {
            throw new BusinessRuleException("Invalid transfer quantity");
        }
        TransferOrderLineItem line = new TransferOrderLineItem();
        line.setTransferOrderId(to.getId());
        line.setStockItemId(item.id());
        line.setQuantity(qty);
        line.setNotes(cmd.notes());
        lineItemRepository.saveAndFlush(line);
        return toView(to, true);
    }

    @Transactional
    public TransferOrderView dispatchTransfer(UUID transferOrderId) {
        TransferOrder to = requireTransferOrder(transferOrderId);
        if (!"PENDING".equals(to.getStatus())) {
            throw new BusinessRuleException("Only PENDING transfer orders can be dispatched");
        }
        List<TransferOrderLineItem> lines = lineItemRepository.findByTransferOrderIdOrderByCreatedAtAsc(to.getId());
        if (lines.isEmpty()) {
            throw new BusinessRuleException("Transfer order has no lines");
        }
        to.setStatus("IN_TRANSIT");
        TransferOrder saved = transferOrderRepository.saveAndFlush(to);
        auditService.record("TransferOrder", saved.getId(), "STATUS_CHANGE", Map.of("from", "PENDING", "to", "IN_TRANSIT"), null);
        return toView(saved, true);
    }

    @Transactional
    public TransferOrderView receiveTransfer(UUID transferOrderId) {
        TransferOrder to = requireTransferOrder(transferOrderId);
        if (!"IN_TRANSIT".equals(to.getStatus()) && !"PENDING".equals(to.getStatus())) {
            throw new BusinessRuleException("Cannot receive transfer in status " + to.getStatus());
        }
        List<TransferOrderLineItem> lines = lineItemRepository.findByTransferOrderIdOrderByCreatedAtAsc(to.getId());
        if (lines.isEmpty()) {
            throw new BusinessRuleException("Transfer order has no lines");
        }
        for (TransferOrderLineItem line : lines) {
            if (line.isReceived()) continue;
            stockOperationsPort.transferStock(
                    line.getStockItemId(),
                    to.getDestinationLocationId(),
                    line.getQuantity(),
                    "Transfer " + to.getReference()
            );
            line.setReceived(true);
            lineItemRepository.saveAndFlush(line);
        }
        to.setStatus("COMPLETE");
        to.setCompletionDate(LocalDate.now());
        TransferOrder saved = transferOrderRepository.saveAndFlush(to);
        auditService.record("TransferOrder", saved.getId(), "STATUS_CHANGE", Map.of("from", "IN_TRANSIT", "to", "COMPLETE"), null);
        outboxWriter.enqueue("transfer_order.completed", "TransferOrder", saved.getId(), Map.of("reference", saved.getReference()));
        return toView(saved, true);
    }

    private TransferOrder requireTransferOrder(UUID id) {
        return transferOrderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Transfer order not found"));
    }

    private TransferOrderView toView(TransferOrder to, boolean includeLines) {
        Map<UUID, StockReadPort.LocationView> locs = stockReadPort.getLocations(List.of(to.getSourceLocationId(), to.getDestinationLocationId()));
        StockReadPort.LocationView src = locs.getOrDefault(to.getSourceLocationId(), stockReadPort.requireLocation(to.getSourceLocationId()));
        StockReadPort.LocationView dest = locs.getOrDefault(to.getDestinationLocationId(), stockReadPort.requireLocation(to.getDestinationLocationId()));
        List<TransferOrderLineView> lineViews = List.of();
        if (includeLines) {
            List<TransferOrderLineItem> lines = lineItemRepository.findByTransferOrderIdOrderByCreatedAtAsc(to.getId());
            Set<UUID> stockIds = new HashSet<>();
            for (TransferOrderLineItem l : lines) {
                stockIds.add(l.getStockItemId());
            }
            Map<UUID, StockReadPort.StockItemSnapshot> stockById = stockReadPort.getStockItemSnapshots(stockIds);
            Set<UUID> partIds = new HashSet<>();
            for (StockReadPort.StockItemSnapshot si : stockById.values()) {
                partIds.add(si.partId());
            }
            Map<UUID, CatalogReadPort.PartSummary> partsById = catalogReadPort.getPartSummaries(partIds);

            lineViews = lines.stream().map(line -> {
                StockReadPort.StockItemSnapshot si = stockById.get(line.getStockItemId());
                CatalogReadPort.PartSummary part = si != null ? partsById.get(si.partId()) : null;
                return new TransferOrderLineView(
                        line.getId(),
                        line.getTransferOrderId(),
                        line.getStockItemId(),
                        part != null ? part.id() : null,
                        part != null ? part.ipn() : "",
                        part != null ? part.name() : "",
                        si != null ? si.serialNumber() : null,
                        si != null ? si.batchCode() : null,
                        line.getQuantity(),
                        line.isReceived(),
                        line.getNotes()
                );
            }).toList();
        }
        return new TransferOrderView(
                to.getId(),
                to.getReference(),
                to.getSourceLocationId(),
                src.pathString(),
                to.getDestinationLocationId(),
                dest.pathString(),
                to.getStatus(),
                to.getDescription(),
                to.getTargetDate(),
                to.getCompletionDate(),
                to.getNotes(),
                lineViews,
                to.getCreatedAt(),
                to.getUpdatedAt()
        );
    }
}
