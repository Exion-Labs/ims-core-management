package com.ims.order.fulfillment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import java.util.UUID;

public interface SalesOrderRepository extends JpaRepository<SalesOrder, UUID>, JpaSpecificationExecutor<SalesOrder> {
}
