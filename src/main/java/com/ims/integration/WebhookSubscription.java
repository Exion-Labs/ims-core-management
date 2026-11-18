package com.ims.integration;

import com.ims.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "webhook_subscriptions")
public class WebhookSubscription extends BaseTenantEntity {

    @Column(nullable = false, length = 150)
    private String name;

    @Column(name = "target_url", nullable = false, length = 1024)
    private String targetUrl;

    @Column(nullable = false, length = 255)
    private String secret;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "event_types", nullable = false, columnDefinition = "jsonb")
    private List<String> eventTypes = new ArrayList<>();

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "last_delivery_at")
    private Instant lastDeliveryAt;

    @Column(name = "last_status_code")
    private Integer lastStatusCode;

    @Column(name = "failure_count", nullable = false)
    private int failureCount = 0;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getTargetUrl() { return targetUrl; }
    public void setTargetUrl(String targetUrl) { this.targetUrl = targetUrl; }
    public String getSecret() { return secret; }
    public void setSecret(String secret) { this.secret = secret; }
    public List<String> getEventTypes() { return eventTypes; }
    public void setEventTypes(List<String> eventTypes) { this.eventTypes = eventTypes; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Instant getLastDeliveryAt() { return lastDeliveryAt; }
    public void setLastDeliveryAt(Instant lastDeliveryAt) { this.lastDeliveryAt = lastDeliveryAt; }
    public Integer getLastStatusCode() { return lastStatusCode; }
    public void setLastStatusCode(Integer lastStatusCode) { this.lastStatusCode = lastStatusCode; }
    public int getFailureCount() { return failureCount; }
    public void setFailureCount(int failureCount) { this.failureCount = failureCount; }
}
