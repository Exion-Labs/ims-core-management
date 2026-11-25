package com.ims.fulfillment.infrastructure.persistence;

import com.ims.fulfillment.domain.model.*;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface SalesOrderLineItemRepository extends JpaRepository<SalesOrderLineItem, UUID> {
    List<SalesOrderLineItem> findBySalesOrderIdOrderByCreatedAtAsc(UUID salesOrderId);
}
