package com.ims.order.purchase;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PurchaseOrderExtraLineRepository extends JpaRepository<PurchaseOrderExtraLine, UUID> {

    List<PurchaseOrderExtraLine> findByPurchaseOrderIdOrderByCreatedAtAsc(UUID purchaseOrderId);
}
