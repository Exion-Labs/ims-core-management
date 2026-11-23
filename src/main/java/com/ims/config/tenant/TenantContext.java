package com.ims.config.tenant;

import org.slf4j.MDC;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * Holds the currently bound tenant ID using a scoped ThreadLocal.
 * The previous tenant binding is always restored (or cleared) when the request/task scope exits,
 * preventing tenant leakage across pooled or virtual threads.
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> TENANT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static UUID require() {
        UUID tenantId = TENANT.get();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant bound in TenantContext. Tenant-scoped data access is forbidden outside a bound tenant scope.");
        }
        return tenantId;
    }

    public static Optional<UUID> current() {
        return Optional.ofNullable(TENANT.get());
    }

    public static boolean isBound() {
        return TENANT.get() != null;
    }

    public static void runAs(UUID tenantId, Runnable action) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must not be null");
        }
        UUID previousTenant = TENANT.get();
        String previousMdc = MDC.get("tenantId");
        try {
            TENANT.set(tenantId);
            MDC.put("tenantId", tenantId.toString());
            action.run();
        } finally {
            if (previousTenant != null) {
                TENANT.set(previousTenant);
            } else {
                TENANT.remove();
            }
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
        UUID previousTenant = TENANT.get();
        String previousMdc = MDC.get("tenantId");
        try {
            TENANT.set(tenantId);
            MDC.put("tenantId", tenantId.toString());
            return action.call();
        } finally {
            if (previousTenant != null) {
                TENANT.set(previousTenant);
            } else {
                TENANT.remove();
            }
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
