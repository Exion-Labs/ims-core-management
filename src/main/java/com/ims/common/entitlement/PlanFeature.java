package com.ims.common.entitlement;

import com.ims.common.GlobalEntity;
import com.ims.common.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@GlobalEntity
@Table(name = "plan_features")
public class PlanFeature {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UuidV7.generate();

    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    @Column(name = "feature_key", nullable = false, length = 80)
    private String featureKey;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "limit_value")
    private Long limitValue;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getPlanId() {
        return planId;
    }

    public void setPlanId(UUID planId) {
        this.planId = planId;
    }

    public String getFeatureKey() {
        return featureKey;
    }

    public void setFeatureKey(String featureKey) {
        this.featureKey = featureKey;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Long getLimitValue() {
        return limitValue;
    }

    public void setLimitValue(Long limitValue) {
        this.limitValue = limitValue;
    }
}
