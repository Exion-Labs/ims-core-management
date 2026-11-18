package com.ims.order.purchase;

import com.ims.common.audit.AuditService;
import com.ims.common.entitlement.EntitlementService;
import com.ims.common.entitlement.Feature;
import com.ims.common.entitlement.RequiresFeature;
import com.ims.common.outbox.OutboxWriter;
import com.ims.common.sequence.ReferenceSequenceService;
import com.ims.company.CompanyService;
import com.ims.company.CurrencyService;
import com.ims.part.Part;
import com.ims.part.PartService;
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
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
            List<StockService.StockItemView> createdStockItems
    ) {}

    private final PurchaseOrderRepository poRepository;
    private final PurchaseOrderLineItemRepository lineRepository;
    private final PurchaseOrderExtraLineRepository extraLineRepository;
    private final CompanyService companyService;
    private final CurrencyService currencyService;
    private final PartService partService;
    private final StockService stockService;
    private final StockLocationService locationService;
    private final ReferenceSequenceService sequenceService;
    private final EntitlementService entitlementService;
    private final AuditService auditService;
    private final OutboxWriter outboxWriter;

    public PurchaseOrderService(
            PurchaseOrderRepository poRepository,
            PurchaseOrderLineItemRepository lineRepository,
            PurchaseOrderExtraLineRepository extraLineRepository,
            CompanyService companyService,
            CurrencyService currencyService,
            PartService partService,
            StockService stockService,
            StockLocationService locationService,
            ReferenceSequenceService sequenceService,
            EntitlementService entitlementService,
            AuditService auditService,
            OutboxWriter outboxWriter
    ) {
        this.poRepository = poRepository;
        this.lineRepository = lineRepository;
        this.extraLineRepository = extraLineRepository;
        this.companyService = companyService;
        this.currencyService = currencyService;
        this.partService = partService;
        this.stockService = stockService;
        this.locationService = locationService;
        this.sequenceService = sequenceService;
        this.entitlementService = entitlementService;
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
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "supplierId is required");
        }
        CompanyService.CompanyView supplier = companyService.getCompany(cmd.supplierId());
        if (!supplier.supplier()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Company is not marked as a supplier");
        }

        if (cmd.destinationLocationId() != null) {
            StockLocation loc = locationService.requireLocation(cmd.destinationLocationId());
            if (loc.isStructural()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Destination location cannot be structural");
            }
        }

        String baseCurrency = currencyService.getTenantBaseCurrency();
        String currency = (cmd.currency() != null && !cmd.currency().isBlank())
                ? cmd.currency().trim().toUpperCase(Locale.ROOT)
                : supplier.defaultCurrency();
        if (!currency.equalsIgnoreCase(baseCurrency)) {
            entitlementService.requireFeature(Feature.MULTI_CURRENCY);
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
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot add line items to a completed or cancelled PO");
        }
        if (cmd.supplierPartId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "supplierPartId is required");
        }
        if (cmd.quantity() == null || cmd.quantity().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Order quantity (packs) must be positive");
        }

        CompanyService.SupplierPartView sp = companyService.getSupplierPart(cmd.supplierPartId());
        if (!sp.supplierId().equals(po.getSupplierId())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "SupplierPart belongs to a different supplier");
        }

        if (cmd.destinationLocationId() != null) {
            StockLocation loc = locationService.requireLocation(cmd.destinationLocationId());
            if (loc.isStructural()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Line destination location cannot be structural");
            }
        }

        BigDecimal unitPrice = cmd.unitPrice();
        if (unitPrice == null) {
            // Auto-price from SupplierPriceBreak converted into PO currency
            Optional<CompanyService.SupplierPriceBreakView> best = companyService.resolveBestPriceBreak(sp.id(), cmd.quantity());
            unitPrice = best.map(pb -> currencyService.convert(pb.unitPrice(), pb.currency(), po.getCurrency()))
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

        // If PO is already placed/in-progress, immediately adjust onOrder
        if (PurchaseOrder.Status.PLACED.name().equals(po.getStatus())
                || PurchaseOrder.Status.IN_PROGRESS.name().equals(po.getStatus())) {
            stockService.adjustOnOrderDelta(sp.partId(), cmd.quantity().multiply(sp.packQuantity()));
        }

        return toPurchaseOrderView(po);
    }

    @Transactional
    public PurchaseOrderView removeLineItem(UUID poId, UUID lineId) {
        PurchaseOrder po = requirePoForUpdate(poId);
        PurchaseOrderLineItem line = lineRepository.findByIdAndPurchaseOrderIdForUpdate(lineId, poId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PO line item not found"));
        if (line.getReceived().compareTo(BigDecimal.ZERO) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot delete a PO line item that has already received stock");
        }
        if (PurchaseOrder.Status.PLACED.name().equals(po.getStatus())
                || PurchaseOrder.Status.IN_PROGRESS.name().equals(po.getStatus())) {
            stockService.adjustOnOrderDelta(line.getPartId(), line.getQuantity().multiply(line.getPackQuantity()).negate());
        }
        lineRepository.delete(line);
        return toPurchaseOrderView(po);
    }

    @Transactional
    public PurchaseOrderView addExtraLine(UUID poId, AddExtraLineCommand cmd) {
        PurchaseOrder po = requirePoForUpdate(poId);
        if (cmd.description() == null || cmd.description().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Extra line description is required");
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
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only PENDING or ON_HOLD purchase orders can be placed");
        }
        List<PurchaseOrderLineItem> lines = lineRepository.findByPurchaseOrderIdOrderByCreatedAtAsc(poId);
        if (lines.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Cannot place a purchase order with zero line items");
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
                stockService.adjustOnOrderDelta(line.getPartId(), onOrderUnits);
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
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot place a completed or cancelled order on hold");
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
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order is already " + oldStatus);
        }

        if (PurchaseOrder.Status.PLACED.name().equals(oldStatus)
                || PurchaseOrder.Status.IN_PROGRESS.name().equals(oldStatus)
                || PurchaseOrder.Status.ON_HOLD.name().equals(oldStatus)) {
            for (PurchaseOrderLineItem line : lineRepository.findByPurchaseOrderIdOrderByCreatedAtAsc(poId)) {
                BigDecimal unreceivedUnits = line.remainingPacks().multiply(line.getPackQuantity());
                if (unreceivedUnits.compareTo(BigDecimal.ZERO) > 0) {
                    stockService.adjustOnOrderDelta(line.getPartId(), unreceivedUnits.negate());
                }
            }
        }

        po.setStatus(PurchaseOrder.Status.CANCELLED.name());
        PurchaseOrder saved = poRepository.saveAndFlush(po);
        auditService.record("PurchaseOrder", saved.getId(), "STATUS_CHANGE",
                Map.of("status", Map.of("from", oldStatus, "to", saved.getStatus())), null);
        return toPurchaseOrderView(saved);
    }

    /**
     * Receives one or more PO line items into warehouse stock, converting receivedPacks * packQuantity
     * into internal Part units, stamping purchase price + currency, and atomically moving quantities
     * from PartStockSummary.onOrder to PartStockSummary.inStock.
     */
    @Transactional
    public ReceiveLineResult receiveLineItems(UUID poId, List<ReceiveLineRequest> requests) {
        PurchaseOrder po = requirePoForUpdate(poId);
        if (!PurchaseOrder.Status.PLACED.name().equals(po.getStatus())
                && !PurchaseOrder.Status.IN_PROGRESS.name().equals(po.getStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Purchase order must be PLACED or IN_PROGRESS to receive items (current: " + po.getStatus() + ")"
            );
        }
        if (requests == null || requests.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one receive line request is required");
        }

        List<StockService.StockItemView> createdStock = new ArrayList<>();

        for (ReceiveLineRequest req : requests) {
            if (req.lineItemId() == null || req.quantityPacks() == null || req.quantityPacks().compareTo(BigDecimal.ZERO) <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "lineItemId and positive quantityPacks are required");
            }
            PurchaseOrderLineItem line = lineRepository.findByIdAndPurchaseOrderIdForUpdate(req.lineItemId(), poId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PO line item not found: " + req.lineItemId()));

            BigDecimal remaining = line.remainingPacks();
            if (req.quantityPacks().compareTo(remaining) > 0) {
                throw new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "Cannot receive " + req.quantityPacks() + " packs; only " + remaining + " packs remaining"
                );
            }

            UUID destLocId = req.locationId() != null ? req.locationId() : line.getDestinationLocationId();
            if (destLocId == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Destination locationId is required for receiving");
            }

            BigDecimal internalQty = req.quantityPacks().multiply(line.getPackQuantity());
            BigDecimal internalUnitCost = line.getPackQuantity().compareTo(BigDecimal.ZERO) > 0
                    ? line.getUnitPrice().divide(line.getPackQuantity(), 4, RoundingMode.HALF_UP)
                    : line.getUnitPrice();

            if (req.serialNumbers() != null && !req.serialNumbers().isEmpty()) {
                if (BigDecimal.valueOf(req.serialNumbers().size()).compareTo(internalQty) != 0) {
                    throw new ResponseStatusException(
                            HttpStatus.UNPROCESSABLE_ENTITY,
                            "Serial number count (" + req.serialNumbers().size() + ") must match received internal units (" + internalQty + ")"
                    );
                }
                for (String sn : req.serialNumbers()) {
                    createdStock.add(stockService.createStock(new StockService.CreateStockCommand(
                            line.getPartId(),
                            destLocId,
                            BigDecimal.ONE,
                            sn,
                            req.batchCode(),
                            "OK",
                            null,
                            internalUnitCost,
                            line.getCurrency(),
                            null,
                            false,
                            null,
                            req.notes() != null ? req.notes() : "Received on " + po.getReference()
                    )));
                }
            } else {
                createdStock.add(stockService.createStock(new StockService.CreateStockCommand(
                        line.getPartId(),
                        destLocId,
                        internalQty,
                        null,
                        req.batchCode(),
                        "OK",
                        null,
                        internalUnitCost,
                        line.getCurrency(),
                        null,
                        false,
                        null,
                        req.notes() != null ? req.notes() : "Received on " + po.getReference()
                )));
            }

            line.setReceived(line.getReceived().add(req.quantityPacks()));
            lineRepository.saveAndFlush(line);

            // Move quantity from onOrder -> inStock (inStock was already incremented by StockService.createStock)
            stockService.adjustOnOrderDelta(line.getPartId(), internalQty.negate());
        }

        // Check if all lines are now fully received
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
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Purchase order not found: " + id));
    }

    private PurchaseOrder requirePoForUpdate(UUID id) {
        return poRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Purchase order not found: " + id));
    }

    private PurchaseOrderView toPurchaseOrderView(PurchaseOrder po) {
        CompanyService.CompanyView supplier = companyService.getCompany(po.getSupplierId());
        String baseCurrency = currencyService.getTenantBaseCurrency();
        String destLocPath = po.getDestinationLocationId() != null
                ? locationService.getLocation(po.getDestinationLocationId()).pathString()
                : null;

        List<PurchaseOrderLineItem> lines = lineRepository.findByPurchaseOrderIdOrderByCreatedAtAsc(po.getId());
        List<PurchaseOrderLineView> lineViews = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;

        for (PurchaseOrderLineItem l : lines) {
            Part part = partService.requirePart(l.getPartId());
            CompanyService.SupplierPartView sp = companyService.getSupplierPart(l.getSupplierPartId());
            String lineLocPath = l.getDestinationLocationId() != null
                    ? locationService.getLocation(l.getDestinationLocationId()).pathString()
                    : null;
            BigDecimal lineTotal = l.getQuantity().multiply(l.getUnitPrice()).setScale(4, RoundingMode.HALF_UP);
            BigDecimal lineTotalInBase = currencyService.convert(lineTotal, l.getCurrency(), baseCurrency);
            total = total.add(lineTotal);

            lineViews.add(new PurchaseOrderLineView(
                    l.getId(),
                    l.getPurchaseOrderId(),
                    l.getSupplierPartId(),
                    sp.sku(),
                    part.getId(),
                    part.getIpn(),
                    part.getName(),
                    part.getUnit(),
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

        BigDecimal totalInBase = currencyService.convert(total, po.getCurrency(), baseCurrency);

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
