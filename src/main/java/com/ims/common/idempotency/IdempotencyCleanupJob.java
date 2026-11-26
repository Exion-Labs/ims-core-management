package com.ims.common.idempotency;

import com.ims.config.tenant.TenantContext;
import com.ims.identity.spi.TenantReadPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

/**
 * Deletes idempotency records older than the configured TTL, tenant by tenant
 * (so Hibernate's tenant filter and TenantPredicateInspector apply).
 */
@Component
public class IdempotencyCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanupJob.class);

    private final IdempotencyRecordRepository repository;
    private final IdempotencyProperties properties;
    private final TenantReadPort tenantReadPort;
    private final TransactionTemplate transactionTemplate;

    public IdempotencyCleanupJob(
            IdempotencyRecordRepository repository,
            IdempotencyProperties properties,
            TenantReadPort tenantReadPort,
            PlatformTransactionManager transactionManager
    ) {
        this.repository = repository;
        this.properties = properties;
        this.tenantReadPort = tenantReadPort;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Scheduled(cron = "${ims.idempotency.cleanup-cron:0 15 * * * *}")
    public void scheduledPurge() {
        purgeExpired();
    }

    /** @return total number of records deleted across all tenants */
    public int purgeExpired() {
        Instant cutoff = Instant.now().minus(properties.getTtl());
        int total = 0;
        for (TenantReadPort.TenantSummary tenant : tenantReadPort.findAll()) {
            Integer deleted = TenantContext.callUncheckedAs(tenant.id(), () ->
                    transactionTemplate.execute(status -> repository.deleteByCreatedAtBefore(cutoff)));
            total += deleted != null ? deleted : 0;
        }
        if (total > 0) {
            log.info("Purged {} expired idempotency records", total);
        }
        return total;
    }
}
