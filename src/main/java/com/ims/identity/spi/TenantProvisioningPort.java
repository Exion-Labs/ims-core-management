package com.ims.identity.spi;

import java.util.Set;
import java.util.UUID;

/**
 * Public port for provisioning new tenants and issuing initial admin tokens.
 */
public interface TenantProvisioningPort {

    record ProvisionTenantCommand(
            String tenantName,
            String tenantSlug,
            String adminEmail,
            String adminFullName,
            String adminPassword,
            String baseCurrency,
            String timezone,
            String planCode
    ) {}

    record AuthTokensResponse(
            String accessToken,
            String refreshToken,
            long expiresInSeconds,
            UUID userId,
            UUID tenantId,
            String tenantName,
            String tenantSlug,
            String email,
            String fullName,
            Set<String> permissions
    ) {}

    AuthTokensResponse registerTenant(ProvisionTenantCommand command, boolean bypassSignupGate);
}
