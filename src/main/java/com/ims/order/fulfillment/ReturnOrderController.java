package com.ims.order.fulfillment;

import com.ims.common.entitlement.Feature;
import com.ims.common.entitlement.RequiresFeature;
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
@RequestMapping("/api/v1/return-orders")
@RequiresFeature(Feature.RETURNS)
public class ReturnOrderController {

    private final ReturnOrderService returnOrderService;

    public ReturnOrderController(ReturnOrderService returnOrderService) {
        this.returnOrderService = returnOrderService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('ORDER:VIEW')")
    public Page<ReturnOrderService.ReturnOrderView> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size
    ) {
        return returnOrderService.searchReturnOrders(
                q, status, PageRequest.of(page, Math.min(size, 200), Sort.by(Sort.Direction.DESC, "createdAt"))
        );
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('ORDER:VIEW')")
    public ReturnOrderService.ReturnOrderView get(@PathVariable UUID id) {
        return returnOrderService.getReturnOrder(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('ORDER:CREATE')")
    public ReturnOrderService.ReturnOrderView create(@Valid @RequestBody ReturnOrderService.CreateReturnOrderCommand cmd) {
        return returnOrderService.createReturnOrder(cmd);
    }

    @PostMapping("/{id}/lines")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('ORDER:UPDATE')")
    public ReturnOrderService.ReturnOrderView addLine(
            @PathVariable UUID id,
            @Valid @RequestBody ReturnOrderService.AddReturnLineCommand cmd
    ) {
        return returnOrderService.addLineItem(id, cmd);
    }

    @PostMapping("/{id}/lines/{lineId}/receive")
    @PreAuthorize("hasAuthority('ORDER:UPDATE')")
    public ReturnOrderService.ReturnOrderView receiveLine(
            @PathVariable UUID id,
            @PathVariable UUID lineId,
            @Valid @RequestBody ReturnOrderService.ReceiveReturnLineCommand cmd
    ) {
        return returnOrderService.receiveLineItem(id, lineId, cmd);
    }
}
