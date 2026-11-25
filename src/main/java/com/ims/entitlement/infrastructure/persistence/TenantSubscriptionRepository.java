package com.ims.entitlement.infrastructure.persistence;

import com.ims.entitlement.domain.model.TenantSubscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TenantSubscriptionRepository extends JpaRepository<TenantSubscription, UUID> {
    Optional<TenantSubscription> findByTenantId(UUID tenantId);
    List<TenantSubscription> findByTenantIdIn(Collection<UUID> tenantIds);
}
