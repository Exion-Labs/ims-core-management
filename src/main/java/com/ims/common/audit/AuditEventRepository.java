package com.ims.common.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Insert-only and read-only repository for AuditEvent.
 * Intentionally does NOT extend JpaRepository/CrudRepository so delete* methods do not exist.
 */
public interface AuditEventRepository extends Repository<AuditEvent, UUID>, JpaSpecificationExecutor<AuditEvent> {

    AuditEvent save(AuditEvent event);

    Optional<AuditEvent> findById(UUID id);

    Page<AuditEvent> findAll(Specification<AuditEvent> spec, Pageable pageable);
}
