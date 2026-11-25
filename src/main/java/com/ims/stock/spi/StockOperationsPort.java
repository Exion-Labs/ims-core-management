package com.ims.stock.spi;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Public command port for atomic, row-locked stock mutations and ledger adjustments.
 */
public interface StockOperationsPort {

    record CreateStockSpec(
            UUID partId,
            UUID locationId,
            BigDecimal quantity,
            String serialNumber,
            String batchCode,
            String status,
            LocalDate expiryDate,
            BigDecimal purchasePrice,
            String currency,
            String packaging,
            Boolean building,
            String customBarcode,
            String notes
    ) {}

    StockReadPort.StockItemSnapshot lockStockItemForUpdate(UUID stockItemId);

    List<StockReadPort.StockItemSnapshot> lockAvailableFifoForUpdate(UUID partId);

    StockReadPort.StockItemView createStock(CreateStockSpec spec);

    void removeStock(UUID stockItemId, BigDecimal quantity, String notes);

    void adjustOnOrderDelta(UUID partId, BigDecimal delta);

    void adjustAllocatedToBuildsDelta(UUID partId, BigDecimal delta);

    void adjustAllocatedToSalesDelta(UUID partId, BigDecimal delta);

    void completeBuildOutputItem(UUID outputStockItemId, UUID destinationLocationId, String notes);

    void installStockItemIntoAssembly(UUID componentStockItemId, UUID assemblyStockItemId, String notes);

    void shipAllocatedStockToCustomer(UUID stockItemId, BigDecimal quantity, UUID customerId, String notes);

    void returnStockItemFromCustomer(UUID stockItemId, UUID quarantineLocationId, String status, String notes);

    void updateStockStatus(UUID stockItemId, String newStatus, UUID newLocationId, String notes);

    void transferStock(UUID stockItemId, UUID targetLocationId, BigDecimal quantity, String notes);
}
