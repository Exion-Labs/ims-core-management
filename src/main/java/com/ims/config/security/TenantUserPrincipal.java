package com.ims.config.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Authenticated principal holding userId, tenantId, email, fullName, permissions, and auth method (SESSION_JWT vs API_TOKEN).
 */
public record TenantUserPrincipal(
        UUID userId,
        UUID tenantId,
        String email,
        String fullName,
        Set<String> permissions,
        boolean apiTokenAuth,
        UUID apiTokenId
) {
    public Collection<? extends GrantedAuthority> authorities() {
        return permissions.stream()
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toSet());
    }
}
