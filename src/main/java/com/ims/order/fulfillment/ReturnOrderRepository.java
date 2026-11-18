package com.ims.order.fulfillment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import java.util.UUID;

public interface ReturnOrderRepository extends JpaRepository<ReturnOrder, UUID>, JpaSpecificationExecutor<ReturnOrder> {
}
