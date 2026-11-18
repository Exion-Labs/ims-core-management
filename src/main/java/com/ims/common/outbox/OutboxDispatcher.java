package com.ims.common.outbox;

import com.ims.common.audit.AuditContext;
import com.ims.config.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Polls pending outbox events with FOR UPDATE SKIP LOCKED and dispatches them
 * under TenantContext.runAs(event.getTenantId()) with exponential backoff and dead-lettering.
 */
@Component
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);
    private static final int BATCH_SIZE = 50;
    private static final int MAX_ATTEMPTS = 5;

    private final OutboxEventRepository outboxEventRepository;
    private final List<OutboxEventHandler> handlers;
    private final TransactionTemplate transactionTemplate;

    public OutboxDispatcher(
            OutboxEventRepository outboxEventRepository,
            List<OutboxEventHandler> handlers,
            TransactionTemplate transactionTemplate
    ) {
        this.outboxEventRepository = outboxEventRepository;
        this.handlers = handlers;
        this.transactionTemplate = transactionTemplate;
    }

    @Scheduled(fixedDelayString = "${ims.outbox.poll-interval-ms:2000}")
    public void pollAndDispatch() {
        dispatchBatch();
    }

    public int dispatchBatch() {
        Integer processed = transactionTemplate.execute(status -> {
            List<OutboxEvent> batch = outboxEventRepository.findReadyForDispatch(
                    OutboxEvent.Status.PENDING.name(),
                    Instant.now(),
                    PageRequest.of(0, BATCH_SIZE)
            );
            int count = 0;
            for (OutboxEvent event : batch) {
                processSingleEvent(event);
                count++;
            }
            return count;
        });
        return processed != null ? processed : 0;
    }

    private void processSingleEvent(OutboxEvent event) {
        event.setAttempts(event.getAttempts() + 1);
        try {
            TenantContext.runAs(event.getTenantId(), () ->
                    AuditContext.runWith(AuditContext.Snapshot.systemJob("OutboxDispatcher"), () -> {
                        for (OutboxEventHandler handler : handlers) {
                            if (handler.supports(event.getEventType())) {
                                try {
                                    handler.handle(event);
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            }
                        }
                    })
            );
            event.setStatus(OutboxEvent.Status.PUBLISHED.name());
            event.setPublishedAt(Instant.now());
            event.setLastError(null);
        } catch (Exception ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            log.warn("Outbox event {} (type={}, tenant={}) failed attempt {}/{}: {}",
                    event.getId(), event.getEventType(), event.getTenantId(),
                    event.getAttempts(), MAX_ATTEMPTS, cause.getMessage());
            event.setLastError(cause.getMessage());
            if (event.getAttempts() >= MAX_ATTEMPTS) {
                event.setStatus(OutboxEvent.Status.DEAD_LETTER.name());
            } else {
                event.setStatus(OutboxEvent.Status.PENDING.name());
                long backoffSeconds = (long) Math.pow(2, event.getAttempts());
                event.setNextAttemptAt(Instant.now().plus(Duration.ofSeconds(backoffSeconds)));
            }
        }
        outboxEventRepository.save(event);
    }
}
