package com.ims.stock.infrastructure.persistence;

import com.ims.stock.domain.model.*;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface StockItemTestResultRepository extends JpaRepository<StockItemTestResult, UUID> {

    List<StockItemTestResult> findByStockItemIdOrderByCreatedAtDesc(UUID stockItemId);
}
