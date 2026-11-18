package com.ims.common.audit;

import com.ims.config.tenant.TenantContext;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service for recording explicit audit events (login, failed login, logout, token created/revoked,
 * exports, imports, status transitions) and querying the tenant's audit trail.
 */
@Service
public class AuditService {

    private final AuditEventRepository auditEventRepository;
    private final TransactionTemplate requiresNewTx;

    public AuditService(AuditEventRepository auditEventRepository, PlatformTransactionManager transactionManager) {
        this.auditEventRepository = auditEventRepository;
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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
     * Records an explicit audit event in an isolated transaction (REQUIRES_NEW) opened INSIDE the bound tenant scope,
     * useful for LOGIN_FAILED or unauthenticated flows that resolve a tenant dynamically.
     */
    public void recordInNewTransactionForTenant(UUID tenantId, String entityType, UUID entityId, String action, Map<String, Object> changes, String reason) {
        TenantContext.runAs(tenantId, () ->
                requiresNewTx.executeWithoutResult(status -> record(entityType, entityId, action, changes, reason))
        );
    }

    @Transactional(readOnly = true)
    public Page<AuditEvent> search(String entityType, UUID entityId, UUID actorId, String action, Instant from, Instant to, Pageable pageable) {
        Specification<AuditEvent> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (entityType != null && !entityType.isBlank()) {
                predicates.add(cb.equal(root.get("entityType"), entityType));
            }
            if (entityId != null) {
                predicates.add(cb.equal(root.get("entityId"), entityId));
            }
            if (actorId != null) {
                predicates.add(cb.equal(root.get("actorId"), actorId));
            }
            if (action != null && !action.isBlank()) {
                predicates.add(cb.equal(root.get("action"), action));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("occurredAt"), to));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        Pageable sortedPageable = PageRequest.of(
                pageable.getPageNumber(),
                pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "occurredAt")
        );
        return auditEventRepository.findAll(spec, sortedPageable);
    }
}
