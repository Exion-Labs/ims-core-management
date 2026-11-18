package com.ims.platform;

import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TenantUsageSnapshotRepository extends JpaRepository<TenantUsageSnapshot, UUID> {
    List<TenantUsageSnapshot> findByTenantIdOrderBySnapshotDateDesc(UUID tenantId);
    Optional<TenantUsageSnapshot> findByTenantIdAndSnapshotDate(UUID tenantId, LocalDate snapshotDate);
}
