package com.ims.platform;

import com.ims.common.outbox.OutboxEvent;
import com.ims.config.security.JwtTokenService;
import com.ims.tenant.AuthService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/platform/v1")
public class PlatformController {

    public record LoginPayload(String email, String password) {}
    public record StatusPayload(String status) {}
    public record PlanChangePayload(String planCode) {}
    public record FeatureOverridePayload(Boolean enabled, Long limitValue, String reason) {}
    public record PlanFeaturePayload(boolean enabled, Long limitValue) {}
    public record SupportSessionPayload(String reason) {}
    public record CreateTenantPayload(
            @NotBlank String tenantName,
            @NotBlank String tenantSlug,
            @NotBlank @Email String adminEmail,
            @NotBlank String adminFullName,
            @NotBlank @Size(min = 8) String adminPassword,
            String baseCurrency,
            String timezone,
            String planCode
    ) {}

    private final PlatformService platformService;

    public PlatformController(PlatformService platformService) {
        this.platformService = platformService;
    }

    @PostMapping("/auth/login")
    public PlatformService.PlatformLoginResponse login(@RequestBody LoginPayload payload) {
        return platformService.login(payload.email(), payload.password());
    }

    @GetMapping("/tenants")
    public List<PlatformService.TenantSummaryView> listTenants() {
        return platformService.listTenants();
    }

    @PostMapping("/tenants")
    @ResponseStatus(HttpStatus.CREATED)
    public PlatformService.TenantSummaryView createTenant(
            Authentication auth,
            @Valid @RequestBody CreateTenantPayload payload
    ) {
        return platformService.createTenant(
                principal(auth),
                new AuthService.RegisterTenantCommand(
                        payload.tenantName(),
                        payload.tenantSlug(),
                        payload.adminEmail(),
                        payload.adminFullName(),
                        payload.adminPassword(),
                        payload.baseCurrency(),
                        payload.timezone(),
                        payload.planCode() != null && !payload.planCode().isBlank() ? payload.planCode() : "TRIAL"
                )
        );
    }

    @PatchMapping("/tenants/{tenantId}/status")
    public PlatformService.TenantSummaryView updateStatus(
            Authentication auth,
            @PathVariable UUID tenantId,
            @RequestBody StatusPayload payload
    ) {
        return platformService.updateTenantStatus(principal(auth), tenantId, payload.status());
    }

    @PutMapping("/tenants/{tenantId}/plan")
    public PlatformService.TenantSummaryView changePlan(
            Authentication auth,
            @PathVariable UUID tenantId,
            @RequestBody PlanChangePayload payload
    ) {
        return platformService.changeTenantPlan(principal(auth), tenantId, payload.planCode());
    }

    @PutMapping("/tenants/{tenantId}/features/{featureKey}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setOverride(
            Authentication auth,
            @PathVariable UUID tenantId,
            @PathVariable String featureKey,
            @RequestBody FeatureOverridePayload payload
    ) {
        platformService.setFeatureOverride(principal(auth), tenantId, featureKey, payload.enabled(), payload.limitValue(), payload.reason());
    }

    @GetMapping("/plans")
    public List<PlatformService.PlanView> listPlans() {
        return platformService.listPlans();
    }

    @PutMapping("/plans/{planId}/features/{featureKey}")
    public PlatformService.PlanView updatePlanFeature(
            Authentication auth,
            @PathVariable UUID planId,
            @PathVariable String featureKey,
            @RequestBody PlanFeaturePayload payload
    ) {
        return platformService.updatePlanFeature(principal(auth), planId, featureKey, payload.enabled(), payload.limitValue());
    }

    @PostMapping("/tenants/{tenantId}/usage-snapshots")
    public TenantUsageSnapshot captureSnapshot(Authentication auth, @PathVariable UUID tenantId) {
        return platformService.captureUsageSnapshot(principal(auth), tenantId);
    }

    @GetMapping("/tenants/{tenantId}/usage-snapshots")
    public List<TenantUsageSnapshot> listSnapshots(@PathVariable UUID tenantId) {
        return platformService.listUsageSnapshots(tenantId);
    }

    @PostMapping("/tenants/{tenantId}/support-sessions")
    public PlatformService.SupportSessionResponse startSupport(
            Authentication auth,
            @PathVariable UUID tenantId,
            @RequestBody SupportSessionPayload payload
    ) {
        return platformService.startSupportSession(principal(auth), tenantId, payload.reason());
    }

    @GetMapping("/outbox")
    public List<OutboxEvent> listOutbox() {
        return platformService.listDeadLetterOutbox();
    }

    @PostMapping("/outbox/{eventId}/retry")
    public OutboxEvent retryOutbox(Authentication auth, @PathVariable UUID eventId) {
        return platformService.retryOutboxEvent(principal(auth), eventId);
    }

    @GetMapping("/audit-logs")
    public List<PlatformAuditLog> listAuditLogs() {
        return platformService.listAuditLogs();
    }

    private JwtTokenService.PlatformUserPrincipal principal(Authentication auth) {
        if (auth != null && auth.getPrincipal() instanceof JwtTokenService.PlatformUserPrincipal p) {
            return p;
        }
        return null;
    }
}
