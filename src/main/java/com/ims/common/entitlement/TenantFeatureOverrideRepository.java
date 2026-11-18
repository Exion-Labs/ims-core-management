package com.ims.common.entitlement;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TenantFeatureOverrideRepository extends JpaRepository<TenantFeatureOverride, UUID> {
    List<TenantFeatureOverride> findByTenantId(UUID tenantId);
    Optional<TenantFeatureOverride> findByTenantIdAndFeatureKey(UUID tenantId, String featureKey);
}
