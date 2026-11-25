package com.ims.fulfillment.infrastructure.persistence;

import com.ims.fulfillment.domain.model.*;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface ReturnOrderLineItemRepository extends JpaRepository<ReturnOrderLineItem, UUID> {
    List<ReturnOrderLineItem> findByReturnOrderIdOrderByCreatedAtAsc(UUID returnOrderId);
}
