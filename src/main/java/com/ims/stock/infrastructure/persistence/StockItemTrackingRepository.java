package com.ims.stock.infrastructure.persistence;

import com.ims.stock.domain.model.*;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface StockItemTrackingRepository extends JpaRepository<StockItemTracking, UUID> {

    List<StockItemTracking> findByStockItemIdOrderByCreatedAtDesc(UUID stockItemId);

    List<StockItemTracking> findTop50ByPartIdOrderByCreatedAtDesc(UUID partId);
}
