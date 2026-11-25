package com.ims.platform;

import com.ims.catalog.spi.CatalogReadPort;
import com.ims.common.audit.AuditContext;
import com.ims.common.audit.AuditService;
import com.ims.entitlement.spi.EntitlementAdminPort;
import com.ims.common.outbox.OutboxEvent;
import com.ims.common.outbox.OutboxEventRepository;
import com.ims.config.security.JwtTokenService;
import com.ims.config.tenant.TenantContext;
import com.ims.identity.spi.TenantProvisioningPort;
import com.ims.identity.spi.TenantReadPort;
import com.ims.stock.spi.StockReadPort;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class PlatformService implements ApplicationRunner {

    public record PlatformUserView(UUID id, String email, String fullName, String role, boolean active) {}
    public record PlatformLoginResponse(String accessToken, PlatformUserView user) {}

    public record TenantSummaryView(
            UUID id,
            String name,
            String slug,
            String status,
            String baseCurrency,
            String planCode,
            String planName,
            long userCount,
            Instant createdAt
    ) {}

    public record PlanFeatureView(String featureKey, boolean enabled, Long limitValue) {}
    public record PlanView(UUID id, String code, String name, String description, List<PlanFeatureView> features) {}

    public record SupportSessionResponse(
            UUID sessionId,
            UUID targetTenantId,
            String targetTenantSlug,
            String impersonationToken,
            Instant expiresAt,
            String reason
    ) {}

    private final PlatformUserRepository platformUserRepository;
    private final TenantUsageSnapshotRepository usageSnapshotRepository;
    private final SupportSessionRepository supportSessionRepository;
    private final PlatformAuditLogRepository platformAuditLogRepository;
    private final TenantReadPort tenantReadPort;
    private final EntitlementAdminPort entitlementAdminPort;
    private final OutboxEventRepository outboxEventRepository;
    private final AuditService auditService;
    private final CatalogReadPort catalogReadPort;
    private final StockReadPort stockReadPort;
    private final JwtTokenService jwtTokenService;
    private final PasswordEncoder passwordEncoder;
    private final TenantProvisioningPort tenantProvisioningPort;

    public PlatformService(
            PlatformUserRepository platformUserRepository,
            TenantUsageSnapshotRepository usageSnapshotRepository,
            SupportSessionRepository supportSessionRepository,
            PlatformAuditLogRepository platformAuditLogRepository,
            TenantReadPort tenantReadPort,
            EntitlementAdminPort entitlementAdminPort,
            OutboxEventRepository outboxEventRepository,
            AuditService auditService,
            CatalogReadPort catalogReadPort,
            StockReadPort stockReadPort,
            JwtTokenService jwtTokenService,
            PasswordEncoder passwordEncoder,
            TenantProvisioningPort tenantProvisioningPort
    ) {
        this.platformUserRepository = platformUserRepository;
        this.usageSnapshotRepository = usageSnapshotRepository;
        this.supportSessionRepository = supportSessionRepository;
        this.platformAuditLogRepository = platformAuditLogRepository;
        this.tenantReadPort = tenantReadPort;
        this.entitlementAdminPort = entitlementAdminPort;
        this.outboxEventRepository = outboxEventRepository;
        this.auditService = auditService;
        this.catalogReadPort = catalogReadPort;
        this.stockReadPort = stockReadPort;
        this.jwtTokenService = jwtTokenService;
        this.passwordEncoder = passwordEncoder;
        this.tenantProvisioningPort = tenantProvisioningPort;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (platformUserRepository.findByEmailIgnoreCase("platform@ims.dev").isEmpty()) {
            PlatformUser admin = new PlatformUser();
            admin.setEmail("platform@ims.dev");
            admin.setFullName("IMS Platform Operator");
            admin.setPasswordHash(passwordEncoder.encode("Password123!"));
            admin.setRole("PLATFORM_ADMIN");
            admin.setActive(true);
            platformUserRepository.saveAndFlush(admin);
        }
    }

    @Transactional(readOnly = true)
    public PlatformLoginResponse login(String email, String password) {
        PlatformUser user = platformUserRepository.findByEmailIgnoreCase(email)
                .filter(PlatformUser::isActive)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }
        String token = jwtTokenService.createPlatformAccessToken(user.getId(), user.getEmail(), user.getFullName(), user.getRole());
        return new PlatformLoginResponse(token, new PlatformUserView(user.getId(), user.getEmail(), user.getFullName(), user.getRole(), user.isActive()));
    }

    @Transactional(readOnly = true)
    public List<TenantSummaryView> listTenants() {
        List<TenantReadPort.TenantSummary> tenants = tenantReadPort.findAll();
        List<UUID> ids = tenants.stream().map(TenantReadPort.TenantSummary::id).toList();
        Map<UUID, EntitlementAdminPort.TenantPlanSummary> plans = entitlementAdminPort.getTenantPlanSummaries(ids);
        Map<UUID, Long> userCounts = tenantReadPort.countUsersByTenantIds(ids);

        return tenants.stream().map(t -> {
            EntitlementAdminPort.TenantPlanSummary plan = plans.get(t.id());
            long userCount = userCounts.getOrDefault(t.id(), 0L);
            return new TenantSummaryView(
                    t.id(),
                    t.name(),
                    t.slug(),
                    t.status(),
                    t.baseCurrency(),
                    plan != null ? plan.planCode() : "NONE",
                    plan != null ? plan.planName() : "None",
                    userCount,
                    t.createdAt()
            );
        }).toList();
    }

    public TenantSummaryView createTenant(
            JwtTokenService.PlatformUserPrincipal actor,
            TenantProvisioningPort.ProvisionTenantCommand cmd
    ) {
        TenantProvisioningPort.AuthTokensResponse tokens = tenantProvisioningPort.registerTenant(cmd, true);
        recordPlatformAudit(
                actor,
                "TENANT_CREATED",
                "Tenant",
                tokens.tenantId(),
                Map.of("slug", tokens.tenantSlug(), "adminEmail", tokens.email())
        );
        return listTenants().stream()
                .filter(t -> t.id().equals(tokens.tenantId()))
                .findFirst()
                .orElseThrow();
    }

    @Transactional
    public TenantSummaryView updateTenantStatus(JwtTokenService.PlatformUserPrincipal actor, UUID tenantId, String status) {
        TenantReadPort.TenantSummary existing = tenantReadPort.findById(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found"));
        String prev = existing.status();
        TenantReadPort.TenantSummary updated = tenantReadPort.updateStatus(tenantId, status);
        recordPlatformAudit(actor, "TENANT_STATUS_CHANGE", "Tenant", tenantId, Map.of("from", prev, "to", updated.status()));
        return listTenants().stream().filter(t -> t.id().equals(tenantId)).findFirst().orElseThrow();
    }

    @Transactional
    public TenantSummaryView changeTenantPlan(JwtTokenService.PlatformUserPrincipal actor, UUID tenantId, String planCode) {
        tenantReadPort.findById(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found"));
        entitlementAdminPort.changeTenantPlan(tenantId, planCode);
        recordPlatformAudit(actor, "TENANT_PLAN_CHANGE", "Tenant", tenantId, Map.of("planCode", planCode.trim().toUpperCase()));
        return listTenants().stream().filter(t -> t.id().equals(tenantId)).findFirst().orElseThrow();
    }

    @Transactional
    public void setFeatureOverride(
            JwtTokenService.PlatformUserPrincipal actor,
            UUID tenantId,
            String featureKey,
            Boolean enabled,
            Long limitValue,
            String reason
    ) {
        entitlementAdminPort.setFeatureOverride(tenantId, featureKey, enabled, limitValue, reason);
        recordPlatformAudit(actor, "TENANT_FEATURE_OVERRIDE", "Tenant", tenantId, Map.of(
                "featureKey", featureKey.trim().toUpperCase(),
                "enabled", String.valueOf(enabled),
                "limitValue", String.valueOf(limitValue)
        ));
    }

    @Transactional(readOnly = true)
    public List<EntitlementAdminPort.PlanView> listPlans() {
        return entitlementAdminPort.listPlans();
    }

    @Transactional
    public EntitlementAdminPort.PlanView updatePlanFeature(
            JwtTokenService.PlatformUserPrincipal actor,
            UUID planId,
            String featureKey,
            boolean enabled,
            Long limitValue
    ) {
        EntitlementAdminPort.PlanView updated = entitlementAdminPort.updatePlanFeature(planId, featureKey, enabled, limitValue);
        recordPlatformAudit(actor, "PLAN_FEATURE_UPDATE", "Plan", planId, Map.of(
                "featureKey", featureKey.trim().toUpperCase(),
                "enabled", enabled
        ));
        return updated;
    }

    public TenantUsageSnapshot captureUsageSnapshot(JwtTokenService.PlatformUserPrincipal actor, UUID tenantId) {
        tenantReadPort.findById(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found"));
        LocalDate today = LocalDate.now();
        long usersCount = tenantReadPort.countUsersForTenant(tenantId);

        long[] counts = new long[3];
        TenantContext.runAs(tenantId, () -> {
            counts[0] = catalogReadPort.countParts();
            counts[1] = stockReadPort.countLocations();
            counts[2] = stockReadPort.countStockItems();
        });

        TenantUsageSnapshot snap = usageSnapshotRepository.findByTenantIdAndSnapshotDate(tenantId, today)
                .orElseGet(() -> {
                    TenantUsageSnapshot s = new TenantUsageSnapshot();
                    s.setTenantId(tenantId);
                    s.setSnapshotDate(today);
                    return s;
                });
        snap.setPartsCount(counts[0]);
        snap.setLocationsCount(counts[1]);
        snap.setUsersCount(usersCount);
        snap.setStockItemsCount(counts[2]);
        TenantUsageSnapshot saved = usageSnapshotRepository.saveAndFlush(snap);
        recordPlatformAudit(actor, "USAGE_SNAPSHOT_CAPTURED", "Tenant", tenantId, Map.of("parts", counts[0], "locations", counts[1]));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<TenantUsageSnapshot> listUsageSnapshots(UUID tenantId) {
        return usageSnapshotRepository.findByTenantIdOrderBySnapshotDateDesc(tenantId);
    }

    public SupportSessionResponse startSupportSession(
            JwtTokenService.PlatformUserPrincipal actor,
            UUID tenantId,
            String reason
    ) {
        if (reason == null || reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Support reason is required");
        }
        TenantReadPort.TenantSummary tenant = tenantReadPort.findById(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found"));
        TenantReadPort.ActiveUserSummary targetUser = tenantReadPort.findFirstActiveUser(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Tenant has no active users"));

        SupportSession session = new SupportSession();
        session.setPlatformUserId(actor.userId());
        session.setTargetTenantId(tenantId);
        session.setReason(reason.trim());
        session.setExpiresAt(Instant.now().plusSeconds(30 * 60));
        SupportSession saved = supportSessionRepository.saveAndFlush(session);

        String impersonationToken = jwtTokenService.createTenantAccessToken(
                targetUser.id(),
                tenantId,
                targetUser.email(),
                targetUser.fullName() + " [Support: " + actor.email() + "]",
                Set.of("ADMIN:VIEW", "PART:VIEW", "STOCK:VIEW", "ORDER:VIEW", "BUILD:VIEW", "COMPANY:VIEW")
        );

        TenantContext.runAs(tenantId, () -> {
            AuditContext.Snapshot snap = new AuditContext.Snapshot(
                    AuditContext.ActorType.PLATFORM_SUPPORT,
                    actor.userId(),
                    actor.email(),
                    null,
                    saved.getId().toString(),
                    "127.0.0.1"
            );
            AuditContext.runWith(snap, () -> auditService.record(
                    "SupportSession",
                    saved.getId(),
                    "PLATFORM_SUPPORT_START",
                    Map.of("platformActor", actor.email(), "reason", reason.trim()),
                    reason.trim()
            ));
        });

        recordPlatformAudit(actor, "SUPPORT_SESSION_START", "Tenant", tenantId, Map.of("sessionId", saved.getId().toString(), "reason", reason.trim()));
        return new SupportSessionResponse(saved.getId(), tenantId, tenant.slug(), impersonationToken, saved.getExpiresAt(), saved.getReason());
    }

    @Transactional(readOnly = true)
    public List<OutboxEvent> listDeadLetterOutbox() {
        return outboxEventRepository.findAll().stream()
                .filter(e -> !"PROCESSED".equals(e.getStatus()))
                .toList();
    }

    @Transactional
    public OutboxEvent retryOutboxEvent(JwtTokenService.PlatformUserPrincipal actor, UUID eventId) {
        OutboxEvent event = outboxEventRepository.findById(eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Outbox event not found"));
        event.setStatus("PENDING");
        event.setNextAttemptAt(Instant.now());
        OutboxEvent saved = outboxEventRepository.saveAndFlush(event);
        recordPlatformAudit(actor, "OUTBOX_RETRY", "OutboxEvent", eventId, Map.of("eventType", event.getEventType()));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<PlatformAuditLog> listAuditLogs() {
        return platformAuditLogRepository.findTop100ByOrderByOccurredAtDesc();
    }

    private void recordPlatformAudit(
            JwtTokenService.PlatformUserPrincipal actor,
            String action,
            String targetType,
            UUID targetId,
            Map<String, Object> details
    ) {
        PlatformAuditLog log = new PlatformAuditLog();
        log.setPlatformUserId(actor != null ? actor.userId() : null);
        log.setActorEmail(actor != null ? actor.email() : "SYSTEM");
        log.setAction(action);
        log.setTargetType(targetType);
        log.setTargetId(targetId);
        log.setDetails(details != null ? details : Map.of());
        platformAuditLogRepository.save(log);
    }
}
