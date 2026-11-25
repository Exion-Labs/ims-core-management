package com.ims.entitlement.spi;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Administrative port for seeding plans, assigning tenant subscriptions, and managing feature overrides.
 */
public interface EntitlementAdminPort {

    record PlanFeatureView(String featureKey, boolean enabled, Long limitValue) {}

    record PlanView(UUID id, String code, String name, String description, List<PlanFeatureView> features) {}

    record TenantPlanSummary(String planCode, String planName) {}

    void ensureDefaultPlansSeeded();

    String assignInitialPlan(UUID tenantId, String requestedPlanCode);

    void changeTenantPlan(UUID tenantId, String planCode);

    void setFeatureOverride(UUID tenantId, String featureKey, Boolean enabled, Long limitValue, String reason);

    List<PlanView> listPlans();

    PlanView updatePlanFeature(UUID planId, String featureKey, boolean enabled, Long limitValue);

    Map<UUID, TenantPlanSummary> getTenantPlanSummaries(Collection<UUID> tenantIds);
}
