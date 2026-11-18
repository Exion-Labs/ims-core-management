package com.ims.common.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when a new tenant is provisioned.
 */
public record TenantRegisteredEvent(
        UUID tenantId,
        String tenantSlug,
        String planCode,
        Instant occurredAt
) implements DomainEvent {

    @Override
    public String eventType() {
        return "tenant.registered";
    }

    @Override
    public String aggregateType() {
        return "Tenant";
    }

    @Override
    public UUID aggregateId() {
        return tenantId;
    }
}
