package com.ims.order.fulfillment;

import com.ims.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "sales_order_line_items")
public class SalesOrderLineItem extends BaseTenantEntity {

    @Column(name = "sales_order_id", nullable = false)
    private UUID salesOrderId;

    @Column(name = "part_id", nullable = false)
    private UUID partId;

    @Column(nullable = false, precision = 19, scale = 6)
    private BigDecimal quantity;

    @Column(nullable = false, precision = 19, scale = 6)
    private BigDecimal shipped = BigDecimal.ZERO;

    @Column(name = "sale_price", precision = 19, scale = 6)
    private BigDecimal salePrice;

    @Column(name = "sale_price_currency", length = 3)
    private String salePriceCurrency;

    @Column(length = 128)
    private String reference;

    @Column(columnDefinition = "text")
    private String notes;

    public UUID getSalesOrderId() { return salesOrderId; }
    public void setSalesOrderId(UUID salesOrderId) { this.salesOrderId = salesOrderId; }
    public UUID getPartId() { return partId; }
    public void setPartId(UUID partId) { this.partId = partId; }
    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }
    public BigDecimal getShipped() { return shipped; }
    public void setShipped(BigDecimal shipped) { this.shipped = shipped; }
    public BigDecimal getSalePrice() { return salePrice; }
    public void setSalePrice(BigDecimal salePrice) { this.salePrice = salePrice; }
    public String getSalePriceCurrency() { return salePriceCurrency; }
    public void setSalePriceCurrency(String salePriceCurrency) { this.salePriceCurrency = salePriceCurrency; }
    public String getReference() { return reference; }
    public void setReference(String reference) { this.reference = reference; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}
