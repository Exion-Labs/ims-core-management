package com.ims.stock;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface StockItemTrackingRepository extends JpaRepository<StockItemTracking, UUID> {

    List<StockItemTracking> findByStockItemIdOrderByCreatedAtDesc(UUID stockItemId);

    List<StockItemTracking> findTop50ByPartIdOrderByCreatedAtDesc(UUID partId);
}
