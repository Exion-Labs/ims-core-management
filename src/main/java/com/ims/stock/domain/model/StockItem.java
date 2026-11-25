package com.ims.stock.domain.model;

import com.ims.common.VersionedTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Physical batch or serialized stock instance of a Part.
 * High-churn movements are recorded in StockItemTracking (not AuditEvent).
 */
@Entity
@Table(name = "stock_items")
public class StockItem extends VersionedTenantEntity {

    public enum Status {
        OK,
        ATTENTION,
        DAMAGED,
        DESTROYED,
        REJECTED,
        LOST,
        QUARANTINED,
        RETURNED,
        IN_TRANSIT
    }

    @Column(name = "part_id", nullable = false)
    private UUID partId;

    @Column(name = "location_id")
    private UUID locationId;

    @Column(name = "parent_item_id")
    private UUID parentItemId;

    @Column(name = "installed_in_id")
    private UUID installedInId;

    @Column(name = "customer_id")
    private UUID customerId;

    @Column(name = "quantity", nullable = false, precision = 19, scale = 4)
    private BigDecimal quantity = BigDecimal.ZERO;

    @Column(name = "serial_number", length = 100)
    private String serialNumber;

    @Column(name = "batch_code", length = 100)
    private String batchCode;

    @Column(name = "status", nullable = false, length = 32)
    private String status = Status.OK.name();

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Column(name = "purchase_price", precision = 19, scale = 4)
    private BigDecimal purchasePrice;

    @Column(name = "currency", length = 3)
    private String currency;

    @Column(name = "packaging", length = 120)
    private String packaging;

    @Column(name = "is_building", nullable = false)
    private boolean building = false;

    @Column(name = "custom_barcode", length = 128)
    private String customBarcode;

    public boolean countsTowardInStock() {
        if (building || customerId != null || installedInId != null) {
            return false;
        }
        return Status.OK.name().equals(status) || Status.ATTENTION.name().equals(status);
    }

    public UUID getPartId() {
        return partId;
    }

    public void setPartId(UUID partId) {
        this.partId = partId;
    }

    public UUID getLocationId() {
        return locationId;
    }

    public void setLocationId(UUID locationId) {
        this.locationId = locationId;
    }

    public UUID getParentItemId() {
        return parentItemId;
    }

    public void setParentItemId(UUID parentItemId) {
        this.parentItemId = parentItemId;
    }

    public UUID getInstalledInId() {
        return installedInId;
    }

    public void setInstalledInId(UUID installedInId) {
        this.installedInId = installedInId;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public void setCustomerId(UUID customerId) {
        this.customerId = customerId;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }

    public String getSerialNumber() {
        return serialNumber;
    }

    public void setSerialNumber(String serialNumber) {
        this.serialNumber = serialNumber;
    }

    public String getBatchCode() {
        return batchCode;
    }

    public void setBatchCode(String batchCode) {
        this.batchCode = batchCode;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDate getExpiryDate() {
        return expiryDate;
    }

    public void setExpiryDate(LocalDate expiryDate) {
        this.expiryDate = expiryDate;
    }

    public BigDecimal getPurchasePrice() {
        return purchasePrice;
    }

    public void setPurchasePrice(BigDecimal purchasePrice) {
        this.purchasePrice = purchasePrice;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getPackaging() {
        return packaging;
    }

    public void setPackaging(String packaging) {
        this.packaging = packaging;
    }

    public boolean isBuilding() {
        return building;
    }

    public void setBuilding(boolean building) {
        this.building = building;
    }

    public String getCustomBarcode() {
        return customBarcode;
    }

    public void setCustomBarcode(String customBarcode) {
        this.customBarcode = customBarcode;
    }
}
