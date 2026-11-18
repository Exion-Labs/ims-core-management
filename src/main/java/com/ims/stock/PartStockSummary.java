package com.ims.stock;

import com.ims.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Denormalized stock counters per Part, owned by the stock domain.
 * Extends BaseTenantEntity (NOT VersionedTenantEntity) so high-concurrency batched HQL delta updates
 * never trigger OptimisticLockException.
 */
@Entity
@Table(name = "part_stock_summaries")
public class PartStockSummary extends BaseTenantEntity {

    @Column(name = "part_id", nullable = false, updatable = false)
    private UUID partId;

    @Column(name = "in_stock", nullable = false, precision = 19, scale = 4)
    private BigDecimal inStock = BigDecimal.ZERO;

    @Column(name = "allocated_to_builds", nullable = false, precision = 19, scale = 4)
    private BigDecimal allocatedToBuilds = BigDecimal.ZERO;

    @Column(name = "allocated_to_sales", nullable = false, precision = 19, scale = 4)
    private BigDecimal allocatedToSales = BigDecimal.ZERO;

    @Column(name = "on_order", nullable = false, precision = 19, scale = 4)
    private BigDecimal onOrder = BigDecimal.ZERO;

    @Column(name = "in_production", nullable = false, precision = 19, scale = 4)
    private BigDecimal inProduction = BigDecimal.ZERO;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "available_stock", insertable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal availableStock = BigDecimal.ZERO;

    public UUID getPartId() {
        return partId;
    }

    public void setPartId(UUID partId) {
        this.partId = partId;
    }

    public BigDecimal getInStock() {
        return inStock;
    }

    public void setInStock(BigDecimal inStock) {
        this.inStock = inStock;
    }

    public BigDecimal getAllocatedToBuilds() {
        return allocatedToBuilds;
    }

    public void setAllocatedToBuilds(BigDecimal allocatedToBuilds) {
        this.allocatedToBuilds = allocatedToBuilds;
    }

    public BigDecimal getAllocatedToSales() {
        return allocatedToSales;
    }

    public void setAllocatedToSales(BigDecimal allocatedToSales) {
        this.allocatedToSales = allocatedToSales;
    }

    public BigDecimal getOnOrder() {
        return onOrder;
    }

    public void setOnOrder(BigDecimal onOrder) {
        this.onOrder = onOrder;
    }

    public BigDecimal getInProduction() {
        return inProduction;
    }

    public void setInProduction(BigDecimal inProduction) {
        this.inProduction = inProduction;
    }

    public BigDecimal getAvailableStock() {
        return availableStock != null ? availableStock : BigDecimal.ZERO;
    }
}
