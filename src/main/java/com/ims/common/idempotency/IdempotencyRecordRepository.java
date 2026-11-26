package com.ims.common.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Tenant-scoped (Hibernate {@code @TenantId}) access to idempotency records.
 */
public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, UUID> {

    Optional<IdempotencyRecord> findByPrincipalIdAndIdemKey(UUID principalId, String idemKey);

    @Modifying
    @Query("DELETE FROM IdempotencyRecord r WHERE r.createdAt < :cutoff")
    int deleteByCreatedAtBefore(@Param("cutoff") Instant cutoff);
}
