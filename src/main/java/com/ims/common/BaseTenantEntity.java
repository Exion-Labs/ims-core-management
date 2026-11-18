package com.ims.common;

import com.ims.common.audit.AuditContext;
import com.ims.common.audit.AuditIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.TenantId;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Base mapped superclass for all tenant-scoped entities.
 * Hibernate's @TenantId automatically binds the current tenant on INSERT and appends
 * WHERE tenant_id = ? to every SELECT, UPDATE, DELETE and JOIN query.
 */
@MappedSuperclass
public abstract class BaseTenantEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UuidV7.generate();

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @AuditIgnore
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @AuditIgnore
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @AuditIgnore
    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @AuditIgnore
    @Column(name = "updated_by")
    private UUID updatedBy;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> metadata = new HashMap<>();

    @PrePersist
    protected void onPrePersist() {
        if (this.id == null) {
            this.id = UuidV7.generate();
        }
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        UUID actorId = AuditContext.currentActorId();
        if (actorId != null) {
            this.createdBy = actorId;
            this.updatedBy = actorId;
        }
        if (this.metadata == null) {
            this.metadata = new HashMap<>();
        }
    }

    @PreUpdate
    protected void onPreUpdate() {
        this.updatedAt = Instant.now();
        UUID actorId = AuditContext.currentActorId();
        if (actorId != null) {
            this.updatedBy = actorId;
        }
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(UUID createdBy) {
        this.createdBy = createdBy;
    }

    public UUID getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(UUID updatedBy) {
        this.updatedBy = updatedBy;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata != null ? metadata : new HashMap<>();
    }
}
