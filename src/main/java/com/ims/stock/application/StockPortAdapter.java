package com.ims.stock.application;

import com.ims.stock.domain.model.StockItem;
import com.ims.stock.domain.model.StockLocation;
import com.ims.stock.infrastructure.persistence.StockItemRepository;
import com.ims.stock.infrastructure.persistence.StockLocationRepository;
import com.ims.stock.spi.StockOperationsPort;
import com.ims.stock.spi.StockReadPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class StockPortAdapter implements StockReadPort, StockOperationsPort {

    private final StockService stockService;
    private final StockLocationService locationService;
    private final StockItemRepository stockItemRepository;
    private final StockLocationRepository locationRepository;

    public StockPortAdapter(
            StockService stockService,
            StockLocationService locationService,
            StockItemRepository stockItemRepository,
            StockLocationRepository locationRepository
    ) {
        this.stockService = stockService;
        this.locationService = locationService;
        this.stockItemRepository = stockItemRepository;
        this.locationRepository = locationRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public StockItemSnapshot requireStockItemSnapshot(UUID stockItemId) {
        return toSnapshot(stockService.requireStockItem(stockItemId));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, StockItemSnapshot> getStockItemSnapshots(Collection<UUID> stockItemIds) {
        Map<UUID, StockItemSnapshot> map = new HashMap<>();
        if (stockItemIds == null || stockItemIds.isEmpty()) {
            return map;
        }
        for (StockItem item : stockItemRepository.findAllById(stockItemIds)) {
            map.put(item.getId(), toSnapshot(item));
        }
        return map;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, StockSummaryView> getStockSummariesForParts(Collection<UUID> partIds) {
        Map<UUID, StockSummaryView> out = new HashMap<>();
        stockService.getStockSummariesForParts(partIds).forEach((k, v) -> out.put(k, new StockSummaryView(
                v.partId(), v.inStock(), v.allocatedToBuilds(), v.allocatedToSales(),
                v.availableStock(), v.onOrder(), v.inProduction()
        )));
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public LocationView requireLocation(UUID locationId) {
        return toLocationView(locationService.getLocation(locationId));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, LocationView> getLocations(Collection<UUID> locationIds) {
        Map<UUID, LocationView> map = new HashMap<>();
        if (locationIds == null || locationIds.isEmpty()) {
            return map;
        }
        for (StockLocation loc : locationRepository.findAllById(locationIds)) {
            map.put(loc.getId(), new LocationView(
                    loc.getId(), loc.getName(), loc.getDescription(), loc.getParentId(),
                    loc.getPath(), loc.getPathString(), loc.getPath().split("\\.").length,
                    loc.getLocationType(), loc.isStructural(), loc.isExternalLocation(),
                    loc.getCustomBarcode(), 0L, loc.getCreatedAt(), loc.getUpdatedAt()
            ));
        }
        return map;
    }

    @Override
    @Transactional(readOnly = true)
    public long countLocations() {
        return locationService.countLocations();
    }

    @Override
    @Transactional(readOnly = true)
    public long countStockItems() {
        return stockItemRepository.count();
    }

    @Override
    public StockItemSnapshot lockStockItemForUpdate(UUID stockItemId) {
        return toSnapshot(stockService.requireStockItemForUpdate(stockItemId));
    }

    @Override
    public List<StockItemSnapshot> lockAvailableFifoForUpdate(UUID partId) {
        return stockService.findAvailableFifoForUpdate(partId).stream()
                .map(this::toSnapshot)
                .toList();
    }

    @Override
    @Transactional
    public StockItemView createStock(CreateStockSpec spec) {
        StockService.StockItemView v = stockService.createStock(new StockService.CreateStockCommand(
                spec.partId(), spec.locationId(), spec.quantity(), spec.serialNumber(),
                spec.batchCode(), spec.status(), spec.expiryDate(), spec.purchasePrice(),
                spec.currency(), spec.packaging(), spec.building(), spec.customBarcode(), spec.notes()
        ));
        return new StockItemView(
                v.id(), v.partId(), v.partIpn(), v.partName(), v.partUnit(), v.partTrackable(),
                v.locationId(), v.locationName(), v.locationPathString(), v.parentItemId(),
                v.installedInId(), v.customerId(), v.quantity(), v.serialNumber(), v.batchCode(),
                v.status(), v.expiryDate(), v.purchasePrice(), v.currency(), v.packaging(),
                v.building(), v.customBarcode(), v.createdAt(), v.updatedAt()
        );
    }

    @Override
    @Transactional
    public void removeStock(UUID stockItemId, BigDecimal quantity, String notes) {
        stockService.removeStock(stockItemId, new StockService.StockQuantityOperationCommand(quantity, notes));
    }

    @Override
    @Transactional
    public void adjustOnOrderDelta(UUID partId, BigDecimal delta) {
        stockService.adjustOnOrderDelta(partId, delta);
    }

    @Override
    @Transactional
    public void adjustAllocatedToBuildsDelta(UUID partId, BigDecimal delta) {
        stockService.adjustAllocatedToBuildsDelta(partId, delta);
    }

    @Override
    @Transactional
    public void adjustAllocatedToSalesDelta(UUID partId, BigDecimal delta) {
        stockService.adjustAllocatedToSalesDelta(partId, delta);
    }

    @Override
    @Transactional
    public void completeBuildOutputItem(UUID outputStockItemId, UUID destinationLocationId, String notes) {
        stockService.completeBuildOutputItem(outputStockItemId, destinationLocationId, notes);
    }

    @Override
    @Transactional
    public void installStockItemIntoAssembly(UUID componentStockItemId, UUID assemblyStockItemId, String notes) {
        stockService.installStockItemIntoAssembly(componentStockItemId, assemblyStockItemId, notes);
    }

    @Override
    @Transactional
    public void shipAllocatedStockToCustomer(UUID stockItemId, BigDecimal quantity, UUID customerId, String notes) {
        stockService.shipAllocatedStockToCustomer(stockItemId, quantity, customerId, notes);
    }

    @Override
    @Transactional
    public void returnStockItemFromCustomer(UUID stockItemId, UUID quarantineLocationId, String status, String notes) {
        stockService.returnStockItemFromCustomer(stockItemId, quarantineLocationId, status, notes);
    }

    @Override
    @Transactional
    public void updateStockStatus(UUID stockItemId, String newStatus, UUID newLocationId, String notes) {
        stockService.updateStockStatus(stockItemId, newStatus, newLocationId, notes);
    }

    @Override
    @Transactional
    public void transferStock(UUID stockItemId, UUID targetLocationId, BigDecimal quantity, String notes) {
        stockService.transferStock(stockItemId, new StockService.TransferStockCommand(targetLocationId, quantity, notes));
    }

    private StockItemSnapshot toSnapshot(StockItem s) {
        return new StockItemSnapshot(
                s.getId(), s.getPartId(), s.getLocationId(), s.getQuantity(),
                s.getSerialNumber(), s.getBatchCode(), s.getStatus(),
                s.isBuilding(), s.getCustomerId(), s.getInstalledInId(),
                s.countsTowardInStock()
        );
    }

    private LocationView toLocationView(StockLocationService.LocationView v) {
        return new LocationView(
                v.id(), v.name(), v.description(), v.parentId(), v.path(), v.pathString(),
                v.depth(), v.locationType(), v.structural(), v.externalLocation(), v.customBarcode(),
                v.directItemCount(), v.createdAt(), v.updatedAt()
        );
    }
}
