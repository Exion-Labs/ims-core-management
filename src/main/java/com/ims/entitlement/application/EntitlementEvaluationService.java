package com.ims.entitlement.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.ims.common.events.EntitlementsChangedEvent;
import com.ims.config.tenant.TenantContext;
import com.ims.entitlement.domain.model.Plan;
import com.ims.entitlement.domain.model.PlanFeature;
import com.ims.entitlement.domain.model.TenantFeatureOverride;
import com.ims.entitlement.domain.model.TenantSubscription;
import com.ims.entitlement.infrastructure.persistence.PlanFeatureRepository;
import com.ims.entitlement.infrastructure.persistence.PlanRepository;
import com.ims.entitlement.infrastructure.persistence.TenantFeatureOverrideRepository;
import com.ims.entitlement.infrastructure.persistence.TenantSubscriptionRepository;
import com.ims.entitlement.spi.EntitlementAdminPort;
import com.ims.entitlement.spi.EntitlementFacade;
import com.ims.entitlement.spi.Feature;
import com.ims.entitlement.spi.FeatureNotEnabledException;
import com.ims.entitlement.spi.LimitReachedException;
import com.ims.shared.exception.BusinessRuleException;
import com.ims.shared.exception.ResourceNotFoundException;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class EntitlementEvaluationService implements EntitlementFacade, EntitlementAdminPort {

    private final PlanRepository planRepository;
    private final PlanFeatureRepository planFeatureRepository;
    private final TenantSubscriptionRepository subscriptionRepository;
    private final TenantFeatureOverrideRepository overrideRepository;

    private final Cache<UUID, EffectiveEntitlements> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(30))
            .maximumSize(10_000)
            .build();

    public EntitlementEvaluationService(
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

    @Override
    @Transactional(readOnly = true)
    public EffectiveEntitlements getEffectiveForCurrentTenant() {
        return getEffectiveForTenant(TenantContext.require());
    }

    @Override
    @Transactional(readOnly = true)
    public EffectiveEntitlements getEffectiveForTenant(UUID tenantId) {
        return cache.get(tenantId, this::computeEffective);
    }

    @Override
    public void requireFeature(Feature feature) {
        EffectiveEntitlements effective = getEffectiveForCurrentTenant();
        if (!effective.isEnabled(feature)) {
            throw new FeatureNotEnabledException(feature);
        }
    }

    @Override
    public void requireWithinLimit(Feature limitFeature, long currentCount) {
        EffectiveEntitlements effective = getEffectiveForCurrentTenant();
        Long limit = effective.getLimit(limitFeature);
        if (limit != null && currentCount >= limit) {
            throw new LimitReachedException(limitFeature, limit, currentCount);
        }
    }

    @Override
    public void evictCache(UUID tenantId) {
        cache.invalidate(tenantId);
    }

    @EventListener
    public void onEntitlementsChanged(EntitlementsChangedEvent event) {
        evictCache(event.tenantId());
    }

    @Override
    @Transactional
    public void ensureDefaultPlansSeeded() {
        seedPlanIfMissing("TRIAL", "Trial (30 Days)", "Full-featured 30-day evaluation plan",
                Set.of(
                        Feature.API_ACCESS, Feature.IMPORT_EXPORT, Feature.SERIAL_TRACKING,
                        Feature.QUALITY_TESTS, Feature.BARCODES, Feature.MULTI_CURRENCY,
                        Feature.PURCHASING, Feature.MANUFACTURING, Feature.SALES,
                        Feature.RETURNS, Feature.TRANSFERS
                ),
                Map.of(
                        Feature.MAX_USERS, 10L,
                        Feature.MAX_PARTS, 5_000L,
                        Feature.MAX_LOCATIONS, 200L,
                        Feature.MAX_WEBHOOKS, 5L,
                        Feature.STORAGE_MB, 2_000L,
                        Feature.API_RATE_PER_MIN, 120L
                ));

        seedPlanIfMissing("STARTER", "Starter", "Core inventory, barcodes, and purchasing for growing teams",
                Set.of(
                        Feature.API_ACCESS, Feature.IMPORT_EXPORT, Feature.BARCODES, Feature.PURCHASING
                ),
                Map.of(
                        Feature.MAX_USERS, 5L,
                        Feature.MAX_PARTS, 2_500L,
                        Feature.MAX_LOCATIONS, 100L,
                        Feature.MAX_WEBHOOKS, 0L,
                        Feature.STORAGE_MB, 1_000L,
                        Feature.API_RATE_PER_MIN, 60L
                ));

        seedPlanIfMissing("PRO", "Professional", "Full warehouse, manufacturing, multi-currency, sales and webhooks",
                Set.of(
                        Feature.API_ACCESS, Feature.IMPORT_EXPORT, Feature.SERIAL_TRACKING,
                        Feature.QUALITY_TESTS, Feature.BARCODES, Feature.MULTI_CURRENCY,
                        Feature.PURCHASING, Feature.MANUFACTURING, Feature.SALES,
                        Feature.RETURNS, Feature.TRANSFERS, Feature.PRICING_ROLLUP,
                        Feature.WEBHOOKS, Feature.REALTIME
                ),
                Map.of(
                        Feature.MAX_USERS, 25L,
                        Feature.MAX_PARTS, 50_000L,
                        Feature.MAX_LOCATIONS, 2_000L,
                        Feature.MAX_WEBHOOKS, 20L,
                        Feature.STORAGE_MB, 25_000L,
                        Feature.API_RATE_PER_MIN, 600L
                ));

        seedPlanIfMissing("ENTERPRISE", "Enterprise", "Unlimited scale with custom PDF templates, SLA and dedicated support",
                Set.of(
                        Feature.API_ACCESS, Feature.IMPORT_EXPORT, Feature.SERIAL_TRACKING,
                        Feature.QUALITY_TESTS, Feature.BARCODES, Feature.MULTI_CURRENCY,
                        Feature.PURCHASING, Feature.MANUFACTURING, Feature.SALES,
                        Feature.RETURNS, Feature.TRANSFERS, Feature.PRICING_ROLLUP,
                        Feature.CUSTOM_REPORTS, Feature.WEBHOOKS, Feature.REALTIME
                ),
                Map.of(
                        Feature.MAX_USERS, 500L,
                        Feature.MAX_PARTS, 500_000L,
                        Feature.MAX_LOCATIONS, 20_000L,
                        Feature.MAX_WEBHOOKS, 100L,
                        Feature.STORAGE_MB, 250_000L,
                        Feature.API_RATE_PER_MIN, 3_000L
                ));
    }

    @Override
    @Transactional
    public String assignInitialPlan(UUID tenantId, String requestedPlanCode) {
        ensureDefaultPlansSeeded();
        String code = (requestedPlanCode != null && !requestedPlanCode.isBlank())
                ? requestedPlanCode.trim().toUpperCase(Locale.ROOT)
                : "TRIAL";
        Plan plan = planRepository.findByCode(code)
                .orElseGet(() -> planRepository.findByCode("TRIAL").orElseThrow());

        TenantSubscription sub = new TenantSubscription();
        sub.setTenantId(tenantId);
        sub.setPlanId(plan.getId());
        sub.setStatus("TRIAL".equals(plan.getCode()) ? "TRIAL" : "ACTIVE");
        sub.setStartsAt(Instant.now());
        if ("TRIAL".equals(plan.getCode())) {
            sub.setEndsAt(Instant.now().plus(Duration.ofDays(30)));
        }
        subscriptionRepository.save(sub);
        evictCache(tenantId);
        return plan.getCode();
    }

    @Override
    @Transactional
    public void changeTenantPlan(UUID tenantId, String planCode) {
        Plan plan = planRepository.findByCode(planCode.trim().toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new ResourceNotFoundException("Plan not found: " + planCode));
        TenantSubscription sub = subscriptionRepository.findByTenantId(tenantId)
                .orElseGet(() -> {
                    TenantSubscription s = new TenantSubscription();
                    s.setTenantId(tenantId);
                    return s;
                });
        sub.setPlanId(plan.getId());
        sub.setStatus("ACTIVE");
        subscriptionRepository.saveAndFlush(sub);
        evictCache(tenantId);
    }

    @Override
    @Transactional
    public void setFeatureOverride(UUID tenantId, String featureKey, Boolean enabled, Long limitValue, String reason) {
        Feature feature = Feature.valueOf(featureKey.trim().toUpperCase(Locale.ROOT));
        TenantFeatureOverride ov = overrideRepository.findByTenantIdAndFeatureKey(tenantId, feature.name())
                .orElseGet(() -> {
                    TenantFeatureOverride o = new TenantFeatureOverride();
                    o.setTenantId(tenantId);
                    o.setFeatureKey(feature.name());
                    return o;
                });
        ov.setEnabled(Boolean.TRUE.equals(enabled));
        ov.setLimitValue(limitValue);
        ov.setReason(reason != null ? reason : "Platform override");
        overrideRepository.saveAndFlush(ov);
        evictCache(tenantId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PlanView> listPlans() {
        return planRepository.findAll().stream().map(p -> {
            List<PlanFeatureView> feats = planFeatureRepository.findByPlanId(p.getId()).stream()
                    .map(pf -> new PlanFeatureView(pf.getFeatureKey(), pf.isEnabled(), pf.getLimitValue()))
                    .toList();
            return new PlanView(p.getId(), p.getCode(), p.getName(), p.getDescription(), feats);
        }).toList();
    }

    @Override
    @Transactional
    public PlanView updatePlanFeature(UUID planId, String featureKey, boolean enabled, Long limitValue) {
        planRepository.findById(planId)
                .orElseThrow(() -> new ResourceNotFoundException("Plan not found"));
        Feature feature = Feature.valueOf(featureKey.trim().toUpperCase(Locale.ROOT));

        List<PlanFeature> existing = planFeatureRepository.findByPlanId(planId);
        if (!enabled && feature.isCoreAlwaysOn()) {
            throw new BusinessRuleException("CORE_FEATURE_REQUIRED",
                    "Core feature " + feature.name() + " cannot be disabled");
        }
        if (enabled) {
            for (Feature dep : feature.getDependencies()) {
                boolean depEnabled = existing.stream()
                        .anyMatch(pf -> pf.getFeatureKey().equals(dep.name()) && pf.isEnabled());
                if (!depEnabled) {
                    throw new BusinessRuleException("FEATURE_DEPENDENCY_MISSING",
                            "Feature " + feature.name() + " requires " + dep.name() + " to be enabled on this plan");
                }
            }
        } else {
            for (PlanFeature pf : existing) {
                if (!pf.isEnabled()) continue;
                Feature other = Feature.valueOf(pf.getFeatureKey());
                if (other.getDependencies().contains(feature)) {
                    throw new BusinessRuleException("FEATURE_DEPENDENCY_CONFLICT",
                            "Cannot disable " + feature.name() + " while dependent feature " + other.name() + " is enabled");
                }
            }
        }

        PlanFeature target = existing.stream()
                .filter(pf -> pf.getFeatureKey().equals(feature.name()))
                .findFirst()
                .orElseGet(() -> {
                    PlanFeature pf = new PlanFeature();
                    pf.setPlanId(planId);
                    pf.setFeatureKey(feature.name());
                    return pf;
                });
        target.setEnabled(enabled);
        target.setLimitValue(limitValue);
        planFeatureRepository.saveAndFlush(target);

        return listPlans().stream().filter(p -> p.id().equals(planId)).findFirst().orElseThrow();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, TenantPlanSummary> getTenantPlanSummaries(Collection<UUID> tenantIds) {
        if (tenantIds == null || tenantIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Plan> plansById = new HashMap<>();
        for (Plan p : planRepository.findAll()) {
            plansById.put(p.getId(), p);
        }
        Map<UUID, TenantPlanSummary> result = new HashMap<>();
        for (TenantSubscription sub : subscriptionRepository.findByTenantIdIn(tenantIds)) {
            Plan plan = plansById.get(sub.getPlanId());
            if (plan != null) {
                result.put(sub.getTenantId(), new TenantPlanSummary(plan.getCode(), plan.getName()));
            }
        }
        return result;
    }

    private void seedPlanIfMissing(
            String code,
            String name,
            String description,
            Set<Feature> enabledFeatures,
            Map<Feature, Long> limits
    ) {
        if (planRepository.findByCode(code).isPresent()) {
            return;
        }
        Plan plan = new Plan();
        plan.setCode(code);
        plan.setName(name);
        plan.setDescription(description);
        plan = planRepository.save(plan);

        for (Feature f : Feature.values()) {
            if (f.isCoreAlwaysOn()) {
                continue;
            }
            PlanFeature pf = new PlanFeature();
            pf.setPlanId(plan.getId());
            pf.setFeatureKey(f.name());
            if (f.getType() == Feature.FeatureType.BOOLEAN) {
                pf.setEnabled(enabledFeatures.contains(f));
            } else {
                pf.setEnabled(true);
                pf.setLimitValue(limits.getOrDefault(f, 10L));
            }
            planFeatureRepository.save(pf);
        }
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
