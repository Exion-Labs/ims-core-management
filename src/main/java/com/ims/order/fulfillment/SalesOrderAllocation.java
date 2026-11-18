package com.ims.order.fulfillment;

import com.ims.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "sales_order_allocations")
public class SalesOrderAllocation extends BaseTenantEntity {

    @Column(name = "line_item_id", nullable = false)
    private UUID lineItemId;

    @Column(name = "shipment_id")
    private UUID shipmentId;

    @Column(name = "stock_item_id", nullable = false)
    private UUID stockItemId;

    @Column(nullable = false, precision = 19, scale = 6)
    private BigDecimal quantity;

    @Column(nullable = false)
    private boolean shipped = false;

    public UUID getLineItemId() { return lineItemId; }
    public void setLineItemId(UUID lineItemId) { this.lineItemId = lineItemId; }
    public UUID getShipmentId() { return shipmentId; }
    public void setShipmentId(UUID shipmentId) { this.shipmentId = shipmentId; }
    public UUID getStockItemId() { return stockItemId; }
    public void setStockItemId(UUID stockItemId) { this.stockItemId = stockItemId; }
    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }
    public boolean isShipped() { return shipped; }
    public void setShipped(boolean shipped) { this.shipped = shipped; }
}
