package com.ims.stock.api;

import com.ims.stock.application.*;

import com.ims.common.idempotency.IdempotencyExempt;
import com.ims.entitlement.spi.Feature;
import com.ims.entitlement.spi.RequiresFeature;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiresFeature(Feature.STOCK)
public class StockController {

    public record MoveLocationPayload(UUID newParentId) {}

    public record BarcodeScanRequest(String barcode) {}

    public record BatchSummariesRequest(List<UUID> partIds) {}

    private final StockLocationService locationService;
    private final StockService stockService;
    private final BarcodeService barcodeService;
    private final StockReconciliationJob reconciliationJob;

    public StockController(
            StockLocationService locationService,
            StockService stockService,
            BarcodeService barcodeService,
            StockReconciliationJob reconciliationJob
    ) {
        this.locationService = locationService;
        this.stockService = stockService;
        this.barcodeService = barcodeService;
        this.reconciliationJob = reconciliationJob;
    }

    // =========================================================================
    // 2.1 Stock Locations (ltree)
    // =========================================================================

    @GetMapping("/stock-locations")
    @PreAuthorize("hasAuthority('STOCK:VIEW')")
    public List<StockLocationService.LocationView> listLocations() {
        return locationService.listLocations();
    }

    @GetMapping("/stock-locations/{id}")
    @PreAuthorize("hasAuthority('STOCK:VIEW')")
    public StockLocationService.LocationView getLocation(@PathVariable UUID id) {
        return locationService.getLocation(id);
    }

    @PostMapping("/stock-locations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('STOCK:CREATE')")
    public StockLocationService.LocationView createLocation(
            @Valid @RequestBody StockLocationService.CreateLocationCommand command
    ) {
        return locationService.createLocation(command);
    }

    @PutMapping("/stock-locations/{id}")
    @PreAuthorize("hasAuthority('STOCK:UPDATE')")
    public StockLocationService.LocationView updateLocation(
            @PathVariable UUID id,
            @Valid @RequestBody StockLocationService.UpdateLocationCommand command
    ) {
        return locationService.updateLocation(id, command);
    }

    @PostMapping("/stock-locations/{id}/move")
    @PreAuthorize("hasAuthority('STOCK:UPDATE')")
    public StockLocationService.LocationView moveLocationSubtree(
            @PathVariable UUID id,
            @RequestBody MoveLocationPayload payload
    ) {
        return locationService.moveSubtree(id, payload != null ? payload.newParentId() : null);
    }

    @DeleteMapping("/stock-locations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('STOCK:DELETE')")
    public void deleteLocation(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "REPARENT") String mode
    ) {
        locationService.deleteLocation(id, mode);
    }

    // =========================================================================
    // 2.2 - 2.4 Stock Items, PartStockSummary & Atomic Operations
    // =========================================================================

    @GetMapping("/stock-items")
    @PreAuthorize("hasAuthority('STOCK:VIEW')")
    public Page<StockService.StockItemView> searchStockItems(
            @RequestParam(required = false) UUID partId,
            @RequestParam(required = false) UUID locationId,
            @RequestParam(defaultValue = "true") boolean includeSublocations,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String batchCode,
            @RequestParam(required = false) String serialNumber,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size
    ) {
        StockService.StockSearchFilter filter = new StockService.StockSearchFilter(
                partId, locationId, includeSublocations, status, batchCode, serialNumber, q
        );
        return stockService.searchStockItems(
                filter,
                PageRequest.of(page, Math.min(size, 200), Sort.by(Sort.Direction.DESC, "createdAt"))
        );
    }

    @GetMapping("/stock-items/{id}")
    @PreAuthorize("hasAuthority('STOCK:VIEW')")
    public StockService.StockItemView getStockItem(@PathVariable UUID id) {
        return stockService.getStockItem(id);
    }

    @PostMapping("/stock-items")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('STOCK:CREATE')")
    public StockService.StockItemView createStock(
            @Valid @RequestBody StockService.CreateStockCommand command
    ) {
        return stockService.createStock(command);
    }

    @PostMapping("/stock-items/{id}/count")
    @PreAuthorize("hasAuthority('STOCK:UPDATE')")
    public StockService.StockItemView countStock(
            @PathVariable UUID id,
            @Valid @RequestBody StockService.StockQuantityOperationCommand command
    ) {
        return stockService.countStock(id, command);
    }

    @PostMapping("/stock-items/{id}/add")
    @PreAuthorize("hasAuthority('STOCK:UPDATE')")
    public StockService.StockItemView addStock(
            @PathVariable UUID id,
            @Valid @RequestBody StockService.StockQuantityOperationCommand command
    ) {
        return stockService.addStock(id, command);
    }

    @PostMapping("/stock-items/{id}/remove")
    @PreAuthorize("hasAuthority('STOCK:UPDATE')")
    public StockService.StockItemView removeStock(
            @PathVariable UUID id,
            @Valid @RequestBody StockService.StockQuantityOperationCommand command
    ) {
        return stockService.removeStock(id, command);
    }

    @PostMapping("/stock-items/{id}/transfer")
    @PreAuthorize("hasAuthority('STOCK:UPDATE')")
    public StockService.StockItemView transferStock(
            @PathVariable UUID id,
            @Valid @RequestBody StockService.TransferStockCommand command
    ) {
        return stockService.transferStock(id, command);
    }

    @PostMapping("/stock-items/{id}/split")
    @PreAuthorize("hasAuthority('STOCK:UPDATE')")
    public StockService.StockItemView splitStock(
            @PathVariable UUID id,
            @Valid @RequestBody StockService.SplitStockCommand command
    ) {
        return stockService.splitStock(id, command);
    }

    @PostMapping("/stock-items/merge")
    @PreAuthorize("hasAuthority('STOCK:UPDATE')")
    public StockService.StockItemView mergeStock(
            @Valid @RequestBody StockService.MergeStockCommand command
    ) {
        return stockService.mergeStock(command);
    }

    @PostMapping("/stock-items/{id}/serialize")
    @PreAuthorize("hasAuthority('STOCK:UPDATE')")
    @RequiresFeature(Feature.SERIAL_TRACKING)
    public List<StockService.StockItemView> serializeStock(
            @PathVariable UUID id,
            @Valid @RequestBody StockService.SerializeStockCommand command
    ) {
        return stockService.serializeStock(id, command);
    }

    @GetMapping("/stock-items/{id}/tracking")
    @PreAuthorize("hasAuthority('STOCK:VIEW')")
    public List<StockService.StockTrackingView> listTrackingHistory(@PathVariable UUID id) {
        return stockService.listTrackingHistory(id);
    }

    @GetMapping("/parts/{partId}/stock-summary")
    @PreAuthorize("hasAuthority('STOCK:VIEW')")
    public StockService.StockSummaryView getPartStockSummary(@PathVariable UUID partId) {
        return stockService.getPartStockSummary(partId);
    }

    @IdempotencyExempt(reason = "Read-only batch lookup exposed via POST because of the request body size")
    @PostMapping("/stock/summaries")
    @PreAuthorize("hasAuthority('STOCK:VIEW')")
    public Map<UUID, StockService.StockSummaryView> getBatchStockSummaries(
            @RequestBody BatchSummariesRequest request
    ) {
        return stockService.getStockSummariesForParts(request != null ? request.partIds() : List.of());
    }

    @PostMapping("/stock/reconcile")
    @PreAuthorize("hasAuthority('STOCK:UPDATE')")
    public StockReconciliationJob.ReconciliationReport reconcileStock() {
        return reconciliationJob.reconcileCurrentTenant();
    }

    // =========================================================================
    // 2.5 Quality Control (Test Templates & Test Results)
    // =========================================================================

    @GetMapping("/parts/{partId}/test-templates")
    @PreAuthorize("hasAuthority('STOCK:VIEW')")
    @RequiresFeature(Feature.QUALITY_TESTS)
    public List<StockService.PartTestTemplateView> listTestTemplates(@PathVariable UUID partId) {
        return stockService.listTestTemplates(partId);
    }

    @PostMapping("/parts/{partId}/test-templates")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('STOCK:CREATE')")
    @RequiresFeature(Feature.QUALITY_TESTS)
    public StockService.PartTestTemplateView createTestTemplate(
            @PathVariable UUID partId,
            @Valid @RequestBody StockService.CreateTestTemplateCommand command
    ) {
        return stockService.createTestTemplate(partId, command);
    }

    @GetMapping("/stock-items/{id}/test-results")
    @PreAuthorize("hasAuthority('STOCK:VIEW')")
    @RequiresFeature(Feature.QUALITY_TESTS)
    public List<StockService.StockItemTestResultView> listTestResults(@PathVariable UUID id) {
        return stockService.listTestResults(id);
    }

    @PostMapping("/stock-items/{id}/test-results")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('STOCK:UPDATE')")
    @RequiresFeature(Feature.QUALITY_TESTS)
    public StockService.StockItemTestResultView recordTestResult(
            @PathVariable UUID id,
            @Valid @RequestBody StockService.RecordTestResultCommand command
    ) {
        return stockService.recordTestResult(id, command);
    }

    // =========================================================================
    // 2.6 Barcodes & Scan Resolver
    // =========================================================================

    @IdempotencyExempt(reason = "Read-only barcode resolution exposed via POST")
    @PostMapping("/barcode/scan")
    @PreAuthorize("hasAuthority('STOCK:VIEW')")
    @RequiresFeature(Feature.BARCODES)
    public BarcodeService.ScanResolutionResult resolveScan(@RequestBody BarcodeScanRequest request) {
        return barcodeService.resolveScan(request != null ? request.barcode() : "");
    }

    @GetMapping(value = "/barcode/image", produces = MediaType.IMAGE_PNG_VALUE)
    @PreAuthorize("hasAuthority('STOCK:VIEW')")
    @RequiresFeature(Feature.BARCODES)
    public ResponseEntity<byte[]> generateBarcodeImage(
            @RequestParam String code,
            @RequestParam(defaultValue = "QR_CODE") String format,
            @RequestParam(defaultValue = "180") int width,
            @RequestParam(defaultValue = "180") int height
    ) {
        byte[] png = barcodeService.generateBarcodePng(code, format, width, height);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(png);
    }
}
