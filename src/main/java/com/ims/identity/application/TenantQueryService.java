package com.ims.identity.application;

import com.ims.config.tenant.TenantContext;
import com.ims.identity.domain.model.ApiToken;
import com.ims.identity.domain.model.Tenant;
import com.ims.identity.domain.model.UserAccount;
import com.ims.identity.infrastructure.persistence.ApiTokenRepository;
import com.ims.identity.infrastructure.persistence.TenantRepository;
import com.ims.identity.infrastructure.persistence.UserAccountRepository;
import com.ims.identity.spi.TenantReadPort;
import com.ims.shared.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class TenantQueryService implements TenantReadPort {

    private final TenantRepository tenantRepository;
    private final UserAccountRepository userAccountRepository;
    private final ApiTokenRepository apiTokenRepository;

    public TenantQueryService(
            TenantRepository tenantRepository,
            UserAccountRepository userAccountRepository,
            ApiTokenRepository apiTokenRepository
    ) {
        this.tenantRepository = tenantRepository;
        this.userAccountRepository = userAccountRepository;
        this.apiTokenRepository = apiTokenRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public String getCurrentTenantBaseCurrency() {
        UUID tenantId = TenantContext.require();
        return tenantRepository.findById(tenantId)
                .map(Tenant::getBaseCurrency)
                .orElse("USD");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TenantSummary> findById(UUID tenantId) {
        return tenantRepository.findById(tenantId).map(this::toSummary);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TenantSummary> findBySlug(String slug) {
        return tenantRepository.findBySlug(slug).map(this::toSummary);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsBySlug(String slug) {
        return tenantRepository.existsBySlug(slug);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TenantSummary> findAll() {
        return tenantRepository.findAll().stream().map(this::toSummary).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Long> countUsersByTenantIds(Collection<UUID> tenantIds) {
        Map<UUID, Long> counts = new HashMap<>();
        if (tenantIds == null || tenantIds.isEmpty()) {
            return counts;
        }
        for (UUID tenantId : tenantIds) {
            counts.put(tenantId, (long) userAccountRepository.findByTenantIdOrderByCreatedAtAsc(tenantId).size());
        }
        return counts;
    }

    @Override
    @Transactional(readOnly = true)
    public long countUsersForTenant(UUID tenantId) {
        return userAccountRepository.findByTenantIdOrderByCreatedAtAsc(tenantId).size();
    }

    @Override
    @Transactional
    public TenantSummary updateStatus(UUID tenantId, String status) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found"));
        tenant.setStatus(status.trim().toUpperCase(Locale.ROOT));
        return toSummary(tenantRepository.saveAndFlush(tenant));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ActiveUserSummary> findFirstActiveUser(UUID tenantId) {
        return userAccountRepository.findByTenantIdOrderByCreatedAtAsc(tenantId).stream()
                .filter(u -> UserAccount.Status.ACTIVE.name().equals(u.getStatus()))
                .findFirst()
                .map(u -> new ActiveUserSummary(u.getId(), u.getTenantId(), u.getEmail(), u.getFullName()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ApiTokenPrincipal> authenticateApiToken(String tokenHash) {
        Optional<ApiToken> tokenOpt = apiTokenRepository.findByTokenHash(tokenHash);
        if (tokenOpt.isEmpty()) {
            return Optional.empty();
        }
        ApiToken apiToken = tokenOpt.get();
        if (apiToken.isRevoked() || (apiToken.getExpiresAt() != null && apiToken.getExpiresAt().isBefore(Instant.now()))) {
            return Optional.empty();
        }
        Optional<UserAccount> userOpt = userAccountRepository.findByTenantIdAndId(apiToken.getTenantId(), apiToken.getUserId());
        if (userOpt.isEmpty() || !UserAccount.Status.ACTIVE.name().equals(userOpt.get().getStatus())) {
            return Optional.empty();
        }
        UserAccount user = userOpt.get();
        Set<String> scopes = Arrays.stream(apiToken.getScopes().split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
        return Optional.of(new ApiTokenPrincipal(
                user.getId(),
                apiToken.getTenantId(),
                user.getEmail(),
                user.getFullName(),
                scopes,
                apiToken.getId()
        ));
    }

    private TenantSummary toSummary(Tenant t) {
        return new TenantSummary(
                t.getId(),
                t.getName(),
                t.getSlug(),
                t.getStatus(),
                t.getBaseCurrency(),
                t.getTimezone(),
                t.getCreatedAt()
        );
    }
}
