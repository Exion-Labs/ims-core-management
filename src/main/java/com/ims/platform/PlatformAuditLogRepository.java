package com.ims.platform;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface PlatformAuditLogRepository extends JpaRepository<PlatformAuditLog, UUID> {
    List<PlatformAuditLog> findTop100ByOrderByOccurredAtDesc();
}
