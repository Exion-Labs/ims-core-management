package com.ims.common.audit;

import com.ims.config.tenant.TenantContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Service for recording explicit audit events (login, failed login, logout, token created/revoked,
 * exports, imports, status transitions) and querying the tenant's audit trail.
 */
@Service
public class AuditService {

    private final AuditEventRepository auditEventRepository;

    public AuditService(AuditEventRepository auditEventRepository) {
        this.auditEventRepository = auditEventRepository;
    }

    @Transactional
    public AuditEvent record(String entityType, UUID entityId, String action, Map<String, Object> changes, String reason) {
        AuditContext.Snapshot ctx = AuditContext.capture();
        AuditEvent event = new AuditEvent();
        event.setOccurredAt(Instant.now());
        event.setActorType(ctx.actorType().name());
        event.setActorId(ctx.actorId());
        event.setActorName(ctx.actorName());
        event.setSupportSessionId(ctx.supportSessionId());
        event.setRequestId(ctx.requestId());
        event.setIp(ctx.ip());
        event.setEntityType(entityType);
        event.setEntityId(entityId);
        event.setAction(action);
        event.setChanges(changes != null ? changes : Map.of());
        event.setReason(reason);
        return auditEventRepository.save(event);
    }

    /**
     * Records an explicit audit event in an isolated transaction (REQUIRES_NEW) under the specified tenant,
     * useful for LOGIN_FAILED or unauthenticated flows that resolve a tenant dynamically.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordInNewTransactionForTenant(UUID tenantId, String entityType, UUID entityId, String action, Map<String, Object> changes, String reason) {
        TenantContext.runAs(tenantId, () -> record(entityType, entityId, action, changes, reason));
    }

    @Transactional(readOnly = true)
    public Page<AuditEvent> search(String entityType, UUID entityId, UUID actorId, String action, Instant from, Instant to, Pageable pageable) {
        return auditEventRepository.search(entityType, entityId, actorId, action, from, to, pageable);
    }
}
