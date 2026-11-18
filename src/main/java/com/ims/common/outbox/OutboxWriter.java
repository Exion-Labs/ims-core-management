package com.ims.common.outbox;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ims.common.events.DomainEvent;
import com.ims.config.tenant.TenantContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Writes an OutboxEvent row in the same database transaction as the business mutation,
 * and publishes the in-process Spring event.
 */
@Component
public class OutboxWriter {

    private final OutboxEventRepository outboxEventRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public OutboxWriter(
            OutboxEventRepository outboxEventRepository,
            ApplicationEventPublisher eventPublisher,
            ObjectMapper objectMapper
    ) {
        this.outboxEventRepository = outboxEventRepository;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent enqueue(DomainEvent domainEvent) {
        Map<String, Object> payload = objectMapper.convertValue(domainEvent, new TypeReference<>() {});
        return enqueue(
                domainEvent.tenantId(),
                domainEvent.eventType(),
                domainEvent.aggregateType(),
                domainEvent.aggregateId(),
                payload,
                domainEvent
        );
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent enqueue(String eventType, String aggregateType, UUID aggregateId, Map<String, Object> payload) {
        UUID tenantId = TenantContext.require();
        return enqueue(tenantId, eventType, aggregateType, aggregateId, payload, null);
    }

    private OutboxEvent enqueue(
            UUID tenantId,
            String eventType,
            String aggregateType,
            UUID aggregateId,
            Map<String, Object> payload,
            DomainEvent inProcessEvent
    ) {
        OutboxEvent outbox = new OutboxEvent();
        outbox.setTenantId(tenantId);
        outbox.setEventType(eventType);
        outbox.setAggregateType(aggregateType);
        outbox.setAggregateId(aggregateId);
        outbox.setPayload(payload);
        outbox.setStatus(OutboxEvent.Status.PENDING.name());
        outbox.setNextAttemptAt(Instant.now());
        OutboxEvent saved = outboxEventRepository.save(outbox);

        if (inProcessEvent != null) {
            eventPublisher.publishEvent(inProcessEvent);
        }
        return saved;
    }
}
