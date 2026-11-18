package com.ims.config.tenant;

import com.ims.common.audit.AuditContext;
import org.springframework.core.task.TaskDecorator;

import java.util.Optional;
import java.util.UUID;

/**
 * Propagates TenantContext and AuditContext across @Async and virtual-thread executors.
 */
public class TenantAwareTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        Optional<UUID> capturedTenant = TenantContext.current();
        AuditContext.Snapshot capturedAudit = AuditContext.capture();
        return () -> {
            Runnable withAudit = () -> AuditContext.runWith(capturedAudit, runnable);
            if (capturedTenant.isPresent()) {
                TenantContext.runAs(capturedTenant.get(), withAudit);
            } else {
                withAudit.run();
            }
        };
    }
}
