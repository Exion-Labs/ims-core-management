package com.ims.order.fulfillment;

import com.ims.common.audit.AuditService;
import com.ims.common.entitlement.Feature;
import com.ims.common.entitlement.RequiresFeature;
import com.ims.common.outbox.OutboxWriter;
import com.ims.common.sequence.ReferenceSequenceService;
import com.ims.part.Part;
import com.ims.part.PartService;
import com.ims.stock.StockItem;
import com.ims.stock.StockLocation;
import com.ims.stock.StockLocationService;
import com.ims.stock.StockService;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private final StockLocationService locationService;
    private final StockService stockService;
    private final PartService partService;
    private final ReferenceSequenceService sequenceService;
    private final AuditService auditService;
    private final OutboxWriter outboxWriter;

    public TransferOrderService(
            TransferOrderRepository transferOrderRepository,
            TransferOrderLineItemRepository lineItemRepository,
            StockLocationService locationService,
            StockService stockService,
            PartService partService,
            ReferenceSequenceService sequenceService,
            AuditService auditService,
            OutboxWriter outboxWriter
    ) {
        this.transferOrderRepository = transferOrderRepository;
        this.lineItemRepository = lineItemRepository;
        this.locationService = locationService;
        this.stockService = stockService;
        this.partService = partService;
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
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sourceLocationId and destinationLocationId are required");
        }
        if (cmd.sourceLocationId().equals(cmd.destinationLocationId())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Source and destination locations must differ");
        }
        StockLocation src = locationService.requireLocation(cmd.sourceLocationId());
        StockLocation dest = locationService.requireLocation(cmd.destinationLocationId());
        if (dest.isStructural()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Destination location cannot be structural");
        }
        TransferOrder to = new TransferOrder();
        to.setReference(sequenceService.nextReference("TRF"));
        to.setSourceLocationId(src.getId());
        to.setDestinationLocationId(dest.getId());
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
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Can only add lines to PENDING transfer orders");
        }
        StockItem item = stockService.requireStockItem(cmd.stockItemId());
        BigDecimal qty = cmd.quantity() != null ? cmd.quantity() : item.getQuantity();
        if (qty.compareTo(BigDecimal.ZERO) <= 0 || qty.compareTo(item.getQuantity()) > 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid transfer quantity");
        }
        TransferOrderLineItem line = new TransferOrderLineItem();
        line.setTransferOrderId(to.getId());
        line.setStockItemId(item.getId());
        line.setQuantity(qty);
        line.setNotes(cmd.notes());
        lineItemRepository.saveAndFlush(line);
        return toView(to, true);
    }

    @Transactional
    public TransferOrderView dispatchTransfer(UUID transferOrderId) {
        TransferOrder to = requireTransferOrder(transferOrderId);
        if (!"PENDING".equals(to.getStatus())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Only PENDING transfer orders can be dispatched");
        }
        List<TransferOrderLineItem> lines = lineItemRepository.findByTransferOrderIdOrderByCreatedAtAsc(to.getId());
        if (lines.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Transfer order has no lines");
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
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Cannot receive transfer in status " + to.getStatus());
        }
        List<TransferOrderLineItem> lines = lineItemRepository.findByTransferOrderIdOrderByCreatedAtAsc(to.getId());
        if (lines.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Transfer order has no lines");
        }
        for (TransferOrderLineItem line : lines) {
            if (line.isReceived()) continue;
            stockService.transferStock(line.getStockItemId(), new StockService.TransferStockCommand(
                    to.getDestinationLocationId(),
                    line.getQuantity(),
                    "Transfer " + to.getReference()
            ));
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
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transfer order not found"));
    }

    private TransferOrderView toView(TransferOrder to, boolean includeLines) {
        StockLocation src = locationService.requireLocation(to.getSourceLocationId());
        StockLocation dest = locationService.requireLocation(to.getDestinationLocationId());
        List<TransferOrderLineView> lineViews = List.of();
        if (includeLines) {
            lineViews = lineItemRepository.findByTransferOrderIdOrderByCreatedAtAsc(to.getId()).stream().map(line -> {
                StockItem si = stockService.requireStockItem(line.getStockItemId());
                Part part = partService.requirePart(si.getPartId());
                return new TransferOrderLineView(
                        line.getId(),
                        line.getTransferOrderId(),
                        line.getStockItemId(),
                        part.getId(),
                        part.getIpn(),
                        part.getName(),
                        si.getSerialNumber(),
                        si.getBatchCode(),
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
                src.getPathString(),
                to.getDestinationLocationId(),
                dest.getPathString(),
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
