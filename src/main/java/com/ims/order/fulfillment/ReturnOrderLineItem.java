package com.ims.order.fulfillment;

import com.ims.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "return_order_line_items")
public class ReturnOrderLineItem extends BaseTenantEntity {

    @Column(name = "return_order_id", nullable = false)
    private UUID returnOrderId;

    @Column(name = "stock_item_id", nullable = false)
    private UUID stockItemId;

    @Column(nullable = false, precision = 19, scale = 6)
    private BigDecimal quantity = BigDecimal.ONE;

    @Column(name = "received_location_id")
    private UUID receivedLocationId;

    @Column(nullable = false, length = 32)
    private String outcome = "PENDING";

    @Column(columnDefinition = "text")
    private String notes;

    public UUID getReturnOrderId() { return returnOrderId; }
    public void setReturnOrderId(UUID returnOrderId) { this.returnOrderId = returnOrderId; }
    public UUID getStockItemId() { return stockItemId; }
    public void setStockItemId(UUID stockItemId) { this.stockItemId = stockItemId; }
    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }
    public UUID getReceivedLocationId() { return receivedLocationId; }
    public void setReceivedLocationId(UUID receivedLocationId) { this.receivedLocationId = receivedLocationId; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}
