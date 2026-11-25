package com.ims.manufacturing.infrastructure.persistence;

import com.ims.manufacturing.domain.model.*;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BuildOrderRepository extends JpaRepository<BuildOrder, UUID>, JpaSpecificationExecutor<BuildOrder> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from BuildOrder b where b.id = :id")
    Optional<BuildOrder> findByIdForUpdate(@Param("id") UUID id);

    List<BuildOrder> findByParentBuildIdOrderByCreatedAtAsc(UUID parentBuildId);
}
