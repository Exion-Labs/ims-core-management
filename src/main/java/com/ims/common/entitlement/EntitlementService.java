package com.ims.common.entitlement;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.ims.common.events.EntitlementsChangedEvent;
import com.ims.config.tenant.TenantContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Computes and caches effective tenant entitlements (Plan features + non-expired TenantFeatureOverrides).
 */
@Service
public class EntitlementService {

    public record EffectiveFeature(
            String featureKey,
            Feature.FeatureType type,
            boolean enabled,
            Long limitValue,
            String source, // CORE, PLAN, OVERRIDE, OVERRIDE_TRIAL
            String overrideReason,
            Instant overrideExpiresAt,
            String description
    ) {}

    public record EffectiveEntitlements(
            UUID tenantId,
            String planCode,
            String planName,
            String subscriptionStatus,
            Instant subscriptionEndsAt,
            Map<Feature, EffectiveFeature> features
    ) {
        public boolean isEnabled(Feature f) {
            if (f.isCoreAlwaysOn()) {
                return true;
            }
            EffectiveFeature ef = features.get(f);
            return ef != null && ef.enabled();
        }

        public Long getLimit(Feature f) {
            EffectiveFeature ef = features.get(f);
            return ef != null ? ef.limitValue() : null;
        }

        public Set<String> enabledFeatureKeys() {
            return features.values().stream()
                    .filter(EffectiveFeature::enabled)
                    .map(EffectiveFeature::featureKey)
                    .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        }

        public Map<String, Long> limitsMap() {
            Map<String, Long> map = new LinkedHashMap<>();
            for (EffectiveFeature ef : features.values()) {
                if (ef.type() == Feature.FeatureType.LIMIT && ef.limitValue() != null) {
                    map.put(ef.featureKey(), ef.limitValue());
                }
            }
            return map;
        }
    }

    private final PlanRepository planRepository;
    private final PlanFeatureRepository planFeatureRepository;
    private final TenantSubscriptionRepository subscriptionRepository;
    private final TenantFeatureOverrideRepository overrideRepository;

    private final Cache<UUID, EffectiveEntitlements> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(30))
            .maximumSize(10_000)
            .build();

    public EntitlementService(
            PlanRepository planRepository,
            PlanFeatureRepository planFeatureRepository,
            TenantSubscriptionRepository subscriptionRepository,
            TenantFeatureOverrideRepository overrideRepository
    ) {
        this.planRepository = planRepository;
        this.planFeatureRepository = planFeatureRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.overrideRepository = overrideRepository;
    }

    @Transactional(readOnly = true)
    public EffectiveEntitlements getEffectiveForCurrentTenant() {
        return getEffectiveForTenant(TenantContext.require());
    }

    @Transactional(readOnly = true)
    public EffectiveEntitlements getEffectiveForTenant(UUID tenantId) {
        return cache.get(tenantId, this::computeEffective);
    }

    public void requireFeature(Feature feature) {
        EffectiveEntitlements effective = getEffectiveForCurrentTenant();
        if (!effective.isEnabled(feature)) {
            throw new FeatureNotEnabledException(feature);
        }
    }

    public void requireWithinLimit(Feature limitFeature, long currentCount) {
        EffectiveEntitlements effective = getEffectiveForCurrentTenant();
        Long limit = effective.getLimit(limitFeature);
        if (limit != null && currentCount >= limit) {
            throw new LimitReachedException(limitFeature, limit, currentCount);
        }
    }

    public void evictCache(UUID tenantId) {
        cache.invalidate(tenantId);
    }

    @EventListener
    public void onEntitlementsChanged(EntitlementsChangedEvent event) {
        evictCache(event.tenantId());
    }

    private EffectiveEntitlements computeEffective(UUID tenantId) {
        Instant now = Instant.now();
        Optional<TenantSubscription> subOpt = subscriptionRepository.findByTenantId(tenantId);

        String planCode = "STARTER";
        String planName = "Starter";
        String subStatus = "ACTIVE";
        Instant subEndsAt = null;
        Map<String, PlanFeature> planFeatureMap = new LinkedHashMap<>();

        if (subOpt.isPresent()) {
            TenantSubscription sub = subOpt.get();
            subStatus = sub.getStatus();
            subEndsAt = sub.getEndsAt();
            Optional<Plan> planOpt = planRepository.findById(sub.getPlanId());
            if (planOpt.isPresent()) {
                Plan plan = planOpt.get();
                planCode = plan.getCode();
                planName = plan.getName();
                for (PlanFeature pf : planFeatureRepository.findByPlanId(plan.getId())) {
                    planFeatureMap.put(pf.getFeatureKey(), pf);
                }
            }
        }

        List<TenantFeatureOverride> overrides = overrideRepository.findByTenantId(tenantId);
        Map<String, TenantFeatureOverride> activeOverrides = new LinkedHashMap<>();
        for (TenantFeatureOverride ov : overrides) {
            if (!ov.isExpired(now)) {
                activeOverrides.put(ov.getFeatureKey(), ov);
            }
        }

        Map<Feature, EffectiveFeature> result = new EnumMap<>(Feature.class);
        for (Feature f : Feature.values()) {
            if (f.isCoreAlwaysOn()) {
                result.put(f, new EffectiveFeature(
                        f.name(), f.getType(), true, null, "CORE", null, null, f.getDescription()
                ));
                continue;
            }

            TenantFeatureOverride ov = activeOverrides.get(f.name());
            if (ov != null) {
                String source = ov.getExpiresAt() != null ? "OVERRIDE_TRIAL" : "OVERRIDE";
                result.put(f, new EffectiveFeature(
                        f.name(), f.getType(), ov.isEnabled(), ov.getLimitValue(),
                        source, ov.getReason(), ov.getExpiresAt(), f.getDescription()
                ));
                continue;
            }

            PlanFeature pf = planFeatureMap.get(f.name());
            if (pf != null) {
                result.put(f, new EffectiveFeature(
                        f.name(), f.getType(), pf.isEnabled(), pf.getLimitValue(),
                        "PLAN", null, null, f.getDescription()
                ));
            } else {
                // Default fallback if no plan row exists
                boolean defaultEnabled = (f == Feature.API_ACCESS || f == Feature.IMPORT_EXPORT);
                Long defaultLimit = defaultLimitFor(f);
                result.put(f, new EffectiveFeature(
                        f.name(), f.getType(), defaultEnabled, defaultLimit,
                        "PLAN", null, null, f.getDescription()
                ));
            }
        }

        return new EffectiveEntitlements(tenantId, planCode, planName, subStatus, subEndsAt, result);
    }

    private Long defaultLimitFor(Feature f) {
        return switch (f) {
            case MAX_USERS -> 25L;
            case MAX_PARTS -> 10_000L;
            case MAX_LOCATIONS -> 500L;
            case MAX_WEBHOOKS -> 10L;
            case STORAGE_MB -> 5_000L;
            case API_RATE_PER_MIN -> 300L;
            default -> null;
        };
    }
}
