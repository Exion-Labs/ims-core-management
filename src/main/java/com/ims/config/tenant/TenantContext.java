package com.ims.config.tenant;

import org.slf4j.MDC;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * Holds the currently bound tenant ID for the active execution scope.
 * Never returns a default value when unbound: TenantContext.require() fails fast.
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT_TENANT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static UUID require() {
        UUID tenantId = CURRENT_TENANT.get();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant bound in TenantContext. Tenant-scoped data access is forbidden outside a bound tenant scope.");
        }
        return tenantId;
    }

    public static Optional<UUID> current() {
        return Optional.ofNullable(CURRENT_TENANT.get());
    }

    public static boolean isBound() {
        return CURRENT_TENANT.get() != null;
    }

    public static void runAs(UUID tenantId, Runnable action) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must not be null");
        }
        UUID previous = CURRENT_TENANT.get();
        String previousMdc = MDC.get("tenantId");
        try {
            CURRENT_TENANT.set(tenantId);
            MDC.put("tenantId", tenantId.toString());
            action.run();
        } finally {
            if (previous != null) {
                CURRENT_TENANT.set(previous);
            } else {
                CURRENT_TENANT.remove();
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
        UUID previous = CURRENT_TENANT.get();
        String previousMdc = MDC.get("tenantId");
        try {
            CURRENT_TENANT.set(tenantId);
            MDC.put("tenantId", tenantId.toString());
            return action.call();
        } finally {
            if (previous != null) {
                CURRENT_TENANT.set(previous);
            } else {
                CURRENT_TENANT.remove();
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
