package com.ims.fulfillment.api;

import com.ims.fulfillment.application.*;

import com.ims.entitlement.spi.Feature;
import com.ims.entitlement.spi.RequiresFeature;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/sales-orders")
@RequiresFeature(Feature.SALES)
public class SalesOrderController {

    private final SalesOrderService salesOrderService;

    public SalesOrderController(SalesOrderService salesOrderService) {
        this.salesOrderService = salesOrderService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('ORDER:VIEW')")
    public Page<SalesOrderService.SalesOrderView> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size
    ) {
        return salesOrderService.searchSalesOrders(
                q, status, PageRequest.of(page, Math.min(size, 200), Sort.by(Sort.Direction.DESC, "createdAt"))
        );
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('ORDER:VIEW')")
    public SalesOrderService.SalesOrderView get(@PathVariable UUID id) {
        return salesOrderService.getSalesOrder(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('ORDER:CREATE')")
    public SalesOrderService.SalesOrderView create(@Valid @RequestBody SalesOrderService.CreateSalesOrderCommand cmd) {
        return salesOrderService.createSalesOrder(cmd);
    }

    @PostMapping("/{id}/lines")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('ORDER:UPDATE')")
    public SalesOrderService.SalesOrderView addLine(
            @PathVariable UUID id,
            @Valid @RequestBody SalesOrderService.AddSalesOrderLineCommand cmd
    ) {
        return salesOrderService.addLineItem(id, cmd);
    }

    @PostMapping("/{id}/issue")
    @PreAuthorize("hasAuthority('ORDER:UPDATE')")
    public SalesOrderService.SalesOrderView issue(@PathVariable UUID id) {
        return salesOrderService.issueSalesOrder(id);
    }

    @PostMapping("/{id}/shipments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('ORDER:UPDATE')")
    public SalesOrderService.SalesOrderShipmentView createShipment(
            @PathVariable UUID id,
            @RequestBody(required = false) SalesOrderService.CreateShipmentCommand cmd
    ) {
        return salesOrderService.createShipment(id, cmd);
    }

    @PostMapping("/{id}/allocate")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('ORDER:UPDATE')")
    public SalesOrderService.SalesOrderAllocationView allocate(
            @PathVariable UUID id,
            @Valid @RequestBody SalesOrderService.AllocateSalesStockCommand cmd
    ) {
        return salesOrderService.allocateStock(id, cmd);
    }

    @DeleteMapping("/{id}/allocations/{allocationId}")
    @PreAuthorize("hasAuthority('ORDER:UPDATE')")
    public SalesOrderService.SalesOrderView deallocate(
            @PathVariable UUID id,
            @PathVariable UUID allocationId
    ) {
        return salesOrderService.deallocateAllocation(id, allocationId);
    }

    @PostMapping("/{id}/shipments/{shipmentId}/complete")
    @PreAuthorize("hasAuthority('ORDER:UPDATE')")
    public SalesOrderService.SalesOrderView completeShipment(
            @PathVariable UUID id,
            @PathVariable UUID shipmentId,
            @RequestBody(required = false) SalesOrderService.CompleteShipmentCommand cmd
    ) {
        return salesOrderService.completeShipment(id, shipmentId, cmd);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('ORDER:UPDATE')")
    public SalesOrderService.SalesOrderView cancel(@PathVariable UUID id) {
        return salesOrderService.cancelSalesOrder(id);
    }
}
