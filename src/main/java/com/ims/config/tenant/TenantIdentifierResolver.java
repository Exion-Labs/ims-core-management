package com.ims.config.tenant;

import org.hibernate.cfg.AvailableSettings;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Resolves the current tenant identifier for Hibernate @TenantId discriminator filtering.
 * Note: During Spring startup metadata bootstrap, Hibernate may probe resolveCurrentTenantIdentifier()
 * only if no bootstrap sentinel is needed; however, during runtime session open for any @TenantId entity,
 * we strictly require a bound tenant via TenantContext.require(), except when explicitly running a global-only
 * transaction where no @TenantId entity is touched.
 *
 * IMPORTANT: Hibernate calls resolveCurrentTenantIdentifier() whenever ANY Session is opened!
 *Wait — if Hibernate opens a Session for an unauthenticated login request (to query UserAccount, a @GlobalEntity),
 * Hibernate still invokes CurrentTenantIdentifierResolver.resolveCurrentTenantIdentifier() at Session creation!
 */
@Component
public class TenantIdentifierResolver implements CurrentTenantIdentifierResolver<UUID>, HibernatePropertiesCustomizer {

    /**
     * Sentinel UUID used ONLY when a Session is opened outside a tenant scope (e.g., login or outbox polling on @GlobalEntity).
     * Any attempt to query or mutate a BaseTenantEntity when no tenant is bound in TenantContext is rejected by
     * TenantSessionGuardInterceptor / TenantPredicateInspector AND matches zero rows with the sentinel UUID.
     */
    public static final UUID UNBOUND_TENANT_SENTINEL = UUID.fromString("00000000-0000-0000-0000-000000000000");

    @Override
    public UUID resolveCurrentTenantIdentifier() {
        return TenantContext.current().orElse(UNBOUND_TENANT_SENTINEL);
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return true;
    }

    @Override
    public void customize(Map<String, Object> hibernateProperties) {
        hibernateProperties.put(AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER, this);
    }
}
