package com.ims.purchasing.infrastructure.persistence;

import com.ims.purchasing.domain.model.*;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PurchaseOrderExtraLineRepository extends JpaRepository<PurchaseOrderExtraLine, UUID> {

    List<PurchaseOrderExtraLine> findByPurchaseOrderIdOrderByCreatedAtAsc(UUID purchaseOrderId);
}
