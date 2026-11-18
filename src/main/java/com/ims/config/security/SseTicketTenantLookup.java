package com.ims.config.security;

import java.util.Optional;
import java.util.UUID;

public interface SseTicketTenantLookup {
    Optional<UUID> peekTenantIdForTicket(String ticket);
}
