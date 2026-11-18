package com.ims.platform;

import com.ims.common.GlobalEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@GlobalEntity
@Table(name = "tenant_usage_snapshots")
public class TenantUsageSnapshot {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "snapshot_date", nullable = false)
    private LocalDate snapshotDate;

    @Column(name = "parts_count", nullable = false)
    private long partsCount;

    @Column(name = "locations_count", nullable = false)
    private long locationsCount;

    @Column(name = "users_count", nullable = false)
    private long usersCount;

    @Column(name = "stock_items_count", nullable = false)
    private long stockItemsCount;

    @Column(name = "orders_count", nullable = false)
    private long ordersCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public LocalDate getSnapshotDate() { return snapshotDate; }
    public void setSnapshotDate(LocalDate snapshotDate) { this.snapshotDate = snapshotDate; }
    public long getPartsCount() { return partsCount; }
    public void setPartsCount(long partsCount) { this.partsCount = partsCount; }
    public long getLocationsCount() { return locationsCount; }
    public void setLocationsCount(long locationsCount) { this.locationsCount = locationsCount; }
    public long getUsersCount() { return usersCount; }
    public void setUsersCount(long usersCount) { this.usersCount = usersCount; }
    public long getStockItemsCount() { return stockItemsCount; }
    public void setStockItemsCount(long stockItemsCount) { this.stockItemsCount = stockItemsCount; }
    public long getOrdersCount() { return ordersCount; }
    public void setOrdersCount(long ordersCount) { this.ordersCount = ordersCount; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
