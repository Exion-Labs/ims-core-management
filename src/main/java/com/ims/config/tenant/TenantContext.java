package com.ims.config.tenant;

import org.slf4j.MDC;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * Holds the currently bound tenant ID using Java 25 ScopedValue.
 * Scoped Values are automatically removed when the request/task scope exits,
 * preventing any possibility of tenant leakage across pooled or virtual threads.
 */
public final class TenantContext {

    public static final ScopedValue<UUID> TENANT = ScopedValue.newInstance();

    private TenantContext() {
    }

    public static UUID require() {
        if (!TENANT.isBound()) {
            throw new IllegalStateException("No tenant bound in TenantContext. Tenant-scoped data access is forbidden outside a bound tenant scope.");
        }
        return TENANT.get();
    }

    public static Optional<UUID> current() {
        return TENANT.isBound() ? Optional.of(TENANT.get()) : Optional.empty();
    }

    public static boolean isBound() {
        return TENANT.isBound();
    }

    public static void runAs(UUID tenantId, Runnable action) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must not be null");
        }
        String previousMdc = MDC.get("tenantId");
        try {
            MDC.put("tenantId", tenantId.toString());
            ScopedValue.where(TENANT, tenantId).run(action);
        } finally {
            if (previousMdc != null) {
                MDC.put("tenantId", previousMdc);
            } else {
                MDC.remove("tenantId");
            }
        }
    }

    public static <T> T callAs(UUID tenantId, Callable<T> action) throws Exception {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must not be null");
        }
        String previousMdc = MDC.get("tenantId");
        try {
            MDC.put("tenantId", tenantId.toString());
            return ScopedValue.where(TENANT, tenantId).call(action::call);
        } finally {
            if (previousMdc != null) {
                MDC.put("tenantId", previousMdc);
            } else {
                MDC.remove("tenantId");
            }
        }
    }

    public static <T> T callUncheckedAs(UUID tenantId, Callable<T> action) {
        try {
            return callAs(tenantId, action);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
