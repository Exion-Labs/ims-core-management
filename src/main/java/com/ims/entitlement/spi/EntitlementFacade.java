package com.ims.entitlement.spi;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Public facade for evaluating tenant feature entitlements and quotas.
 */
public interface EntitlementFacade {

    record EffectiveFeature(
            String featureKey,
            Feature.FeatureType type,
            boolean enabled,
            Long limitValue,
            String source, // CORE, PLAN, OVERRIDE, OVERRIDE_TRIAL
            String overrideReason,
            Instant overrideExpiresAt,
            String description
    ) {}

    record EffectiveEntitlements(
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
                    .collect(Collectors.toCollection(LinkedHashSet::new));
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

    EffectiveEntitlements getEffectiveForCurrentTenant();

    EffectiveEntitlements getEffectiveForTenant(UUID tenantId);

    void requireFeature(Feature feature);

    void requireWithinLimit(Feature limitFeature, long currentCount);

    void evictCache(UUID tenantId);
}
