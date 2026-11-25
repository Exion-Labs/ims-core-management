package com.ims.manufacturing.infrastructure.persistence;

import com.ims.manufacturing.domain.model.*;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface BuildAllocationRepository extends JpaRepository<BuildAllocation, UUID> {

    List<BuildAllocation> findByBuildOrderIdOrderByCreatedAtAsc(UUID buildOrderId);

    List<BuildAllocation> findByBuildLineIdOrderByCreatedAtAsc(UUID buildLineId);

    @Query("select coalesce(sum(a.quantity), 0) from BuildAllocation a where a.stockItemId = :stockItemId and a.consumed = false")
    BigDecimal sumUnconsumedAllocatedQuantity(@Param("stockItemId") UUID stockItemId);
}
