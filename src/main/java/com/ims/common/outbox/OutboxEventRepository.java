package com.ims.common.outbox;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Repository for global OutboxEvent polling using JPA PESSIMISTIC_WRITE + SKIP LOCKED (-2).
 */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
            SELECT e FROM OutboxEvent e
            WHERE e.status = :status
              AND e.nextAttemptAt <= :now
            ORDER BY e.createdAt ASC
            """)
    List<OutboxEvent> findReadyForDispatch(
            @Param("status") String status,
            @Param("now") Instant now,
            Pageable pageable
    );

    List<OutboxEvent> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);
}
