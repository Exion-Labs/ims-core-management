package com.ims.stock.spi;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Public read port for Stock Items, Locations, and PartStockSummary balances.
 * Never exposes JPA @Entity instances outside com.ims.stock.
 */
public interface StockReadPort {

    record StockItemView(
            UUID id,
            UUID partId,
            String partIpn,
            String partName,
            String partUnit,
            boolean partTrackable,
            UUID locationId,
            String locationName,
            String locationPathString,
            UUID parentItemId,
            UUID installedInId,
            UUID customerId,
            BigDecimal quantity,
            String serialNumber,
            String batchCode,
            String status,
            LocalDate expiryDate,
            BigDecimal purchasePrice,
            String currency,
            String packaging,
            boolean building,
            String customBarcode,
            Instant createdAt,
            Instant updatedAt
    ) {}

    record StockSummaryView(
            UUID partId,
            BigDecimal inStock,
            BigDecimal allocatedToBuilds,
            BigDecimal allocatedToSales,
            BigDecimal availableStock,
            BigDecimal onOrder,
            BigDecimal inProduction
    ) {}

    record LocationView(
            UUID id,
            String name,
            String description,
            UUID parentId,
            String path,
            String pathString,
            int depth,
            String locationType,
            boolean structural,
            boolean external,
            String customBarcode,
            long itemCount,
            Instant createdAt,
            Instant updatedAt
    ) {}

    record StockItemSnapshot(
            UUID id,
            UUID partId,
            UUID locationId,
            BigDecimal quantity,
            String serialNumber,
            String batchCode,
            String status,
            boolean building,
            UUID customerId,
            UUID installedInId,
            boolean countsTowardInStock
    ) {}

    StockItemSnapshot requireStockItemSnapshot(UUID stockItemId);

    Map<UUID, StockItemSnapshot> getStockItemSnapshots(Collection<UUID> stockItemIds);

    Map<UUID, StockSummaryView> getStockSummariesForParts(Collection<UUID> partIds);

    LocationView requireLocation(UUID locationId);

    Map<UUID, LocationView> getLocations(Collection<UUID> locationIds);

    long countLocations();

    long countStockItems();
}
