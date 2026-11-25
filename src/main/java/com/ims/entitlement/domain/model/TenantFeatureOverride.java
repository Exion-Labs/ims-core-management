package com.ims.entitlement.domain.model;

import com.ims.common.GlobalEntity;
import com.ims.common.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@GlobalEntity
@Table(name = "tenant_feature_overrides")
public class TenantFeatureOverride {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UuidV7.generate();

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "feature_key", nullable = false, length = 80)
    private String featureKey;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "limit_value")
    private Long limitValue;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public boolean isExpired(Instant now) {
        return expiresAt != null && expiresAt.isBefore(now);
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public String getFeatureKey() { return featureKey; }
    public void setFeatureKey(String featureKey) { this.featureKey = featureKey; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Long getLimitValue() { return limitValue; }
    public void setLimitValue(Long limitValue) { this.limitValue = limitValue; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
