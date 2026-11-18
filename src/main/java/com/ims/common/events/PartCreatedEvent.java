package com.ims.common.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when a Part is created so downstream domains (such as stock in Phase 2)
 * can initialize PartStockSummary rows without cyclic package dependencies.
 */
public record PartCreatedEvent(
        UUID tenantId,
        UUID partId,
        String ipn,
        String name,
        String revision,
        UUID variantOfId,
        Instant occurredAt
) implements DomainEvent {

    @Override
    public String eventType() {
        return "part.created";
    }

    @Override
    public String aggregateType() {
        return "Part";
    }

    @Override
    public UUID aggregateId() {
        return partId;
    }
}
