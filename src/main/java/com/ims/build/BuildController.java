package com.ims.build;

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

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiresFeature(Feature.MANUFACTURING)
public class BuildController {

    private final BuildService buildService;

    public BuildController(BuildService buildService) {
        this.buildService = buildService;
    }

    @GetMapping("/parts/{partId}/max-buildable")
    @PreAuthorize("hasAuthority('BUILD:VIEW')")
    public Map<String, BigDecimal> getMaxBuildable(@PathVariable UUID partId) {
        return Map.of("maxBuildable", buildService.getMaxBuildableQuantity(partId));
    }

    @GetMapping("/builds")
    @PreAuthorize("hasAuthority('BUILD:VIEW')")
    public Page<BuildService.BuildOrderView> searchBuilds(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID partId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size
    ) {
        return buildService.searchBuildOrders(
                q, status, partId,
                PageRequest.of(page, Math.min(size, 200), Sort.by(Sort.Direction.DESC, "createdAt"))
        );
    }

    @GetMapping("/builds/{id}")
    @PreAuthorize("hasAuthority('BUILD:VIEW')")
    public BuildService.BuildOrderView getBuild(@PathVariable UUID id) {
        return buildService.getBuildOrder(id);
    }

    @PostMapping("/builds")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('BUILD:CREATE')")
    public BuildService.BuildOrderView createBuild(@Valid @RequestBody BuildService.CreateBuildOrderCommand command) {
        return buildService.createBuildOrder(command);
    }

    @PostMapping("/builds/{id}/start")
    @PreAuthorize("hasAuthority('BUILD:UPDATE')")
    public BuildService.BuildOrderView startBuild(@PathVariable UUID id) {
        return buildService.startBuild(id);
    }

    @PostMapping("/builds/{id}/allocate")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('BUILD:UPDATE')")
    public BuildService.BuildAllocationView allocateStock(
            @PathVariable UUID id,
            @Valid @RequestBody BuildService.AllocateStockCommand command
    ) {
        return buildService.allocateStock(id, command);
    }

    @PostMapping("/builds/{id}/auto-allocate")
    @PreAuthorize("hasAuthority('BUILD:UPDATE')")
    public BuildService.BuildOrderView autoAllocate(@PathVariable UUID id) {
        return buildService.autoAllocateFifo(id);
    }

    @PostMapping("/builds/{id}/outputs")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('BUILD:UPDATE')")
    public com.ims.stock.StockService.StockItemView createBuildOutput(
            @PathVariable UUID id,
            @Valid @RequestBody BuildService.CreateBuildOutputCommand command
    ) {
        return buildService.createBuildOutput(id, command);
    }

    @PostMapping("/builds/{id}/complete-output")
    @PreAuthorize("hasAuthority('BUILD:UPDATE')")
    public BuildService.BuildOrderView completeBuildOutput(
            @PathVariable UUID id,
            @Valid @RequestBody BuildService.CompleteBuildOutputCommand command
    ) {
        return buildService.completeBuildOutput(id, command);
    }

    @PostMapping("/builds/{id}/cancel")
    @PreAuthorize("hasAuthority('BUILD:UPDATE')")
    public BuildService.BuildOrderView cancelBuild(@PathVariable UUID id) {
        return buildService.cancelBuild(id);
    }
}
