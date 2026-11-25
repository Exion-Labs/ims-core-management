package com.ims.stock.domain.model;

import com.ims.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Immutable audit ledger for stock item movements and operations.
 */
@Entity
@Table(name = "stock_item_tracking")
@Immutable
public class StockItemTracking extends BaseTenantEntity {

    @Column(name = "stock_item_id", nullable = false, updatable = false)
    private UUID stockItemId;

    @Column(name = "part_id", nullable = false, updatable = false)
    private UUID partId;

    @Column(name = "tracking_type", nullable = false, length = 40, updatable = false)
    private String trackingType;

    @Column(name = "quantity_delta", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal quantityDelta;

    @Column(name = "quantity_after", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal quantityAfter;

    @Column(name = "location_id", updatable = false)
    private UUID locationId;

    @Column(name = "notes", length = 500, updatable = false)
    private String notes;

    @Column(name = "user_id", updatable = false)
    private UUID userId;

    public UUID getStockItemId() {
        return stockItemId;
    }

    public void setStockItemId(UUID stockItemId) {
        this.stockItemId = stockItemId;
    }

    public UUID getPartId() {
        return partId;
    }

    public void setPartId(UUID partId) {
        this.partId = partId;
    }

    public String getTrackingType() {
        return trackingType;
    }

    public void setTrackingType(String trackingType) {
        this.trackingType = trackingType;
    }

    public BigDecimal getQuantityDelta() {
        return quantityDelta;
    }

    public void setQuantityDelta(BigDecimal quantityDelta) {
        this.quantityDelta = quantityDelta;
    }

    public BigDecimal getQuantityAfter() {
        return quantityAfter;
    }

    public void setQuantityAfter(BigDecimal quantityAfter) {
        this.quantityAfter = quantityAfter;
    }

    public UUID getLocationId() {
        return locationId;
    }

    public void setLocationId(UUID locationId) {
        this.locationId = locationId;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }
}
