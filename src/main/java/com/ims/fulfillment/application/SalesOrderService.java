package com.ims.fulfillment.application;

import com.ims.catalog.spi.CatalogReadPort;
import com.ims.common.audit.AuditService;
import com.ims.common.outbox.OutboxWriter;
import com.ims.common.sequence.ReferenceSequenceService;
import com.ims.entitlement.spi.Feature;
import com.ims.entitlement.spi.RequiresFeature;
import com.ims.fulfillment.domain.model.SalesOrder;
import com.ims.fulfillment.domain.model.SalesOrderAllocation;
import com.ims.fulfillment.domain.model.SalesOrderLineItem;
import com.ims.fulfillment.domain.model.SalesOrderShipment;
import com.ims.fulfillment.infrastructure.persistence.SalesOrderAllocationRepository;
import com.ims.fulfillment.infrastructure.persistence.SalesOrderLineItemRepository;
import com.ims.fulfillment.infrastructure.persistence.SalesOrderRepository;
import com.ims.fulfillment.infrastructure.persistence.SalesOrderShipmentRepository;
import com.ims.partner.spi.PartnerReadPort;
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
@RequiresFeature(Feature.SALES)
public class SalesOrderService {

    public record SalesOrderAllocationView(
            UUID id,
            UUID lineItemId,
            UUID shipmentId,
            UUID stockItemId,
            String serialNumber,
            String batchCode,
            BigDecimal quantity,
            boolean shipped
    ) {}

    public record SalesOrderLineView(
            UUID id,
            UUID salesOrderId,
            UUID partId,
            String partIpn,
            String partName,
            String partUnit,
            BigDecimal quantity,
            BigDecimal shipped,
            BigDecimal allocated,
            BigDecimal salePrice,
            String salePriceCurrency,
            String reference,
            List<SalesOrderAllocationView> allocations
    ) {}

    public record SalesOrderShipmentView(
            UUID id,
            UUID salesOrderId,
            String reference,
            String trackingNumber,
            String invoiceNumber,
            String carrier,
            LocalDate shipmentDate,
            LocalDate deliveryDate,
            String notes,
            boolean shipped
    ) {}

    public record SalesOrderView(
            UUID id,
            String reference,
            UUID customerId,
            String customerName,
            String customerReference,
            String description,
            String status,
            String currency,
            LocalDate orderDate,
            LocalDate targetDate,
            LocalDate shipmentDate,
            String notes,
            List<SalesOrderLineView> lines,
            List<SalesOrderShipmentView> shipments,
            Instant createdAt,
            Instant updatedAt
    ) {}

    public record CreateSalesOrderCommand(
            UUID customerId,
            String customerReference,
            String description,
            String currency,
            LocalDate targetDate,
            String notes
    ) {}

    public record AddSalesOrderLineCommand(
            UUID partId,
            BigDecimal quantity,
            BigDecimal salePrice,
            String salePriceCurrency,
            String reference,
            String notes
    ) {}

    public record UpdateSalesOrderLineCommand(
            BigDecimal quantity,
            BigDecimal salePrice,
            String notes
    ) {}

    public record CreateShipmentCommand(
            String reference,
            String trackingNumber,
            String carrier,
            String notes
    ) {}

    public record AllocateSalesStockCommand(
            UUID lineItemId,
            UUID shipmentId,
            UUID stockItemId,
            BigDecimal quantity
    ) {}

    public record CompleteShipmentCommand(
            String trackingNumber,
            String invoiceNumber,
            LocalDate shipmentDate
    ) {}

    private final SalesOrderRepository salesOrderRepository;
    private final SalesOrderLineItemRepository lineItemRepository;
    private final SalesOrderShipmentRepository shipmentRepository;
    private final SalesOrderAllocationRepository allocationRepository;
    private final PartnerReadPort partnerReadPort;
    private final CatalogReadPort catalogReadPort;
    private final StockReadPort stockReadPort;
    private final StockOperationsPort stockOperationsPort;
    private final ReferenceSequenceService sequenceService;
    private final AuditService auditService;
    private final OutboxWriter outboxWriter;

    public SalesOrderService(
            SalesOrderRepository salesOrderRepository,
            SalesOrderLineItemRepository lineItemRepository,
            SalesOrderShipmentRepository shipmentRepository,
            SalesOrderAllocationRepository allocationRepository,
            PartnerReadPort partnerReadPort,
            CatalogReadPort catalogReadPort,
            StockReadPort stockReadPort,
            StockOperationsPort stockOperationsPort,
            ReferenceSequenceService sequenceService,
            AuditService auditService,
            OutboxWriter outboxWriter
    ) {
        this.salesOrderRepository = salesOrderRepository;
        this.lineItemRepository = lineItemRepository;
        this.shipmentRepository = shipmentRepository;
        this.allocationRepository = allocationRepository;
        this.partnerReadPort = partnerReadPort;
        this.catalogReadPort = catalogReadPort;
        this.stockReadPort = stockReadPort;
        this.stockOperationsPort = stockOperationsPort;
        this.sequenceService = sequenceService;
        this.auditService = auditService;
        this.outboxWriter = outboxWriter;
    }

    @Transactional(readOnly = true)
    public Page<SalesOrderView> searchSalesOrders(String q, String status, Pageable pageable) {
        Specification<SalesOrder> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (q != null && !q.isBlank()) {
                String pattern = "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("reference")), pattern),
                        cb.like(cb.lower(cb.coalesce(root.get("description"), "")), pattern),
                        cb.like(cb.lower(cb.coalesce(root.get("customerReference"), "")), pattern)
                ));
            }
            if (status != null && !status.isBlank()) {
                predicates.add(cb.equal(root.get("status"), status.trim().toUpperCase(Locale.ROOT)));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        return salesOrderRepository.findAll(spec, pageable).map(so -> toView(so, false));
    }

    @Transactional(readOnly = true)
    public SalesOrderView getSalesOrder(UUID id) {
        return toView(requireSalesOrder(id), true);
    }

    @Transactional
    public SalesOrderView createSalesOrder(CreateSalesOrderCommand cmd) {
        if (cmd.customerId() == null) {
            throw new ValidationException("customerId is required");
        }
        PartnerReadPort.CompanySummary customer = partnerReadPort.requireCompanySummary(cmd.customerId());
        if (!customer.customer()) {
            throw new BusinessRuleException("Company is not marked as a customer");
        }
        SalesOrder so = new SalesOrder();
        so.setReference(sequenceService.nextReference("SO"));
        so.setCustomerId(customer.id());
        so.setCustomerReference(cmd.customerReference());
        so.setDescription(cmd.description());
        so.setStatus("PENDING");
        so.setCurrency(cmd.currency() != null && !cmd.currency().isBlank() ? cmd.currency().toUpperCase(Locale.ROOT) : "USD");
        so.setOrderDate(LocalDate.now());
        so.setTargetDate(cmd.targetDate());
        so.setNotes(cmd.notes());
        SalesOrder saved = salesOrderRepository.saveAndFlush(so);
        auditService.record("SalesOrder", saved.getId(), "CREATE", Map.of("reference", saved.getReference()), null);
        outboxWriter.enqueue("sales_order.created", "SalesOrder", saved.getId(), Map.of("reference", saved.getReference()));
        return toView(saved, true);
    }

    @Transactional
    public SalesOrderView addLineItem(UUID salesOrderId, AddSalesOrderLineCommand cmd) {
        SalesOrder so = requireSalesOrder(salesOrderId);
        if (!"PENDING".equals(so.getStatus()) && !"IN_PROGRESS".equals(so.getStatus())) {
            throw new BusinessRuleException("Cannot add lines to order in status " + so.getStatus());
        }
        if (cmd.quantity() == null || cmd.quantity().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ValidationException("quantity must be > 0");
        }
        CatalogReadPort.PartSummary part = catalogReadPort.requirePartSummary(cmd.partId());
        if (!part.salable()) {
            throw new BusinessRuleException("Part is not marked as salable");
        }
        SalesOrderLineItem line = new SalesOrderLineItem();
        line.setSalesOrderId(so.getId());
        line.setPartId(part.id());
        line.setQuantity(cmd.quantity());
        line.setSalePrice(cmd.salePrice());
        line.setSalePriceCurrency(cmd.salePriceCurrency() != null ? cmd.salePriceCurrency() : so.getCurrency());
        line.setReference(cmd.reference());
        line.setNotes(cmd.notes());
        lineItemRepository.saveAndFlush(line);
        return toView(so, true);
    }

    @Transactional
    public SalesOrderView updateLineItem(UUID salesOrderId, UUID lineId, UpdateSalesOrderLineCommand cmd) {
        SalesOrder so = requireSalesOrder(salesOrderId);
        if (!"PENDING".equals(so.getStatus()) && !"IN_PROGRESS".equals(so.getStatus())) {
            throw new BusinessRuleException("Cannot update lines on order in status " + so.getStatus());
        }
        SalesOrderLineItem line = lineItemRepository.findById(lineId)
                .filter(l -> l.getSalesOrderId().equals(so.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Sales order line not found: " + lineId));
        if (cmd.quantity() == null || cmd.quantity().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ValidationException("quantity must be > 0");
        }
        BigDecimal allocated = allocationRepository.findByLineItemIdOrderByCreatedAtAsc(line.getId()).stream()
                .filter(a -> !a.isShipped())
                .map(SalesOrderAllocation::getQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal minAllowed = line.getShipped().add(allocated);
        if (cmd.quantity().compareTo(minAllowed) < 0) {
            throw new BusinessRuleException(
                    "Cannot reduce quantity below already allocated/shipped amount (" + minAllowed + ")"
            );
        }
        line.setQuantity(cmd.quantity());
        line.setSalePrice(cmd.salePrice());
        if (cmd.notes() != null) {
            line.setNotes(cmd.notes());
        }
        lineItemRepository.saveAndFlush(line);
        return toView(so, true);
    }

    @Transactional
    public SalesOrderView deleteLineItem(UUID salesOrderId, UUID lineId) {
        SalesOrder so = requireSalesOrder(salesOrderId);
        if (!"PENDING".equals(so.getStatus()) && !"IN_PROGRESS".equals(so.getStatus())) {
            throw new BusinessRuleException("Cannot delete lines from order in status " + so.getStatus());
        }
        SalesOrderLineItem line = lineItemRepository.findById(lineId)
                .filter(l -> l.getSalesOrderId().equals(so.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Sales order line not found: " + lineId));
        if (line.getShipped().compareTo(BigDecimal.ZERO) > 0) {
            throw new BusinessRuleException("Cannot delete a line that already has shipped items");
        }
        boolean hasAllocations = allocationRepository.findByLineItemIdOrderByCreatedAtAsc(line.getId()).stream()
                .anyMatch(a -> !a.isShipped());
        if (hasAllocations) {
            throw new BusinessRuleException("Cannot delete a line with allocated stock; cancel the order to release allocations");
        }
        lineItemRepository.delete(line);
        lineItemRepository.flush();
        return toView(so, true);
    }

    @Transactional
    public SalesOrderView issueSalesOrder(UUID salesOrderId) {
        SalesOrder so = requireSalesOrder(salesOrderId);
        if (!"PENDING".equals(so.getStatus())) {
            throw new BusinessRuleException("Only PENDING sales orders can be issued");
        }
        so.setStatus("IN_PROGRESS");
        SalesOrder saved = salesOrderRepository.saveAndFlush(so);
        auditService.record("SalesOrder", saved.getId(), "STATUS_CHANGE", Map.of("from", "PENDING", "to", "IN_PROGRESS"), null);
        return toView(saved, true);
    }

    @Transactional
    public SalesOrderShipmentView createShipment(UUID salesOrderId, CreateShipmentCommand cmd) {
        SalesOrder so = requireSalesOrder(salesOrderId);
        if ("CANCELLED".equals(so.getStatus()) || "SHIPPED".equals(so.getStatus())) {
            throw new BusinessRuleException("Cannot create shipment for order in status " + so.getStatus());
        }
        List<SalesOrderShipment> existing = shipmentRepository.findBySalesOrderIdOrderByCreatedAtAsc(so.getId());
        String ref = cmd != null && cmd.reference() != null && !cmd.reference().isBlank()
                ? cmd.reference().trim()
                : so.getReference() + "-SHP-" + (existing.size() + 1);
        SalesOrderShipment shipment = new SalesOrderShipment();
        shipment.setSalesOrderId(so.getId());
        shipment.setReference(ref);
        if (cmd != null) {
            shipment.setTrackingNumber(cmd.trackingNumber());
            shipment.setCarrier(cmd.carrier());
            shipment.setNotes(cmd.notes());
        }
        SalesOrderShipment saved = shipmentRepository.saveAndFlush(shipment);
        return toShipmentView(saved);
    }

    @Transactional
    public SalesOrderAllocationView allocateStock(UUID salesOrderId, AllocateSalesStockCommand cmd) {
        SalesOrder so = requireSalesOrder(salesOrderId);
        if ("CANCELLED".equals(so.getStatus()) || "SHIPPED".equals(so.getStatus())) {
            throw new BusinessRuleException("Cannot allocate stock to order in status " + so.getStatus());
        }
        SalesOrderLineItem line = lineItemRepository.findById(cmd.lineItemId())
                .filter(l -> l.getSalesOrderId().equals(so.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Sales order line not found"));

        if (cmd.shipmentId() != null) {
            shipmentRepository.findById(cmd.shipmentId())
                    .filter(s -> s.getSalesOrderId().equals(so.getId()))
                    .orElseThrow(() -> new ResourceNotFoundException("Shipment not found"));
        }

        StockReadPort.StockItemSnapshot item = stockOperationsPort.lockStockItemForUpdate(cmd.stockItemId());
        if (!item.partId().equals(line.getPartId())) {
            throw new BusinessRuleException("Stock item part does not match sales order line");
        }
        if (!item.countsTowardInStock() || item.building()) {
            throw new BusinessRuleException("Stock item is not available for sale");
        }
        if (cmd.quantity() == null || cmd.quantity().compareTo(BigDecimal.ZERO) <= 0 || cmd.quantity().compareTo(item.quantity()) > 0) {
            throw new BusinessRuleException("Invalid allocation quantity");
        }

        SalesOrderAllocation alloc = new SalesOrderAllocation();
        alloc.setLineItemId(line.getId());
        alloc.setShipmentId(cmd.shipmentId());
        alloc.setStockItemId(item.id());
        alloc.setQuantity(cmd.quantity());
        alloc.setShipped(false);
        SalesOrderAllocation saved = allocationRepository.saveAndFlush(alloc);

        stockOperationsPort.adjustAllocatedToSalesDelta(line.getPartId(), cmd.quantity());
        return toAllocationView(saved, item);
    }

    @Transactional
    public SalesOrderView completeShipment(UUID salesOrderId, UUID shipmentId, CompleteShipmentCommand cmd) {
        SalesOrder so = requireSalesOrder(salesOrderId);
        SalesOrderShipment shipment = shipmentRepository.findById(shipmentId)
                .filter(s -> s.getSalesOrderId().equals(so.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Shipment not found"));
        if (shipment.getShipmentDate() != null) {
            throw new BusinessRuleException("Shipment already completed");
        }

        List<SalesOrderAllocation> allocations = allocationRepository.findByShipmentId(shipment.getId());
        if (allocations.isEmpty()) {
            throw new BusinessRuleException("Shipment has no allocated stock items");
        }

        for (SalesOrderAllocation alloc : allocations) {
            if (alloc.isShipped()) continue;
            SalesOrderLineItem line = lineItemRepository.findById(alloc.getLineItemId())
                    .orElseThrow(() -> new ResourceNotFoundException("Line item not found"));
            stockOperationsPort.shipAllocatedStockToCustomer(
                    alloc.getStockItemId(),
                    alloc.getQuantity(),
                    so.getCustomerId(),
                    "Shipped via " + shipment.getReference()
            );
            stockOperationsPort.adjustAllocatedToSalesDelta(line.getPartId(), alloc.getQuantity().negate());
            alloc.setShipped(true);
            allocationRepository.saveAndFlush(alloc);
            line.setShipped(line.getShipped().add(alloc.getQuantity()));
            lineItemRepository.saveAndFlush(line);
        }

        if (cmd != null) {
            if (cmd.trackingNumber() != null) shipment.setTrackingNumber(cmd.trackingNumber());
            if (cmd.invoiceNumber() != null) shipment.setInvoiceNumber(cmd.invoiceNumber());
            shipment.setShipmentDate(cmd.shipmentDate() != null ? cmd.shipmentDate() : LocalDate.now());
        } else {
            shipment.setShipmentDate(LocalDate.now());
        }
        shipmentRepository.saveAndFlush(shipment);

        List<SalesOrderLineItem> allLines = lineItemRepository.findBySalesOrderIdOrderByCreatedAtAsc(so.getId());
        boolean allShipped = !allLines.isEmpty() && allLines.stream().allMatch(l -> l.getShipped().compareTo(l.getQuantity()) >= 0);
        if (allShipped) {
            so.setStatus("SHIPPED");
            so.setShipmentDate(shipment.getShipmentDate());
        } else if ("PENDING".equals(so.getStatus())) {
            so.setStatus("IN_PROGRESS");
        }
        SalesOrder saved = salesOrderRepository.saveAndFlush(so);

        auditService.record("SalesOrder", saved.getId(), "STATUS_CHANGE", Map.of("shipment", shipment.getReference(), "status", "SHIPPED"), null);
        outboxWriter.enqueue("sales_order.shipped", "SalesOrder", saved.getId(), Map.of(
                "reference", saved.getReference(),
                "shipmentReference", shipment.getReference()
        ));
        return toView(saved, true);
    }

    @Transactional
    public SalesOrderView cancelSalesOrder(UUID salesOrderId) {
        SalesOrder so = requireSalesOrder(salesOrderId);
        if ("SHIPPED".equals(so.getStatus()) || "CANCELLED".equals(so.getStatus())) {
            throw new BusinessRuleException("Cannot cancel order in status " + so.getStatus());
        }
        List<SalesOrderLineItem> lines = lineItemRepository.findBySalesOrderIdOrderByCreatedAtAsc(so.getId());
        for (SalesOrderLineItem line : lines) {
            List<SalesOrderAllocation> allocs = allocationRepository.findByLineItemIdOrderByCreatedAtAsc(line.getId());
            for (SalesOrderAllocation alloc : allocs) {
                if (!alloc.isShipped()) {
                    stockOperationsPort.adjustAllocatedToSalesDelta(line.getPartId(), alloc.getQuantity().negate());
                    allocationRepository.delete(alloc);
                }
            }
        }
        so.setStatus("CANCELLED");
        SalesOrder saved = salesOrderRepository.saveAndFlush(so);
        auditService.record("SalesOrder", saved.getId(), "STATUS_CHANGE", Map.of("status", "CANCELLED"), null);
        return toView(saved, true);
    }

    private SalesOrder requireSalesOrder(UUID id) {
        return salesOrderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Sales order not found"));
    }

    private SalesOrderView toView(SalesOrder so, boolean includeDetails) {
        PartnerReadPort.CompanySummary customer = partnerReadPort.requireCompanySummary(so.getCustomerId());
        List<SalesOrderLineView> lineViews = List.of();
        List<SalesOrderShipmentView> shipmentViews = List.of();
        if (includeDetails) {
            List<SalesOrderLineItem> lines = lineItemRepository.findBySalesOrderIdOrderByCreatedAtAsc(so.getId());
            Set<UUID> partIds = new HashSet<>();
            for (SalesOrderLineItem line : lines) {
                partIds.add(line.getPartId());
            }
            Map<UUID, CatalogReadPort.PartSummary> partsById = catalogReadPort.getPartSummaries(partIds);

            lineViews = lines.stream().map(line -> {
                CatalogReadPort.PartSummary part = partsById.get(line.getPartId());
                List<SalesOrderAllocation> allocs = allocationRepository.findByLineItemIdOrderByCreatedAtAsc(line.getId());
                Set<UUID> stockIds = new HashSet<>();
                for (SalesOrderAllocation a : allocs) {
                    stockIds.add(a.getStockItemId());
                }
                Map<UUID, StockReadPort.StockItemSnapshot> stockById = stockReadPort.getStockItemSnapshots(stockIds);

                BigDecimal allocated = allocs.stream()
                        .filter(a -> !a.isShipped())
                        .map(SalesOrderAllocation::getQuantity)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                List<SalesOrderAllocationView> allocViews = allocs.stream()
                        .map(a -> toAllocationView(a, stockById.get(a.getStockItemId())))
                        .toList();
                return new SalesOrderLineView(
                        line.getId(),
                        line.getSalesOrderId(),
                        line.getPartId(),
                        part != null ? part.ipn() : "",
                        part != null ? part.name() : "",
                        part != null ? part.unit() : "pcs",
                        line.getQuantity(),
                        line.getShipped(),
                        allocated,
                        line.getSalePrice(),
                        line.getSalePriceCurrency(),
                        line.getReference(),
                        allocViews
                );
            }).toList();
            shipmentViews = shipmentRepository.findBySalesOrderIdOrderByCreatedAtAsc(so.getId()).stream()
                    .map(this::toShipmentView)
                    .toList();
        }
        return new SalesOrderView(
                so.getId(),
                so.getReference(),
                so.getCustomerId(),
                customer.name(),
                so.getCustomerReference(),
                so.getDescription(),
                so.getStatus(),
                so.getCurrency(),
                so.getOrderDate(),
                so.getTargetDate(),
                so.getShipmentDate(),
                so.getNotes(),
                lineViews,
                shipmentViews,
                so.getCreatedAt(),
                so.getUpdatedAt()
        );
    }

    private SalesOrderShipmentView toShipmentView(SalesOrderShipment s) {
        return new SalesOrderShipmentView(
                s.getId(),
                s.getSalesOrderId(),
                s.getReference(),
                s.getTrackingNumber(),
                s.getInvoiceNumber(),
                s.getCarrier(),
                s.getShipmentDate(),
                s.getDeliveryDate(),
                s.getNotes(),
                s.getShipmentDate() != null
        );
    }

    private SalesOrderAllocationView toAllocationView(SalesOrderAllocation a, StockReadPort.StockItemSnapshot si) {
        return new SalesOrderAllocationView(
                a.getId(),
                a.getLineItemId(),
                a.getShipmentId(),
                a.getStockItemId(),
                si != null ? si.serialNumber() : null,
                si != null ? si.batchCode() : null,
                a.getQuantity(),
                a.isShipped()
        );
    }
}
