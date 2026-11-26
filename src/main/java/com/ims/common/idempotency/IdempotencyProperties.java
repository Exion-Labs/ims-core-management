package com.ims.common.idempotency;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties("ims.idempotency")
public class IdempotencyProperties {

    private boolean enabled = true;
    private Duration ttl = Duration.ofHours(24);
    private int maxKeyLength = 255;
    private boolean requireForApiTokens = false;
    private boolean requireForSessions = false;
    private String cleanupCron = "0 15 * * * *";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }

    public int getMaxKeyLength() {
        return maxKeyLength;
    }

    public void setMaxKeyLength(int maxKeyLength) {
        this.maxKeyLength = maxKeyLength;
    }

    public boolean isRequireForApiTokens() {
        return requireForApiTokens;
    }

    public void setRequireForApiTokens(boolean requireForApiTokens) {
        this.requireForApiTokens = requireForApiTokens;
    }

    public boolean isRequireForSessions() {
        return requireForSessions;
    }

    public void setRequireForSessions(boolean requireForSessions) {
        this.requireForSessions = requireForSessions;
    }

    public String getCleanupCron() {
        return cleanupCron;
    }

    public void setCleanupCron(String cleanupCron) {
        this.cleanupCron = cleanupCron;
    }
}
