package com.ims.order.purchase;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PurchaseOrderLineItemRepository extends JpaRepository<PurchaseOrderLineItem, UUID> {

    List<PurchaseOrderLineItem> findByPurchaseOrderIdOrderByCreatedAtAsc(UUID purchaseOrderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from PurchaseOrderLineItem l where l.id = :id and l.purchaseOrderId = :poId")
    Optional<PurchaseOrderLineItem> findByIdAndPurchaseOrderIdForUpdate(
            @Param("id") UUID id,
            @Param("poId") UUID poId
    );
}
