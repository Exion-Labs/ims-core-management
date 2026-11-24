package com.ims.platform;

import com.ims.common.audit.AuditContext;
import com.ims.common.audit.AuditService;
import com.ims.common.entitlement.EntitlementService;
import com.ims.common.entitlement.Feature;
import com.ims.common.entitlement.Plan;
import com.ims.common.entitlement.PlanFeature;
import com.ims.common.entitlement.PlanFeatureRepository;
import com.ims.common.entitlement.PlanRepository;
import com.ims.common.entitlement.TenantFeatureOverride;
import com.ims.common.entitlement.TenantFeatureOverrideRepository;
import com.ims.common.entitlement.TenantSubscription;
import com.ims.common.entitlement.TenantSubscriptionRepository;
import com.ims.common.outbox.OutboxEvent;
import com.ims.common.outbox.OutboxEventRepository;
import com.ims.config.security.JwtTokenService;
import com.ims.config.tenant.TenantContext;
import com.ims.part.PartService;
import com.ims.stock.StockLocationService;
import com.ims.stock.StockService;
import com.ims.tenant.AuthService;
import com.ims.tenant.Tenant;
import com.ims.tenant.TenantRepository;
import com.ims.tenant.UserAccount;
import com.ims.tenant.UserAccountRepository;
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
    private final TenantRepository tenantRepository;
    private final UserAccountRepository userAccountRepository;
    private final PlanRepository planRepository;
    private final PlanFeatureRepository planFeatureRepository;
    private final TenantSubscriptionRepository subscriptionRepository;
    private final TenantFeatureOverrideRepository overrideRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final EntitlementService entitlementService;
    private final AuditService auditService;
    private final PartService partService;
    private final StockLocationService locationService;
    private final StockService stockService;
    private final JwtTokenService jwtTokenService;
    private final PasswordEncoder passwordEncoder;
    private final AuthService authService;

    public PlatformService(
            PlatformUserRepository platformUserRepository,
            TenantUsageSnapshotRepository usageSnapshotRepository,
            SupportSessionRepository supportSessionRepository,
            PlatformAuditLogRepository platformAuditLogRepository,
            TenantRepository tenantRepository,
            UserAccountRepository userAccountRepository,
            PlanRepository planRepository,
            PlanFeatureRepository planFeatureRepository,
            TenantSubscriptionRepository subscriptionRepository,
            TenantFeatureOverrideRepository overrideRepository,
            OutboxEventRepository outboxEventRepository,
            EntitlementService entitlementService,
            AuditService auditService,
            PartService partService,
            StockLocationService locationService,
            StockService stockService,
            JwtTokenService jwtTokenService,
            PasswordEncoder passwordEncoder,
            AuthService authService
    ) {
        this.platformUserRepository = platformUserRepository;
        this.usageSnapshotRepository = usageSnapshotRepository;
        this.supportSessionRepository = supportSessionRepository;
        this.platformAuditLogRepository = platformAuditLogRepository;
        this.tenantRepository = tenantRepository;
        this.userAccountRepository = userAccountRepository;
        this.planRepository = planRepository;
        this.planFeatureRepository = planFeatureRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.overrideRepository = overrideRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.entitlementService = entitlementService;
        this.auditService = auditService;
        this.partService = partService;
        this.locationService = locationService;
        this.stockService = stockService;
        this.jwtTokenService = jwtTokenService;
        this.passwordEncoder = passwordEncoder;
        this.authService = authService;
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
        return tenantRepository.findAll().stream().map(t -> {
            var sub = subscriptionRepository.findByTenantId(t.getId()).orElse(null);
            Plan plan = sub != null ? planRepository.findById(sub.getPlanId()).orElse(null) : null;
            long userCount = userAccountRepository.findByTenantIdOrderByCreatedAtAsc(t.getId()).size();
            return new TenantSummaryView(
                    t.getId(),
                    t.getName(),
                    t.getSlug(),
                    t.getStatus(),
                    t.getBaseCurrency(),
                    plan != null ? plan.getCode() : "NONE",
                    plan != null ? plan.getName() : "None",
                    userCount,
                    t.getCreatedAt()
            );
        }).toList();
    }

    public TenantSummaryView createTenant(
            JwtTokenService.PlatformUserPrincipal actor,
            AuthService.RegisterTenantCommand cmd
    ) {
        AuthService.AuthTokensResponse tokens = authService.registerTenant(cmd, true);
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
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found"));
        String targetStatus = status.trim().toUpperCase();
        String prev = tenant.getStatus();
        tenant.setStatus(targetStatus);
        tenantRepository.saveAndFlush(tenant);
        recordPlatformAudit(actor, "TENANT_STATUS_CHANGE", "Tenant", tenantId, Map.of("from", prev, "to", targetStatus));
        return listTenants().stream().filter(t -> t.id().equals(tenantId)).findFirst().orElseThrow();
    }

    @Transactional
    public TenantSummaryView changeTenantPlan(JwtTokenService.PlatformUserPrincipal actor, UUID tenantId, String planCode) {
        tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found"));
        Plan plan = planRepository.findByCode(planCode.trim().toUpperCase())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Plan not found: " + planCode));
        TenantSubscription sub = subscriptionRepository.findByTenantId(tenantId)
                .orElseGet(() -> {
                    TenantSubscription s = new TenantSubscription();
                    s.setTenantId(tenantId);
                    return s;
                });
        sub.setPlanId(plan.getId());
        sub.setStatus("ACTIVE");
        subscriptionRepository.saveAndFlush(sub);
        entitlementService.evictCache(tenantId);
        recordPlatformAudit(actor, "TENANT_PLAN_CHANGE", "Tenant", tenantId, Map.of("planCode", plan.getCode()));
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
        Feature feature = Feature.valueOf(featureKey.trim().toUpperCase());
        TenantFeatureOverride ov = overrideRepository.findByTenantIdAndFeatureKey(tenantId, feature.name())
                .orElseGet(() -> {
                    TenantFeatureOverride o = new TenantFeatureOverride();
                    o.setTenantId(tenantId);
                    o.setFeatureKey(feature.name());
                    return o;
                });
        ov.setEnabled(enabled);
        ov.setLimitValue(limitValue);
        ov.setReason(reason != null ? reason : "Platform override");
        overrideRepository.saveAndFlush(ov);
        entitlementService.evictCache(tenantId);
        recordPlatformAudit(actor, "TENANT_FEATURE_OVERRIDE", "Tenant", tenantId, Map.of(
                "featureKey", feature.name(),
                "enabled", String.valueOf(enabled),
                "limitValue", String.valueOf(limitValue)
        ));
    }

    @Transactional(readOnly = true)
    public List<PlanView> listPlans() {
        return planRepository.findAll().stream().map(p -> {
            List<PlanFeatureView> feats = planFeatureRepository.findByPlanId(p.getId()).stream()
                    .map(pf -> new PlanFeatureView(pf.getFeatureKey(), pf.isEnabled(), pf.getLimitValue()))
                    .toList();
            return new PlanView(p.getId(), p.getCode(), p.getName(), p.getDescription(), feats);
        }).toList();
    }

    @Transactional
    public PlanView updatePlanFeature(
            JwtTokenService.PlatformUserPrincipal actor,
            UUID planId,
            String featureKey,
            boolean enabled,
            Long limitValue
    ) {
        planRepository.findById(planId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Plan not found"));
        Feature feature = Feature.valueOf(featureKey.trim().toUpperCase());

        List<PlanFeature> existing = planFeatureRepository.findByPlanId(planId);
        if (!enabled && feature.isCoreAlwaysOn()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Core feature " + feature.name() + " cannot be disabled");
        }
        if (enabled) {
            for (Feature dep : feature.getDependencies()) {
                boolean depEnabled = existing.stream()
                        .anyMatch(pf -> pf.getFeatureKey().equals(dep.name()) && pf.isEnabled());
                if (!depEnabled) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "Feature " + feature.name() + " requires " + dep.name() + " to be enabled on this plan");
                }
            }
        } else {
            for (PlanFeature pf : existing) {
                if (!pf.isEnabled()) continue;
                Feature other = Feature.valueOf(pf.getFeatureKey());
                if (other.getDependencies().contains(feature)) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
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
        recordPlatformAudit(actor, "PLAN_FEATURE_UPDATE", "Plan", planId, Map.of(
                "featureKey", feature.name(),
                "enabled", enabled
        ));
        return listPlans().stream().filter(p -> p.id().equals(planId)).findFirst().orElseThrow();
    }

    public TenantUsageSnapshot captureUsageSnapshot(JwtTokenService.PlatformUserPrincipal actor, UUID tenantId) {
        tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found"));
        LocalDate today = LocalDate.now();
        long usersCount = userAccountRepository.findByTenantIdOrderByCreatedAtAsc(tenantId).size();

        long[] counts = new long[3];
        TenantContext.runAs(tenantId, () -> {
            counts[0] = partService.searchParts(
                    new PartService.PartSearchFilter(null, null, false, null, null, null, null, null, null, null, null, null, null, null, null, null),
                    PageRequest.of(0, 1)
            ).getTotalElements();
            counts[1] = locationService.listLocations().size();
            counts[2] = stockService.searchStockItems(
                    new StockService.StockSearchFilter(null, null, false, null, null, null, null),
                    PageRequest.of(0, 1)
            ).getTotalElements();
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
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found"));
        UserAccount targetUser = userAccountRepository.findByTenantIdOrderByCreatedAtAsc(tenantId).stream()
                .filter(u -> "ACTIVE".equals(u.getStatus()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Tenant has no active users"));

        SupportSession session = new SupportSession();
        session.setPlatformUserId(actor.userId());
        session.setTargetTenantId(tenantId);
        session.setReason(reason.trim());
        session.setExpiresAt(Instant.now().plusSeconds(30 * 60));
        SupportSession saved = supportSessionRepository.saveAndFlush(session);

        String impersonationToken = jwtTokenService.createTenantAccessToken(
                targetUser.getId(),
                tenantId,
                targetUser.getEmail(),
                targetUser.getFullName() + " [Support: " + actor.email() + "]",
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
        return new SupportSessionResponse(saved.getId(), tenantId, tenant.getSlug(), impersonationToken, saved.getExpiresAt(), saved.getReason());
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
