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
@RequestMapping("/api/v1/transfer-orders")
@RequiresFeature(Feature.TRANSFERS)
public class TransferOrderController {

    private final TransferOrderService transferOrderService;

    public TransferOrderController(TransferOrderService transferOrderService) {
        this.transferOrderService = transferOrderService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('ORDER:VIEW')")
    public Page<TransferOrderService.TransferOrderView> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size
    ) {
        return transferOrderService.searchTransferOrders(
                q, status, PageRequest.of(page, Math.min(size, 200), Sort.by(Sort.Direction.DESC, "createdAt"))
        );
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('ORDER:VIEW')")
    public TransferOrderService.TransferOrderView get(@PathVariable UUID id) {
        return transferOrderService.getTransferOrder(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('ORDER:CREATE')")
    public TransferOrderService.TransferOrderView create(@Valid @RequestBody TransferOrderService.CreateTransferOrderCommand cmd) {
        return transferOrderService.createTransferOrder(cmd);
    }

    @PostMapping("/{id}/lines")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('ORDER:UPDATE')")
    public TransferOrderService.TransferOrderView addLine(
            @PathVariable UUID id,
            @Valid @RequestBody TransferOrderService.AddTransferLineCommand cmd
    ) {
        return transferOrderService.addLineItem(id, cmd);
    }

    @PostMapping("/{id}/dispatch")
    @PreAuthorize("hasAuthority('ORDER:UPDATE')")
    public TransferOrderService.TransferOrderView dispatch(@PathVariable UUID id) {
        return transferOrderService.dispatchTransfer(id);
    }

    @PostMapping("/{id}/receive")
    @PreAuthorize("hasAuthority('ORDER:UPDATE')")
    public TransferOrderService.TransferOrderView receive(@PathVariable UUID id) {
        return transferOrderService.receiveTransfer(id);
    }
}
