package com.ims.entitlement.infrastructure.persistence;

import com.ims.entitlement.domain.model.Plan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PlanRepository extends JpaRepository<Plan, UUID> {
    Optional<Plan> findByCode(String code);
}
