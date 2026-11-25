package com.ims.purchasing.domain.model;

import com.ims.common.VersionedTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "purchase_order_line_items")
public class PurchaseOrderLineItem extends VersionedTenantEntity {

    @Column(name = "purchase_order_id", nullable = false)
    private UUID purchaseOrderId;

    @Column(name = "supplier_part_id", nullable = false)
    private UUID supplierPartId;

    @Column(name = "part_id", nullable = false)
    private UUID partId;

    @Column(name = "quantity", nullable = false, precision = 19, scale = 4)
    private BigDecimal quantity;

    @Column(name = "received", nullable = false, precision = 19, scale = 4)
    private BigDecimal received = BigDecimal.ZERO;

    @Column(name = "pack_quantity", nullable = false, precision = 19, scale = 4)
    private BigDecimal packQuantity = BigDecimal.ONE;

    @Column(name = "unit_price", nullable = false, precision = 19, scale = 6)
    private BigDecimal unitPrice = BigDecimal.ZERO;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "USD";

    @Column(name = "destination_location_id")
    private UUID destinationLocationId;

    @Column(name = "target_date")
    private LocalDate targetDate;

    @Column(name = "reference", length = 120)
    private String reference;

    @Column(name = "notes", length = 500)
    private String notes;

    public BigDecimal remainingPacks() {
        return quantity.subtract(received).max(BigDecimal.ZERO);
    }

    public UUID getPurchaseOrderId() {
        return purchaseOrderId;
    }

    public void setPurchaseOrderId(UUID purchaseOrderId) {
        this.purchaseOrderId = purchaseOrderId;
    }

    public UUID getSupplierPartId() {
        return supplierPartId;
    }

    public void setSupplierPartId(UUID supplierPartId) {
        this.supplierPartId = supplierPartId;
    }

    public UUID getPartId() {
        return partId;
    }

    public void setPartId(UUID partId) {
        this.partId = partId;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getReceived() {
        return received;
    }

    public void setReceived(BigDecimal received) {
        this.received = received;
    }

    public BigDecimal getPackQuantity() {
        return packQuantity;
    }

    public void setPackQuantity(BigDecimal packQuantity) {
        this.packQuantity = packQuantity;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public void setUnitPrice(BigDecimal unitPrice) {
        this.unitPrice = unitPrice;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public UUID getDestinationLocationId() {
        return destinationLocationId;
    }

    public void setDestinationLocationId(UUID destinationLocationId) {
        this.destinationLocationId = destinationLocationId;
    }

    public LocalDate getTargetDate() {
        return targetDate;
    }

    public void setTargetDate(LocalDate targetDate) {
        this.targetDate = targetDate;
    }

    public String getReference() {
        return reference;
    }

    public void setReference(String reference) {
        this.reference = reference;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}
