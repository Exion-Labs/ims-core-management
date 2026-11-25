package com.ims.identity.spi;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Public read/admin port for tenant metadata, base currency, and user lookups.
 */
public interface TenantReadPort {

    record TenantSummary(
            UUID id,
            String name,
            String slug,
            String status,
            String baseCurrency,
            String timezone,
            Instant createdAt
    ) {}

    record ActiveUserSummary(
            UUID id,
            UUID tenantId,
            String email,
            String fullName
    ) {}

    record ApiTokenPrincipal(
            UUID userId,
            UUID tenantId,
            String email,
            String fullName,
            Set<String> scopes,
            UUID apiTokenId
    ) {}

    String getCurrentTenantBaseCurrency();

    Optional<TenantSummary> findById(UUID tenantId);

    Optional<TenantSummary> findBySlug(String slug);

    boolean existsBySlug(String slug);

    List<TenantSummary> findAll();

    Map<UUID, Long> countUsersByTenantIds(Collection<UUID> tenantIds);

    long countUsersForTenant(UUID tenantId);

    TenantSummary updateStatus(UUID tenantId, String status);

    Optional<ActiveUserSummary> findFirstActiveUser(UUID tenantId);

    Optional<ApiTokenPrincipal> authenticateApiToken(String tokenHash);
}
