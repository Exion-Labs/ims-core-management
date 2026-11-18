package com.ims.common.audit;

import com.ims.common.BaseTenantEntity;
import com.ims.config.tenant.TenantContext;
import org.hibernate.action.spi.BeforeTransactionCompletionProcess;
import org.hibernate.engine.spi.SessionImplementor;
import org.hibernate.event.spi.EventSource;
import org.hibernate.event.spi.PostDeleteEvent;
import org.hibernate.event.spi.PostDeleteEventListener;
import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostInsertEventListener;
import org.hibernate.event.spi.PostUpdateEvent;
import org.hibernate.event.spi.PostUpdateEventListener;
import org.hibernate.persister.entity.EntityPersister;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hibernate PostInsert / PostUpdate / PostDelete listener for entities annotated with @Audited.
 * Buffers AuditEvent rows per session and persists them in a BeforeTransactionCompletionProcess
 * so that audit entries commit atomically in the same transaction without mutating session state mid-flush.
 */
public class AuditEventListener implements PostInsertEventListener, PostUpdateEventListener, PostDeleteEventListener {

    private static final Map<Class<?>, Set<String>> IGNORED_PROPERTIES_CACHE = new ConcurrentHashMap<>();
    private final Map<SessionImplementor, List<AuditEvent>> sessionBuffers = Collections.synchronizedMap(new IdentityHashMap<>());

    @Override
    public void onPostInsert(PostInsertEvent event) {
        Object entity = event.getEntity();
        if (!isAudited(entity)) {
            return;
        }
        BaseTenantEntity tenantEntity = (BaseTenantEntity) entity;
        Set<String> ignored = getIgnoredProperties(entity.getClass());
        String[] propertyNames = event.getPersister().getPropertyNames();
        Object[] state = event.getState();

        Map<String, Object> changes = new LinkedHashMap<>();
        for (int i = 0; i < propertyNames.length; i++) {
            String prop = propertyNames[i];
            if (ignored.contains(prop)) {
                continue;
            }
            Object val = sanitizeValue(state[i]);
            if (val != null) {
                Map<String, Object> diff = new LinkedHashMap<>();
                diff.put("from", null);
                diff.put("to", val);
                changes.put(prop, diff);
            }
        }
        bufferAuditEvent(event.getSession(), tenantEntity, "CREATE", changes);
    }

    @Override
    public void onPostUpdate(PostUpdateEvent event) {
        Object entity = event.getEntity();
        if (!isAudited(entity)) {
            return;
        }
        BaseTenantEntity tenantEntity = (BaseTenantEntity) entity;
        Set<String> ignored = getIgnoredProperties(entity.getClass());
        String[] propertyNames = event.getPersister().getPropertyNames();
        Object[] oldState = event.getOldState();
        Object[] newState = event.getState();
        int[] dirtyProps = event.getDirtyProperties();

        if (dirtyProps == null || dirtyProps.length == 0) {
            return;
        }

        Map<String, Object> changes = new LinkedHashMap<>();
        for (int idx : dirtyProps) {
            String prop = propertyNames[idx];
            if (ignored.contains(prop)) {
                continue;
            }
            Object fromVal = oldState != null ? sanitizeValue(oldState[idx]) : null;
            Object toVal = sanitizeValue(newState[idx]);
            if (!Objects.equals(fromVal, toVal)) {
                Map<String, Object> diff = new LinkedHashMap<>();
                diff.put("from", fromVal);
                diff.put("to", toVal);
                changes.put(prop, diff);
            }
        }
        if (!changes.isEmpty()) {
            bufferAuditEvent(event.getSession(), tenantEntity, "UPDATE", changes);
        }
    }

    @Override
    public void onPostDelete(PostDeleteEvent event) {
        Object entity = event.getEntity();
        if (!isAudited(entity)) {
            return;
        }
        BaseTenantEntity tenantEntity = (BaseTenantEntity) entity;
        Set<String> ignored = getIgnoredProperties(entity.getClass());
        String[] propertyNames = event.getPersister().getPropertyNames();
        Object[] deletedState = event.getDeletedState();

        Map<String, Object> changes = new LinkedHashMap<>();
        if (deletedState != null) {
            for (int i = 0; i < propertyNames.length; i++) {
                String prop = propertyNames[i];
                if (ignored.contains(prop)) {
                    continue;
                }
                Object val = sanitizeValue(deletedState[i]);
                if (val != null) {
                    Map<String, Object> diff = new LinkedHashMap<>();
                    diff.put("from", val);
                    diff.put("to", null);
                    changes.put(prop, diff);
                }
            }
        }
        bufferAuditEvent(event.getSession(), tenantEntity, "DELETE", changes);
    }

    private void bufferAuditEvent(EventSource session, BaseTenantEntity entity, String action, Map<String, Object> changes) {
        if (!TenantContext.isBound()) {
            return;
        }
        AuditContext.Snapshot ctx = AuditContext.capture();
        Audited audited = entity.getClass().getAnnotation(Audited.class);
        String entityType = (audited != null && !audited.entityType().isBlank())
                ? audited.entityType()
                : entity.getClass().getSimpleName();

        AuditEvent audit = new AuditEvent();
        audit.setOccurredAt(Instant.now());
        audit.setActorType(ctx.actorType().name());
        audit.setActorId(ctx.actorId());
        audit.setActorName(ctx.actorName());
        audit.setSupportSessionId(ctx.supportSessionId());
        audit.setRequestId(ctx.requestId());
        audit.setIp(ctx.ip());
        audit.setEntityType(entityType);
        audit.setEntityId(entity.getId());
        audit.setAction(action);
        audit.setChanges(changes);

        synchronized (sessionBuffers) {
            List<AuditEvent> buffer = sessionBuffers.get(session);
            if (buffer == null) {
                buffer = new ArrayList<>();
                sessionBuffers.put(session, buffer);
                session.getActionQueue().registerProcess((BeforeTransactionCompletionProcess) s -> flushBuffer(s));
            }
            buffer.add(audit);
        }
    }

    private void flushBuffer(SessionImplementor session) {
        List<AuditEvent> events;
        synchronized (sessionBuffers) {
            events = sessionBuffers.remove(session);
        }
        if (events == null || events.isEmpty()) {
            return;
        }
        for (AuditEvent event : events) {
            session.persist(event);
        }
        session.flush();
    }

    private boolean isAudited(Object entity) {
        return entity instanceof BaseTenantEntity
                && !(entity instanceof AuditEvent)
                && entity.getClass().isAnnotationPresent(Audited.class);
    }

    private Set<String> getIgnoredProperties(Class<?> clazz) {
        return IGNORED_PROPERTIES_CACHE.computeIfAbsent(clazz, c -> {
            Set<String> ignored = ConcurrentHashMap.newKeySet();
            ignored.add("tenantId");
            ignored.add("createdAt");
            ignored.add("updatedAt");
            ignored.add("createdBy");
            ignored.add("updatedBy");
            ignored.add("version");
            Class<?> curr = c;
            while (curr != null && curr != Object.class) {
                for (Field field : curr.getDeclaredFields()) {
                    if (field.isAnnotationPresent(AuditIgnore.class)) {
                        ignored.add(field.getName());
                    }
                }
                curr = curr.getSuperclass();
            }
            return ignored;
        });
    }

    private Object sanitizeValue(Object val) {
        if (val == null) {
            return null;
        }
        if (val instanceof Number || val instanceof Boolean || val instanceof String || val instanceof Map<?> || val instanceof List<?>) {
            return val;
        }
        return val.toString();
    }

    @Override
    public boolean requiresPostCommitHandling(EntityPersister persister) {
        return false;
    }
}
