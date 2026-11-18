package com.ims.platform;

import com.ims.common.GlobalEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@GlobalEntity
@Table(name = "support_sessions")
public class SupportSession {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "platform_user_id", nullable = false)
    private UUID platformUserId;

    @Column(name = "target_tenant_id", nullable = false)
    private UUID targetTenantId;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getPlatformUserId() { return platformUserId; }
    public void setPlatformUserId(UUID platformUserId) { this.platformUserId = platformUserId; }
    public UUID getTargetTenantId() { return targetTenantId; }
    public void setTargetTenantId(UUID targetTenantId) { this.targetTenantId = targetTenantId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getEndedAt() { return endedAt; }
    public void setEndedAt(Instant endedAt) { this.endedAt = endedAt; }
}
