package com.ims.common.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Marker interface for all cross-domain events.
 * All cross-domain event records live exclusively in com.ims.common.events (enforced by ArchUnit).
 */
public interface DomainEvent {
    UUID tenantId();
    String eventType();
    String aggregateType();
    UUID aggregateId();
    Instant occurredAt();
}
