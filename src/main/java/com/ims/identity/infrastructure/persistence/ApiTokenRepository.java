package com.ims.identity.infrastructure.persistence;

import com.ims.identity.domain.model.*;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiTokenRepository extends JpaRepository<ApiToken, UUID> {
    Optional<ApiToken> findByTokenHash(String tokenHash);
    List<ApiToken> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);
    Optional<ApiToken> findByTenantIdAndId(UUID tenantId, UUID id);
}
