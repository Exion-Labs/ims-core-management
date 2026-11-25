package com.ims.manufacturing.domain.model;

import com.ims.common.VersionedTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "build_allocations")
public class BuildAllocation extends VersionedTenantEntity {

    @Column(name = "build_order_id", nullable = false)
    private UUID buildOrderId;

    @Column(name = "build_line_id", nullable = false)
    private UUID buildLineId;

    @Column(name = "stock_item_id", nullable = false)
    private UUID stockItemId;

    @Column(name = "install_into_id")
    private UUID installIntoId;

    @Column(name = "quantity", nullable = false, precision = 19, scale = 4)
    private BigDecimal quantity;

    @Column(name = "consumed", nullable = false)
    private boolean consumed = false;

    public UUID getBuildOrderId() {
        return buildOrderId;
    }

    public void setBuildOrderId(UUID buildOrderId) {
        this.buildOrderId = buildOrderId;
    }

    public UUID getBuildLineId() {
        return buildLineId;
    }

    public void setBuildLineId(UUID buildLineId) {
        this.buildLineId = buildLineId;
    }

    public UUID getStockItemId() {
        return stockItemId;
    }

    public void setStockItemId(UUID stockItemId) {
        this.stockItemId = stockItemId;
    }

    public UUID getInstallIntoId() {
        return installIntoId;
    }

    public void setInstallIntoId(UUID installIntoId) {
        this.installIntoId = installIntoId;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }

    public boolean isConsumed() {
        return consumed;
    }

    public void setConsumed(boolean consumed) {
        this.consumed = consumed;
    }
}
