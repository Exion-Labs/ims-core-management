package com.ims.order.purchase;

import com.ims.common.entitlement.Feature;
import com.ims.common.entitlement.RequiresFeature;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/purchase-orders")
@RequiresFeature(Feature.PURCHASING)
public class PurchaseOrderController {

    public record ReceiveBatchPayload(List<PurchaseOrderService.ReceiveLineRequest> lines) {}

    private final PurchaseOrderService purchaseOrderService;

    public PurchaseOrderController(PurchaseOrderService purchaseOrderService) {
        this.purchaseOrderService = purchaseOrderService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('ORDER:VIEW')")
    public Page<PurchaseOrderService.PurchaseOrderView> searchPurchaseOrders(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID supplierId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size
    ) {
        return purchaseOrderService.searchPurchaseOrders(
                q, status, supplierId,
                PageRequest.of(page, Math.min(size, 200), Sort.by(Sort.Direction.DESC, "createdAt"))
        );
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('ORDER:VIEW')")
    public PurchaseOrderService.PurchaseOrderView getPurchaseOrder(@PathVariable UUID id) {
        return purchaseOrderService.getPurchaseOrder(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('ORDER:CREATE')")
    public PurchaseOrderService.PurchaseOrderView createPurchaseOrder(
            @Valid @RequestBody PurchaseOrderService.CreatePurchaseOrderCommand command
    ) {
        return purchaseOrderService.createPurchaseOrder(command);
    }

    @PostMapping("/{id}/lines")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('ORDER:UPDATE')")
    public PurchaseOrderService.PurchaseOrderView addLineItem(
            @PathVariable UUID id,
            @Valid @RequestBody PurchaseOrderService.AddLineItemCommand command
    ) {
        return purchaseOrderService.addLineItem(id, command);
    }

    @DeleteMapping("/{id}/lines/{lineId}")
    @PreAuthorize("hasAuthority('ORDER:UPDATE')")
    public PurchaseOrderService.PurchaseOrderView removeLineItem(
            @PathVariable UUID id,
            @PathVariable UUID lineId
    ) {
        return purchaseOrderService.removeLineItem(id, lineId);
    }

    @PostMapping("/{id}/extra-lines")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('ORDER:UPDATE')")
    public PurchaseOrderService.PurchaseOrderView addExtraLine(
            @PathVariable UUID id,
            @Valid @RequestBody PurchaseOrderService.AddExtraLineCommand command
    ) {
        return purchaseOrderService.addExtraLine(id, command);
    }

    @PostMapping("/{id}/place")
    @PreAuthorize("hasAuthority('ORDER:UPDATE')")
    public PurchaseOrderService.PurchaseOrderView placeOrder(@PathVariable UUID id) {
        return purchaseOrderService.placeOrder(id);
    }

    @PostMapping("/{id}/hold")
    @PreAuthorize("hasAuthority('ORDER:UPDATE')")
    public PurchaseOrderService.PurchaseOrderView holdOrder(@PathVariable UUID id) {
        return purchaseOrderService.holdOrder(id);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('ORDER:UPDATE')")
    public PurchaseOrderService.PurchaseOrderView cancelOrder(@PathVariable UUID id) {
        return purchaseOrderService.cancelOrder(id);
    }

    @PostMapping("/{id}/receive")
    @PreAuthorize("hasAuthority('ORDER:UPDATE')")
    public PurchaseOrderService.ReceiveLineResult receiveLineItems(
            @PathVariable UUID id,
            @Valid @RequestBody ReceiveBatchPayload payload
    ) {
        return purchaseOrderService.receiveLineItems(id, payload != null ? payload.lines() : List.of());
    }

    @GetMapping(value = "/{id}/document", produces = MediaType.TEXT_HTML_VALUE)
    @PreAuthorize("hasAuthority('ORDER:VIEW')")
    public ResponseEntity<String> getDocumentHtml(@PathVariable UUID id) {
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(purchaseOrderService.renderBasicDocumentHtml(id));
    }
}
