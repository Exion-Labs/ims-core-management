package com.ims.common;

import com.ims.common.audit.AuditIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;

/**
 * Subclass of BaseTenantEntity adding optimistic locking (@Version).
 * Used by all domain entities except high-contention summary counters (such as PartStockSummary)
 * and insert-only ledgers (AuditEvent, StockItemTracking).
 */
@MappedSuperclass
public abstract class VersionedTenantEntity extends BaseTenantEntity {

    @Version
    @AuditIgnore
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
