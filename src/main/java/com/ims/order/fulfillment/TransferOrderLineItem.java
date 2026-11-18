package com.ims.order.fulfillment;

import com.ims.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "transfer_order_line_items")
public class TransferOrderLineItem extends BaseTenantEntity {

    @Column(name = "transfer_order_id", nullable = false)
    private UUID transferOrderId;

    @Column(name = "stock_item_id", nullable = false)
    private UUID stockItemId;

    @Column(nullable = false, precision = 19, scale = 6)
    private BigDecimal quantity;

    @Column(nullable = false)
    private boolean received = false;

    @Column(columnDefinition = "text")
    private String notes;

    public UUID getTransferOrderId() { return transferOrderId; }
    public void setTransferOrderId(UUID transferOrderId) { this.transferOrderId = transferOrderId; }
    public UUID getStockItemId() { return stockItemId; }
    public void setStockItemId(UUID stockItemId) { this.stockItemId = stockItemId; }
    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }
    public boolean isReceived() { return received; }
    public void setReceived(boolean received) { this.received = received; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}
