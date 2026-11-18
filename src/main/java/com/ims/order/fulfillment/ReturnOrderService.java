package com.ims.order.fulfillment;

import com.ims.common.audit.AuditService;
import com.ims.common.entitlement.Feature;
import com.ims.common.entitlement.RequiresFeature;
import com.ims.common.outbox.OutboxWriter;
import com.ims.common.sequence.ReferenceSequenceService;
import com.ims.company.Company;
import com.ims.company.CompanyService;
import com.ims.part.Part;
import com.ims.part.PartService;
import com.ims.stock.StockItem;
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
@RequiresFeature(Feature.RETURNS)
public class ReturnOrderService {

    public record ReturnOrderLineView(
            UUID id,
            UUID returnOrderId,
            UUID stockItemId,
            UUID partId,
            String partIpn,
            String partName,
            String serialNumber,
            String batchCode,
            BigDecimal quantity,
            UUID receivedLocationId,
            String outcome,
            String notes
    ) {}

    public record ReturnOrderView(
            UUID id,
            String reference,
            UUID customerId,
            String customerName,
            UUID salesOrderId,
            String status,
            String description,
            LocalDate targetDate,
            LocalDate completionDate,
            String notes,
            List<ReturnOrderLineView> lines,
            Instant createdAt,
            Instant updatedAt
    ) {}

    public record CreateReturnOrderCommand(
            UUID customerId,
            UUID salesOrderId,
            String description,
            LocalDate targetDate,
            String notes
    ) {}

    public record AddReturnLineCommand(
            UUID stockItemId,
            BigDecimal quantity,
            String notes
    ) {}

    public record ReceiveReturnLineCommand(
            UUID quarantineLocationId,
            String outcome,
            String notes
    ) {}

    private final ReturnOrderRepository returnOrderRepository;
    private final ReturnOrderLineItemRepository lineItemRepository;
    private final CompanyService companyService;
    private final PartService partService;
    private final StockService stockService;
    private final ReferenceSequenceService sequenceService;
    private final AuditService auditService;
    private final OutboxWriter outboxWriter;

    public ReturnOrderService(
            ReturnOrderRepository returnOrderRepository,
            ReturnOrderLineItemRepository lineItemRepository,
            CompanyService companyService,
            PartService partService,
            StockService stockService,
            ReferenceSequenceService sequenceService,
            AuditService auditService,
            OutboxWriter outboxWriter
    ) {
        this.returnOrderRepository = returnOrderRepository;
        this.lineItemRepository = lineItemRepository;
        this.companyService = companyService;
        this.partService = partService;
        this.stockService = stockService;
        this.sequenceService = sequenceService;
        this.auditService = auditService;
        this.outboxWriter = outboxWriter;
    }

    @Transactional(readOnly = true)
    public Page<ReturnOrderView> searchReturnOrders(String q, String status, Pageable pageable) {
        Specification<ReturnOrder> spec = (root, query, cb) -> {
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
        return returnOrderRepository.findAll(spec, pageable).map(ro -> toView(ro, false));
    }

    @Transactional(readOnly = true)
    public ReturnOrderView getReturnOrder(UUID id) {
        return toView(requireReturnOrder(id), true);
    }

    @Transactional
    public ReturnOrderView createReturnOrder(CreateReturnOrderCommand cmd) {
        if (cmd.customerId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "customerId is required");
        }
        Company customer = companyService.requireCompany(cmd.customerId());
        ReturnOrder ro = new ReturnOrder();
        ro.setReference(sequenceService.nextReference("RMA"));
        ro.setCustomerId(customer.getId());
        ro.setSalesOrderId(cmd.salesOrderId());
        ro.setStatus("PENDING");
        ro.setDescription(cmd.description());
        ro.setTargetDate(cmd.targetDate());
        ro.setNotes(cmd.notes());
        ReturnOrder saved = returnOrderRepository.saveAndFlush(ro);
        auditService.record("ReturnOrder", saved.getId(), "CREATE", Map.of("reference", saved.getReference()), null);
        outboxWriter.enqueue("return_order.created", "ReturnOrder", saved.getId(), Map.of("reference", saved.getReference()));
        return toView(saved, true);
    }

    @Transactional
    public ReturnOrderView addLineItem(UUID returnOrderId, AddReturnLineCommand cmd) {
        ReturnOrder ro = requireReturnOrder(returnOrderId);
        if ("COMPLETE".equals(ro.getStatus()) || "CANCELLED".equals(ro.getStatus())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Cannot modify return order in status " + ro.getStatus());
        }
        StockItem item = stockService.requireStockItem(cmd.stockItemId());
        ReturnOrderLineItem line = new ReturnOrderLineItem();
        line.setReturnOrderId(ro.getId());
        line.setStockItemId(item.getId());
        line.setQuantity(cmd.quantity() != null ? cmd.quantity() : item.getQuantity());
        line.setOutcome("PENDING");
        line.setNotes(cmd.notes());
        lineItemRepository.saveAndFlush(line);
        if ("PENDING".equals(ro.getStatus())) {
            ro.setStatus("IN_PROGRESS");
            returnOrderRepository.saveAndFlush(ro);
        }
        return toView(ro, true);
    }

    @Transactional
    public ReturnOrderView receiveLineItem(UUID returnOrderId, UUID lineId, ReceiveReturnLineCommand cmd) {
        ReturnOrder ro = requireReturnOrder(returnOrderId);
        ReturnOrderLineItem line = lineItemRepository.findById(lineId)
                .filter(l -> l.getReturnOrderId().equals(ro.getId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Return order line not found"));

        String outcome = cmd.outcome() != null ? cmd.outcome().trim().toUpperCase(Locale.ROOT) : "QUARANTINED";
        String targetStatus = switch (outcome) {
            case "RESTOCK", "RETURN" -> StockItem.Status.OK.name();
            case "SCRAP", "DAMAGED" -> StockItem.Status.DAMAGED.name();
            case "REJECTED" -> StockItem.Status.REJECTED.name();
            default -> StockItem.Status.QUARANTINED.name();
        };

        stockService.returnStockItemFromCustomer(
                line.getStockItemId(),
                cmd.quarantineLocationId(),
                targetStatus,
                "RMA " + ro.getReference() + " (" + outcome + ")" + (cmd.notes() != null ? ": " + cmd.notes() : "")
        );

        line.setReceivedLocationId(cmd.quarantineLocationId());
        line.setOutcome(outcome);
        if (cmd.notes() != null) line.setNotes(cmd.notes());
        lineItemRepository.saveAndFlush(line);

        List<ReturnOrderLineItem> allLines = lineItemRepository.findByReturnOrderIdOrderByCreatedAtAsc(ro.getId());
        boolean allProcessed = !allLines.isEmpty() && allLines.stream().noneMatch(l -> "PENDING".equals(l.getOutcome()));
        if (allProcessed) {
            ro.setStatus("COMPLETE");
            ro.setCompletionDate(LocalDate.now());
        } else {
            ro.setStatus("IN_PROGRESS");
        }
        ReturnOrder saved = returnOrderRepository.saveAndFlush(ro);
        auditService.record("ReturnOrder", saved.getId(), "STATUS_CHANGE", Map.of("outcome", line.getOutcome(), "status", saved.getStatus()), null);
        return toView(saved, true);
    }

    private ReturnOrder requireReturnOrder(UUID id) {
        return returnOrderRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Return order not found"));
    }

    private ReturnOrderView toView(ReturnOrder ro, boolean includeLines) {
        Company customer = companyService.requireCompany(ro.getCustomerId());
        List<ReturnOrderLineView> lineViews = List.of();
        if (includeLines) {
            lineViews = lineItemRepository.findByReturnOrderIdOrderByCreatedAtAsc(ro.getId()).stream().map(line -> {
                StockItem si = stockService.requireStockItem(line.getStockItemId());
                Part part = partService.requirePart(si.getPartId());
                return new ReturnOrderLineView(
                        line.getId(),
                        line.getReturnOrderId(),
                        line.getStockItemId(),
                        part.getId(),
                        part.getIpn(),
                        part.getName(),
                        si.getSerialNumber(),
                        si.getBatchCode(),
                        line.getQuantity(),
                        line.getReceivedLocationId(),
                        line.getOutcome(),
                        line.getNotes()
                );
            }).toList();
        }
        return new ReturnOrderView(
                ro.getId(),
                ro.getReference(),
                ro.getCustomerId(),
                customer.getName(),
                ro.getSalesOrderId(),
                ro.getStatus(),
                ro.getDescription(),
                ro.getTargetDate(),
                ro.getCompletionDate(),
                ro.getNotes(),
                lineViews,
                ro.getCreatedAt(),
                ro.getUpdatedAt()
        );
    }
}
