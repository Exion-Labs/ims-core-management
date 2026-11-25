package com.ims.manufacturing.infrastructure.persistence;

import com.ims.manufacturing.domain.model.*;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BuildLineRepository extends JpaRepository<BuildLine, UUID> {

    List<BuildLine> findByBuildOrderIdOrderByCreatedAtAsc(UUID buildOrderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from BuildLine l where l.id = :id and l.buildOrderId = :buildId")
    Optional<BuildLine> findByIdAndBuildOrderIdForUpdate(@Param("id") UUID id, @Param("buildId") UUID buildId);
}
