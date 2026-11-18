package com.ims.build;

import com.ims.common.VersionedTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "build_lines")
public class BuildLine extends VersionedTenantEntity {

    @Column(name = "build_order_id", nullable = false)
    private UUID buildOrderId;

    @Column(name = "bom_item_id")
    private UUID bomItemId;

    @Column(name = "sub_part_id", nullable = false)
    private UUID subPartId;

    @Column(name = "quantity", nullable = false, precision = 19, scale = 4)
    private BigDecimal quantity;

    @Column(name = "consumed", nullable = false, precision = 19, scale = 4)
    private BigDecimal consumed = BigDecimal.ZERO;

    @Column(name = "reference", length = 250)
    private String reference;

    public UUID getBuildOrderId() {
        return buildOrderId;
    }

    public void setBuildOrderId(UUID buildOrderId) {
        this.buildOrderId = buildOrderId;
    }

    public UUID getBomItemId() {
        return bomItemId;
    }

    public void setBomItemId(UUID bomItemId) {
        this.bomItemId = bomItemId;
    }

    public UUID getSubPartId() {
        return subPartId;
    }

    public void setSubPartId(UUID subPartId) {
        this.subPartId = subPartId;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getConsumed() {
        return consumed;
    }

    public void setConsumed(BigDecimal consumed) {
        this.consumed = consumed;
    }

    public String getReference() {
        return reference;
    }

    public void setReference(String reference) {
        this.reference = reference;
    }
}
