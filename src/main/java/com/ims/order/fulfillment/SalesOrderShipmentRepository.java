package com.ims.order.fulfillment;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface SalesOrderShipmentRepository extends JpaRepository<SalesOrderShipment, UUID> {
    List<SalesOrderShipment> findBySalesOrderIdOrderByCreatedAtAsc(UUID salesOrderId);
}
