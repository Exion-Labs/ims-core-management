package com.ims.common.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Published whenever a tenant's plan or feature overrides change, evicting the EntitlementService cache.
 */
public record EntitlementsChangedEvent(
        UUID tenantId,
        String reason,
        Instant occurredAt
) implements DomainEvent {

    @Override
    public String eventType() {
        return "entitlements.changed";
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
