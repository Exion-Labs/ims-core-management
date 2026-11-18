package com.ims.common.outbox;

/**
 * Pluggable handler invoked by OutboxDispatcher inside TenantContext.runAs(event.getTenantId()).
 */
public interface OutboxEventHandler {
    boolean supports(String eventType);
    void handle(OutboxEvent event) throws Exception;
}
