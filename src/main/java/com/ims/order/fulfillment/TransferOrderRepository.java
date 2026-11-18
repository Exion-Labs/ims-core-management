package com.ims.order.fulfillment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import java.util.UUID;

public interface TransferOrderRepository extends JpaRepository<TransferOrder, UUID>, JpaSpecificationExecutor<TransferOrder> {
}
