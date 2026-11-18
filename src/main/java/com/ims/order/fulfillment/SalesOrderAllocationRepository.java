package com.ims.order.fulfillment;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface SalesOrderAllocationRepository extends JpaRepository<SalesOrderAllocation, UUID> {
    List<SalesOrderAllocation> findByLineItemIdOrderByCreatedAtAsc(UUID lineItemId);
    List<SalesOrderAllocation> findByLineItemIdIn(List<UUID> lineItemIds);
    List<SalesOrderAllocation> findByShipmentId(UUID shipmentId);
}
