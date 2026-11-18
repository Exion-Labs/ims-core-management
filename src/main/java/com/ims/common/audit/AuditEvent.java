package com.ims.common.audit;

import com.ims.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable, insert-only audit trail entry scoped to a tenant via BaseTenantEntity (@TenantId).
 */
@Entity
@Immutable
@Table(name = "audit_event")
public class AuditEvent extends BaseTenantEntity {

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt = Instant.now();

    @Column(name = "actor_type", nullable = false, updatable = false, length = 32)
    private String actorType;

    @Column(name = "actor_id", updatable = false)
    private UUID actorId;

    @Column(name = "actor_name", updatable = false, length = 200)
    private String actorName;

    @Column(name = "support_session_id", updatable = false)
    private UUID supportSessionId;

    @Column(name = "request_id", updatable = false, length = 100)
    private String requestId;

    @Column(name = "ip", updatable = false, length = 64)
    private String ip;

    @Column(name = "entity_type", nullable = false, updatable = false, length = 80)
    private String entityType;

    @Column(name = "entity_id", updatable = false)
    private UUID entityId;

    @Column(name = "action", nullable = false, updatable = false, length = 64)
    private String action;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "changes", nullable = false, updatable = false, columnDefinition = "jsonb")
    private Map<String, Object> changes = new HashMap<>();

    @Column(name = "reason", updatable = false, length = 500)
    private String reason;

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }

    public String getActorType() {
        return actorType;
    }

    public void setActorType(String actorType) {
        this.actorType = actorType;
    }

    public UUID getActorId() {
        return actorId;
    }

    public void setActorId(UUID actorId) {
        this.actorId = actorId;
    }

    public String getActorName() {
        return actorName;
    }

    public void setActorName(String actorName) {
        this.actorName = actorName;
    }

    public UUID getSupportSessionId() {
        return supportSessionId;
    }

    public void setSupportSessionId(UUID supportSessionId) {
        this.supportSessionId = supportSessionId;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public String getEntityType() {
        return entityType;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    public UUID getEntityId() {
        return entityId;
    }

    public void setEntityId(UUID entityId) {
        this.entityId = entityId;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public Map<String, Object> getChanges() {
        return changes;
    }

    public void setChanges(Map<String, Object> changes) {
        this.changes = changes != null ? changes : new HashMap<>();
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
