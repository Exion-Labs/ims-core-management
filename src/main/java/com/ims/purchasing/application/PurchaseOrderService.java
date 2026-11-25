package com.ims.purchasing.application;

import com.ims.catalog.spi.CatalogReadPort;
import com.ims.common.audit.AuditService;
import com.ims.common.outbox.OutboxWriter;
import com.ims.common.sequence.ReferenceSequenceService;
import com.ims.entitlement.spi.EntitlementFacade;
import com.ims.entitlement.spi.Feature;
import com.ims.entitlement.spi.RequiresFeature;
import com.ims.partner.spi.CurrencyConversionPort;
import com.ims.partner.spi.PartnerReadPort;
import com.ims.purchasing.domain.model.PurchaseOrder;
import com.ims.purchasing.domain.model.PurchaseOrderExtraLine;
import com.ims.purchasing.domain.model.PurchaseOrderLineItem;
import com.ims.purchasing.infrastructure.persistence.PurchaseOrderExtraLineRepository;
import com.ims.purchasing.infrastructure.persistence.PurchaseOrderLineItemRepository;
import com.ims.purchasing.infrastructure.persistence.PurchaseOrderRepository;
import com.ims.shared.exception.BusinessRuleException;
import com.ims.shared.exception.ConflictException;
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
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiresFeature(Feature.PURCHASING)
public class PurchaseOrderService {

    public record PurchaseOrderLineView(
            UUID id,
            UUID purchaseOrderId,
            UUID supplierPartId,
            String supplierSku,
            UUID partId,
            String partIpn,
            String partName,
            String partUnit,
            BigDecimal quantity,
            BigDecimal received,
            BigDecimal remaining,
            BigDecimal packQuantity,
            BigDecimal internalQuantityTotal,
            BigDecimal unitPrice,
            String currency,
            BigDecimal lineTotal,
            BigDecimal lineTotalInBase,
            String tenantBaseCurrency,
            UUID destinationLocationId,
            String destinationLocationPath,
            LocalDate targetDate,
            String reference,
            String notes
    ) {}

    public record PurchaseOrderExtraLineView(
            UUID id,
            UUID purchaseOrderId,
            String reference,
            String description,
            BigDecimal quantity,
            BigDecimal unitPrice,
            String currency,
            BigDecimal lineTotal
    ) {}

    public record PurchaseOrderView(
            UUID id,
            String reference,
            String description,
            UUID supplierId,
            String supplierName,
            String supplierReference,
            String status,
            String currency,
            String tenantBaseCurrency,
            BigDecimal totalAmount,
            BigDecimal totalAmountInBase,
            UUID destinationLocationId,
            String destinationLocationPath,
            LocalDate issueDate,
            LocalDate targetDate,
            LocalDate completeDate,
            String notes,
            List<PurchaseOrderLineView> lines,
            List<PurchaseOrderExtraLineView> extraLines,
            Instant createdAt,
            Instant updatedAt
    ) {}

    public record CreatePurchaseOrderCommand(
            UUID supplierId,
            String description,
            String supplierReference,
            String currency,
            UUID destinationLocationId,
            LocalDate targetDate,
            String notes
    ) {}

    public record AddLineItemCommand(
            UUID supplierPartId,
            BigDecimal quantity,
            BigDecimal unitPrice,
            UUID destinationLocationId,
            LocalDate targetDate,
            String reference,
            String notes
    ) {}

    public record AddExtraLineCommand(
            String reference,
            String description,
            BigDecimal quantity,
            BigDecimal unitPrice
    ) {}

    public record ReceiveLineRequest(
            UUID lineItemId,
            BigDecimal quantityPacks,
            UUID locationId,
            String batchCode,
            List<String> serialNumbers,
            String notes
    ) {}

    public record ReceiveLineResult(
            UUID purchaseOrderId,
            String orderStatus,
            List<StockReadPort.StockItemView> createdStockItems
    ) {}

    private final PurchaseOrderRepository poRepository;
    private final PurchaseOrderLineItemRepository lineRepository;
    private final PurchaseOrderExtraLineRepository extraLineRepository;
    private final PartnerReadPort partnerReadPort;
    private final CurrencyConversionPort currencyConversionPort;
    private final CatalogReadPort catalogReadPort;
    private final StockReadPort stockReadPort;
    private final StockOperationsPort stockOperationsPort;
    private final ReferenceSequenceService sequenceService;
    private final EntitlementFacade entitlementFacade;
    private final AuditService auditService;
    private final OutboxWriter outboxWriter;

    public PurchaseOrderService(
            PurchaseOrderRepository poRepository,
            PurchaseOrderLineItemRepository lineRepository,
            PurchaseOrderExtraLineRepository extraLineRepository,
            PartnerReadPort partnerReadPort,
            CurrencyConversionPort currencyConversionPort,
            CatalogReadPort catalogReadPort,
            StockReadPort stockReadPort,
            StockOperationsPort stockOperationsPort,
            ReferenceSequenceService sequenceService,
            EntitlementFacade entitlementFacade,
            AuditService auditService,
            OutboxWriter outboxWriter
    ) {
        this.poRepository = poRepository;
        this.lineRepository = lineRepository;
        this.extraLineRepository = extraLineRepository;
        this.partnerReadPort = partnerReadPort;
        this.currencyConversionPort = currencyConversionPort;
        this.catalogReadPort = catalogReadPort;
        this.stockReadPort = stockReadPort;
        this.stockOperationsPort = stockOperationsPort;
        this.sequenceService = sequenceService;
        this.entitlementFacade = entitlementFacade;
        this.auditService = auditService;
        this.outboxWriter = outboxWriter;
    }

    @Transactional(readOnly = true)
    public Page<PurchaseOrderView> searchPurchaseOrders(String q, String status, UUID supplierId, Pageable pageable) {
        Specification<PurchaseOrder> spec = (root, query, cb) -> {
            List<Predicate> preds = new ArrayList<>();
            if (q != null && !q.isBlank()) {
                String pat = "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
                preds.add(cb.or(
                        cb.like(cb.lower(root.get("reference")), pat),
                        cb.like(cb.lower(cb.coalesce(root.get("description"), "")), pat),
                        cb.like(cb.lower(cb.coalesce(root.get("supplierReference"), "")), pat)
                ));
            }
            if (status != null && !status.isBlank()) {
                preds.add(cb.equal(root.get("status"), status.trim().toUpperCase(Locale.ROOT)));
            }
            if (supplierId != null) {
                preds.add(cb.equal(root.get("supplierId"), supplierId));
            }
            return cb.and(preds.toArray(new Predicate[0]));
        };
        return poRepository.findAll(spec, pageable).map(this::toPurchaseOrderView);
    }

    @Transactional(readOnly = true)
    public PurchaseOrderView getPurchaseOrder(UUID id) {
        return toPurchaseOrderView(requirePo(id));
    }

    @Transactional
    public PurchaseOrderView createPurchaseOrder(CreatePurchaseOrderCommand cmd) {
        if (cmd.supplierId() == null) {
            throw new ValidationException("supplierId is required");
        }
        PartnerReadPort.CompanySummary supplier = partnerReadPort.requireCompanySummary(cmd.supplierId());
        if (!supplier.supplier()) {
            throw new BusinessRuleException("Company is not marked as a supplier");
        }

        if (cmd.destinationLocationId() != null) {
            StockReadPort.LocationView loc = stockReadPort.requireLocation(cmd.destinationLocationId());
            if (loc.structural()) {
                throw new BusinessRuleException("Destination location cannot be structural");
            }
        }

        String baseCurrency = currencyConversionPort.getTenantBaseCurrency();
        String currency = (cmd.currency() != null && !cmd.currency().isBlank())
                ? cmd.currency().trim().toUpperCase(Locale.ROOT)
                : supplier.defaultCurrency();
        if (!currency.equalsIgnoreCase(baseCurrency)) {
            entitlementFacade.requireFeature(Feature.MULTI_CURRENCY);
        }

        PurchaseOrder po = new PurchaseOrder();
        po.setReference(sequenceService.nextReference("PO"));
        po.setSupplierId(supplier.id());
        po.setDescription(cmd.description());
        po.setSupplierReference(cmd.supplierReference());
        po.setStatus(PurchaseOrder.Status.PENDING.name());
        po.setCurrency(currency);
        po.setDestinationLocationId(cmd.destinationLocationId());
        po.setTargetDate(cmd.targetDate());
        po.setNotes(cmd.notes());

        PurchaseOrder saved = poRepository.saveAndFlush(po);
        return toPurchaseOrderView(saved);
    }

    @Transactional
    public PurchaseOrderView addLineItem(UUID poId, AddLineItemCommand cmd) {
        PurchaseOrder po = requirePoForUpdate(poId);
        if (PurchaseOrder.Status.COMPLETE.name().equals(po.getStatus())
                || PurchaseOrder.Status.CANCELLED.name().equals(po.getStatus())) {
            throw new ConflictException("Cannot add line items to a completed or cancelled PO");
        }
        if (cmd.supplierPartId() == null) {
            throw new ValidationException("supplierPartId is required");
        }
        if (cmd.quantity() == null || cmd.quantity().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ValidationException("Order quantity (packs) must be positive");
        }

        PartnerReadPort.SupplierPartSummary sp = partnerReadPort.getSupplierPart(cmd.supplierPartId());
        if (!sp.supplierId().equals(po.getSupplierId())) {
            throw new BusinessRuleException("SupplierPart belongs to a different supplier");
        }

        if (cmd.destinationLocationId() != null) {
            StockReadPort.LocationView loc = stockReadPort.requireLocation(cmd.destinationLocationId());
            if (loc.structural()) {
                throw new BusinessRuleException("Line destination location cannot be structural");
            }
        }

        BigDecimal unitPrice = cmd.unitPrice();
        if (unitPrice == null) {
            Optional<PartnerReadPort.SupplierPriceBreakSummary> best = partnerReadPort.resolveBestPriceBreak(sp.id(), cmd.quantity());
            unitPrice = best.map(pb -> currencyConversionPort.convert(pb.unitPrice(), pb.currency(), po.getCurrency()))
                    .orElse(BigDecimal.ZERO);
        }

        PurchaseOrderLineItem line = new PurchaseOrderLineItem();
        line.setPurchaseOrderId(po.getId());
        line.setSupplierPartId(sp.id());
        line.setPartId(sp.partId());
        line.setQuantity(cmd.quantity());
        line.setReceived(BigDecimal.ZERO);
        line.setPackQuantity(sp.packQuantity());
        line.setUnitPrice(unitPrice);
        line.setCurrency(po.getCurrency());
        line.setDestinationLocationId(cmd.destinationLocationId() != null ? cmd.destinationLocationId() : po.getDestinationLocationId());
        line.setTargetDate(cmd.targetDate() != null ? cmd.targetDate() : po.getTargetDate());
        line.setReference(cmd.reference());
        line.setNotes(cmd.notes());
        lineRepository.saveAndFlush(line);

        if (PurchaseOrder.Status.PLACED.name().equals(po.getStatus())
                || PurchaseOrder.Status.IN_PROGRESS.name().equals(po.getStatus())) {
            stockOperationsPort.adjustOnOrderDelta(sp.partId(), cmd.quantity().multiply(sp.packQuantity()));
        }

        return toPurchaseOrderView(po);
    }

    @Transactional
    public PurchaseOrderView removeLineItem(UUID poId, UUID lineId) {
        PurchaseOrder po = requirePoForUpdate(poId);
        PurchaseOrderLineItem line = lineRepository.findByIdAndPurchaseOrderIdForUpdate(lineId, poId)
                .orElseThrow(() -> new ResourceNotFoundException("PO line item not found"));
        if (line.getReceived().compareTo(BigDecimal.ZERO) > 0) {
            throw new ConflictException("Cannot delete a PO line item that has already received stock");
        }
        if (PurchaseOrder.Status.PLACED.name().equals(po.getStatus())
                || PurchaseOrder.Status.IN_PROGRESS.name().equals(po.getStatus())) {
            stockOperationsPort.adjustOnOrderDelta(line.getPartId(), line.getQuantity().multiply(line.getPackQuantity()).negate());
        }
        lineRepository.delete(line);
        return toPurchaseOrderView(po);
    }

    @Transactional
    public PurchaseOrderView addExtraLine(UUID poId, AddExtraLineCommand cmd) {
        PurchaseOrder po = requirePoForUpdate(poId);
        if (cmd.description() == null || cmd.description().isBlank()) {
            throw new ValidationException("Extra line description is required");
        }
        PurchaseOrderExtraLine extra = new PurchaseOrderExtraLine();
        extra.setPurchaseOrderId(po.getId());
        extra.setReference(cmd.reference());
        extra.setDescription(cmd.description().trim());
        extra.setQuantity(cmd.quantity() != null ? cmd.quantity() : BigDecimal.ONE);
        extra.setUnitPrice(cmd.unitPrice() != null ? cmd.unitPrice() : BigDecimal.ZERO);
        extra.setCurrency(po.getCurrency());
        extraLineRepository.saveAndFlush(extra);
        return toPurchaseOrderView(po);
    }

    @Transactional
    public PurchaseOrderView placeOrder(UUID poId) {
        PurchaseOrder po = requirePoForUpdate(poId);
        if (!PurchaseOrder.Status.PENDING.name().equals(po.getStatus())
                && !PurchaseOrder.Status.ON_HOLD.name().equals(po.getStatus())) {
            throw new ConflictException("Only PENDING or ON_HOLD purchase orders can be placed");
        }
        List<PurchaseOrderLineItem> lines = lineRepository.findByPurchaseOrderIdOrderByCreatedAtAsc(poId);
        if (lines.isEmpty()) {
            throw new BusinessRuleException("Cannot place a purchase order with zero line items");
        }

        String oldStatus = po.getStatus();
        po.setStatus(PurchaseOrder.Status.PLACED.name());
        if (po.getIssueDate() == null) {
            po.setIssueDate(LocalDate.now());
        }
        PurchaseOrder saved = poRepository.saveAndFlush(po);

        if (PurchaseOrder.Status.PENDING.name().equals(oldStatus)) {
            for (PurchaseOrderLineItem line : lines) {
                BigDecimal onOrderUnits = line.remainingPacks().multiply(line.getPackQuantity());
                stockOperationsPort.adjustOnOrderDelta(line.getPartId(), onOrderUnits);
            }
        }

        auditService.record("PurchaseOrder", saved.getId(), "STATUS_CHANGE",
                Map.of("status", Map.of("from", oldStatus, "to", saved.getStatus())), null);
        outboxWriter.enqueue("purchase_order.placed", "PurchaseOrder", saved.getId(),
                Map.of("reference", saved.getReference(), "supplierId", saved.getSupplierId().toString()));
        return toPurchaseOrderView(saved);
    }

    @Transactional
    public PurchaseOrderView holdOrder(UUID poId) {
        PurchaseOrder po = requirePoForUpdate(poId);
        String oldStatus = po.getStatus();
        if (PurchaseOrder.Status.COMPLETE.name().equals(oldStatus)
                || PurchaseOrder.Status.CANCELLED.name().equals(oldStatus)) {
            throw new ConflictException("Cannot place a completed or cancelled order on hold");
        }
        po.setStatus(PurchaseOrder.Status.ON_HOLD.name());
        PurchaseOrder saved = poRepository.saveAndFlush(po);
        auditService.record("PurchaseOrder", saved.getId(), "STATUS_CHANGE",
                Map.of("status", Map.of("from", oldStatus, "to", saved.getStatus())), null);
        return toPurchaseOrderView(saved);
    }

    @Transactional
    public PurchaseOrderView cancelOrder(UUID poId) {
        PurchaseOrder po = requirePoForUpdate(poId);
        String oldStatus = po.getStatus();
        if (PurchaseOrder.Status.COMPLETE.name().equals(oldStatus)
                || PurchaseOrder.Status.CANCELLED.name().equals(oldStatus)) {
            throw new ConflictException("Order is already " + oldStatus);
        }

        if (PurchaseOrder.Status.PLACED.name().equals(oldStatus)
                || PurchaseOrder.Status.IN_PROGRESS.name().equals(oldStatus)
                || PurchaseOrder.Status.ON_HOLD.name().equals(oldStatus)) {
            for (PurchaseOrderLineItem line : lineRepository.findByPurchaseOrderIdOrderByCreatedAtAsc(poId)) {
                BigDecimal unreceivedUnits = line.remainingPacks().multiply(line.getPackQuantity());
                if (unreceivedUnits.compareTo(BigDecimal.ZERO) > 0) {
                    stockOperationsPort.adjustOnOrderDelta(line.getPartId(), unreceivedUnits.negate());
                }
            }
        }

        po.setStatus(PurchaseOrder.Status.CANCELLED.name());
        PurchaseOrder saved = poRepository.saveAndFlush(po);
        auditService.record("PurchaseOrder", saved.getId(), "STATUS_CHANGE",
                Map.of("status", Map.of("from", oldStatus, "to", saved.getStatus())), null);
        return toPurchaseOrderView(saved);
    }

    @Transactional
    public ReceiveLineResult receiveLineItems(UUID poId, List<ReceiveLineRequest> requests) {
        PurchaseOrder po = requirePoForUpdate(poId);
        if (!PurchaseOrder.Status.PLACED.name().equals(po.getStatus())
                && !PurchaseOrder.Status.IN_PROGRESS.name().equals(po.getStatus())) {
            throw new ConflictException("Purchase order must be PLACED or IN_PROGRESS to receive items (current: " + po.getStatus() + ")");
        }
        if (requests == null || requests.isEmpty()) {
            throw new ValidationException("At least one receive line request is required");
        }

        List<StockReadPort.StockItemView> createdStock = new ArrayList<>();

        for (ReceiveLineRequest req : requests) {
            if (req.lineItemId() == null || req.quantityPacks() == null || req.quantityPacks().compareTo(BigDecimal.ZERO) <= 0) {
                throw new ValidationException("lineItemId and positive quantityPacks are required");
            }
            PurchaseOrderLineItem line = lineRepository.findByIdAndPurchaseOrderIdForUpdate(req.lineItemId(), poId)
                    .orElseThrow(() -> new ResourceNotFoundException("PO line item not found: " + req.lineItemId()));

            BigDecimal remaining = line.remainingPacks();
            if (req.quantityPacks().compareTo(remaining) > 0) {
                throw new BusinessRuleException("Cannot receive " + req.quantityPacks() + " packs; only " + remaining + " packs remaining");
            }

            UUID destLocId = req.locationId() != null ? req.locationId() : line.getDestinationLocationId();
            if (destLocId == null) {
                throw new ValidationException("Destination locationId is required for receiving");
            }

            BigDecimal internalQty = req.quantityPacks().multiply(line.getPackQuantity());
            BigDecimal internalUnitCost = line.getPackQuantity().compareTo(BigDecimal.ZERO) > 0
                    ? line.getUnitPrice().divide(line.getPackQuantity(), 4, RoundingMode.HALF_UP)
                    : line.getUnitPrice();

            if (req.serialNumbers() != null && !req.serialNumbers().isEmpty()) {
                if (BigDecimal.valueOf(req.serialNumbers().size()).compareTo(internalQty) != 0) {
                    throw new BusinessRuleException("Serial number count (" + req.serialNumbers().size() + ") must match received internal units (" + internalQty + ")");
                }
                for (String sn : req.serialNumbers()) {
                    createdStock.add(stockOperationsPort.createStock(new StockOperationsPort.CreateStockSpec(
                            line.getPartId(), destLocId, BigDecimal.ONE, sn, req.batchCode(), "OK",
                            null, internalUnitCost, line.getCurrency(), null, false, null,
                            req.notes() != null ? req.notes() : "Received on " + po.getReference()
                    )));
                }
            } else {
                createdStock.add(stockOperationsPort.createStock(new StockOperationsPort.CreateStockSpec(
                        line.getPartId(), destLocId, internalQty, null, req.batchCode(), "OK",
                        null, internalUnitCost, line.getCurrency(), null, false, null,
                        req.notes() != null ? req.notes() : "Received on " + po.getReference()
                )));
            }

            line.setReceived(line.getReceived().add(req.quantityPacks()));
            lineRepository.saveAndFlush(line);

            stockOperationsPort.adjustOnOrderDelta(line.getPartId(), internalQty.negate());
        }

        List<PurchaseOrderLineItem> allLines = lineRepository.findByPurchaseOrderIdOrderByCreatedAtAsc(poId);
        boolean allComplete = allLines.stream().allMatch(l -> l.remainingPacks().compareTo(BigDecimal.ZERO) <= 0);

        String oldStatus = po.getStatus();
        if (allComplete) {
            po.setStatus(PurchaseOrder.Status.COMPLETE.name());
            po.setCompleteDate(LocalDate.now());
        } else {
            po.setStatus(PurchaseOrder.Status.IN_PROGRESS.name());
        }
        PurchaseOrder savedPo = poRepository.saveAndFlush(po);

        if (!oldStatus.equals(savedPo.getStatus())) {
            auditService.record("PurchaseOrder", savedPo.getId(), "STATUS_CHANGE",
                    Map.of("status", Map.of("from", oldStatus, "to", savedPo.getStatus())), null);
        }
        outboxWriter.enqueue("purchase_order.received", "PurchaseOrder", savedPo.getId(),
                Map.of("reference", savedPo.getReference(), "status", savedPo.getStatus(), "itemsCreated", createdStock.size()));

        return new ReceiveLineResult(savedPo.getId(), savedPo.getStatus(), createdStock);
    }

    @Transactional(readOnly = true)
    public String renderBasicDocumentHtml(UUID poId) {
        PurchaseOrderView po = getPurchaseOrder(poId);
        StringBuilder rowsHtml = new StringBuilder();
        for (PurchaseOrderLineView line : po.lines()) {
            rowsHtml.append("<tr>")
                    .append("<td style='padding:8px;border-bottom:1px solid #e4e4e7;font-family:monospace'>").append(escapeHtml(line.supplierSku())).append("</td>")
                    .append("<td style='padding:8px;border-bottom:1px solid #e4e4e7'>").append(escapeHtml(line.partIpn())).append(" — ").append(escapeHtml(line.partName())).append("</td>")
                    .append("<td style='padding:8px;border-bottom:1px solid #e4e4e7;text-align:right'>").append(line.quantity()).append(" (×").append(line.packQuantity()).append(" ").append(escapeHtml(line.partUnit())).append(")</td>")
                    .append("<td style='padding:8px;border-bottom:1px solid #e4e4e7;text-align:right'>").append(line.unitPrice()).append(" ").append(escapeHtml(line.currency())).append("</td>")
                    .append("<td style='padding:8px;border-bottom:1px solid #e4e4e7;text-align:right;font-weight:600'>").append(line.lineTotal()).append(" ").append(escapeHtml(line.currency())).append("</td>")
                    .append("</tr>");
        }
        return """
                <!DOCTYPE html>
                <html><head><meta charset="utf-8"><title>%s</title></head>
                <body style="font-family:Inter,system-ui,sans-serif;color:#18181B;padding:32px;max-width:860px;margin:auto">
                  <div style="display:flex;justify-content:space-between;border-bottom:2px solid #18181B;padding-bottom:16px">
                    <div>
                      <h1 style="margin:0;font-size:24px">PURCHASE ORDER %s</h1>
                      <p style="margin:4px 0;color:#52525B">Supplier: <strong>%s</strong></p>
                    </div>
                    <div style="text-align:right;font-size:13px">
                      <div>Status: <strong>%s</strong></div>
                      <div>Currency: <strong>%s</strong></div>
                      <div>Issue Date: %s</div>
                    </div>
                  </div>
                  <table style="width:100%%;border-collapse:collapse;margin-top:24px;font-size:13px">
                    <thead>
                      <tr style="background:#f7f7f8;text-align:left">
                        <th style="padding:8px;border-bottom:1px solid #e4e4e7">Vendor SKU</th>
                        <th style="padding:8px;border-bottom:1px solid #e4e4e7">Part</th>
                        <th style="padding:8px;border-bottom:1px solid #e4e4e7;text-align:right">Qty (Packs)</th>
                        <th style="padding:8px;border-bottom:1px solid #e4e4e7;text-align:right">Unit Price</th>
                        <th style="padding:8px;border-bottom:1px solid #e4e4e7;text-align:right">Line Total</th>
                      </tr>
                    </thead>
                    <tbody>%s</tbody>
                  </table>
                  <div style="margin-top:24px;text-align:right;font-size:16px">
                    Total: <strong>%s %s</strong> (≈ %s %s)
                  </div>
                </body></html>
                """.formatted(
                escapeHtml(po.reference()),
                escapeHtml(po.reference()),
                escapeHtml(po.supplierName()),
                escapeHtml(po.status()),
                escapeHtml(po.currency()),
                po.issueDate() != null ? po.issueDate().toString() : "Draft",
                rowsHtml.toString(),
                po.totalAmount(),
                escapeHtml(po.currency()),
                po.totalAmountInBase(),
                escapeHtml(po.tenantBaseCurrency())
        );
    }

    private PurchaseOrder requirePo(UUID id) {
        return poRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase order not found: " + id));
    }

    private PurchaseOrder requirePoForUpdate(UUID id) {
        return poRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase order not found: " + id));
    }

    private PurchaseOrderView toPurchaseOrderView(PurchaseOrder po) {
        PartnerReadPort.CompanySummary supplier = partnerReadPort.requireCompanySummary(po.getSupplierId());
        String baseCurrency = currencyConversionPort.getTenantBaseCurrency();

        List<PurchaseOrderLineItem> lines = lineRepository.findByPurchaseOrderIdOrderByCreatedAtAsc(po.getId());

        Set<UUID> partIds = new HashSet<>();
        Set<UUID> spIds = new HashSet<>();
        Set<UUID> locIds = new HashSet<>();
        if (po.getDestinationLocationId() != null) locIds.add(po.getDestinationLocationId());
        for (PurchaseOrderLineItem l : lines) {
            partIds.add(l.getPartId());
            spIds.add(l.getSupplierPartId());
            if (l.getDestinationLocationId() != null) locIds.add(l.getDestinationLocationId());
        }

        Map<UUID, CatalogReadPort.PartSummary> partsById = catalogReadPort.getPartSummaries(partIds);
        Map<UUID, PartnerReadPort.SupplierPartSummary> spById = partnerReadPort.getSupplierPartSummaries(spIds);
        Map<UUID, StockReadPort.LocationView> locsById = stockReadPort.getLocations(locIds);

        String destLocPath = po.getDestinationLocationId() != null && locsById.containsKey(po.getDestinationLocationId())
                ? locsById.get(po.getDestinationLocationId()).pathString()
                : null;

        List<PurchaseOrderLineView> lineViews = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;

        for (PurchaseOrderLineItem l : lines) {
            CatalogReadPort.PartSummary part = partsById.get(l.getPartId());
            PartnerReadPort.SupplierPartSummary sp = spById.get(l.getSupplierPartId());
            String lineLocPath = l.getDestinationLocationId() != null && locsById.containsKey(l.getDestinationLocationId())
                    ? locsById.get(l.getDestinationLocationId()).pathString()
                    : null;
            BigDecimal lineTotal = l.getQuantity().multiply(l.getUnitPrice()).setScale(4, RoundingMode.HALF_UP);
            BigDecimal lineTotalInBase = currencyConversionPort.convert(lineTotal, l.getCurrency(), baseCurrency);
            total = total.add(lineTotal);

            lineViews.add(new PurchaseOrderLineView(
                    l.getId(),
                    l.getPurchaseOrderId(),
                    l.getSupplierPartId(),
                    sp != null ? sp.sku() : "",
                    l.getPartId(),
                    part != null ? part.ipn() : "",
                    part != null ? part.name() : "",
                    part != null ? part.unit() : "pcs",
                    l.getQuantity(),
                    l.getReceived(),
                    l.remainingPacks(),
                    l.getPackQuantity(),
                    l.getQuantity().multiply(l.getPackQuantity()),
                    l.getUnitPrice(),
                    l.getCurrency(),
                    lineTotal,
                    lineTotalInBase,
                    baseCurrency,
                    l.getDestinationLocationId(),
                    lineLocPath,
                    l.getTargetDate(),
                    l.getReference(),
                    l.getNotes()
            ));
        }

        List<PurchaseOrderExtraLineView> extraViews = new ArrayList<>();
        for (PurchaseOrderExtraLine ex : extraLineRepository.findByPurchaseOrderIdOrderByCreatedAtAsc(po.getId())) {
            BigDecimal exTotal = ex.getQuantity().multiply(ex.getUnitPrice()).setScale(4, RoundingMode.HALF_UP);
            total = total.add(exTotal);
            extraViews.add(new PurchaseOrderExtraLineView(
                    ex.getId(),
                    ex.getPurchaseOrderId(),
                    ex.getReference(),
                    ex.getDescription(),
                    ex.getQuantity(),
                    ex.getUnitPrice(),
                    ex.getCurrency(),
                    exTotal
            ));
        }

        BigDecimal totalInBase = currencyConversionPort.convert(total, po.getCurrency(), baseCurrency);

        return new PurchaseOrderView(
                po.getId(),
                po.getReference(),
                po.getDescription(),
                po.getSupplierId(),
                supplier.name(),
                po.getSupplierReference(),
                po.getStatus(),
                po.getCurrency(),
                baseCurrency,
                total,
                totalInBase,
                po.getDestinationLocationId(),
                destLocPath,
                po.getIssueDate(),
                po.getTargetDate(),
                po.getCompleteDate(),
                po.getNotes(),
                lineViews,
                extraViews,
                po.getCreatedAt(),
                po.getUpdatedAt()
        );
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
