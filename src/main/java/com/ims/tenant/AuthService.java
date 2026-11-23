package com.ims.tenant;

import com.ims.common.audit.AuditContext;
import com.ims.common.audit.AuditService;
import com.ims.common.entitlement.EntitlementService;
import com.ims.common.entitlement.Feature;
import com.ims.common.entitlement.Plan;
import com.ims.common.entitlement.PlanFeature;
import com.ims.common.entitlement.PlanFeatureRepository;
import com.ims.common.entitlement.PlanRepository;
import com.ims.common.entitlement.TenantSubscription;
import com.ims.common.entitlement.TenantSubscriptionRepository;
import com.ims.common.events.TenantRegisteredEvent;
import com.ims.common.outbox.OutboxWriter;
import com.ims.common.sequence.ReferenceSequenceService;
import com.ims.config.security.JwtTokenService;
import com.ims.config.tenant.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Handles tenant provisioning, login, refresh-token rotation, logout, password reset, and user invitations.
 */
@Service
public class AuthService {

    public record RegisterTenantCommand(
            String tenantName,
            String tenantSlug,
            String adminEmail,
            String adminFullName,
            String adminPassword,
            String baseCurrency,
            String timezone,
            String planCode
    ) {}

    public record AuthTokensResponse(
            String accessToken,
            String refreshToken,
            long expiresInSeconds,
            UUID userId,
            UUID tenantId,
            String tenantName,
            String tenantSlug,
            String email,
            String fullName,
            Set<String> permissions
    ) {}

    private static final Duration REFRESH_TOKEN_TTL = Duration.ofDays(14);

    private final TenantRepository tenantRepository;
    private final UserAccountRepository userAccountRepository;
    private final RoleRepository roleRepository;
    private final UserRoleAssignmentRepository userRoleAssignmentRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PlanRepository planRepository;
    private final PlanFeatureRepository planFeatureRepository;
    private final TenantSubscriptionRepository subscriptionRepository;
    private final TenantSettingRepository tenantSettingRepository;
    private final ReferenceSequenceService referenceSequenceService;
    private final EntitlementService entitlementService;
    private final AuditService auditService;
    private final OutboxWriter outboxWriter;
    private final JwtTokenService jwtTokenService;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transactionTemplate;
    private final boolean publicSignupEnabled;

    public AuthService(
            TenantRepository tenantRepository,
            UserAccountRepository userAccountRepository,
            RoleRepository roleRepository,
            UserRoleAssignmentRepository userRoleAssignmentRepository,
            RefreshTokenRepository refreshTokenRepository,
            PlanRepository planRepository,
            PlanFeatureRepository planFeatureRepository,
            TenantSubscriptionRepository subscriptionRepository,
            TenantSettingRepository tenantSettingRepository,
            ReferenceSequenceService referenceSequenceService,
            EntitlementService entitlementService,
            AuditService auditService,
            OutboxWriter outboxWriter,
            JwtTokenService jwtTokenService,
            PasswordEncoder passwordEncoder,
            TransactionTemplate transactionTemplate,
            @Value("${ims.signup.enabled:true}") boolean publicSignupEnabled
    ) {
        this.tenantRepository = tenantRepository;
        this.userAccountRepository = userAccountRepository;
        this.roleRepository = roleRepository;
        this.userRoleAssignmentRepository = userRoleAssignmentRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.planRepository = planRepository;
        this.planFeatureRepository = planFeatureRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.tenantSettingRepository = tenantSettingRepository;
        this.referenceSequenceService = referenceSequenceService;
        this.entitlementService = entitlementService;
        this.auditService = auditService;
        this.outboxWriter = outboxWriter;
        this.jwtTokenService = jwtTokenService;
        this.passwordEncoder = passwordEncoder;
        this.transactionTemplate = transactionTemplate;
        this.publicSignupEnabled = publicSignupEnabled;
    }

    public AuthTokensResponse registerTenant(RegisterTenantCommand cmd, boolean bypassSignupGate) {
        if (!bypassSignupGate && !publicSignupEnabled) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Public tenant self-signup is disabled.");
        }
        String normalizedSlug = cmd.tenantSlug().trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-");
        String normalizedEmail = cmd.adminEmail().trim().toLowerCase(Locale.ROOT);

        if (tenantRepository.existsBySlug(normalizedSlug)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Tenant slug '" + normalizedSlug + "' is already taken.");
        }
        if (userAccountRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email '" + normalizedEmail + "' is already registered.");
        }

        ensureDefaultPlansSeeded();
        String requestedPlanCode = (cmd.planCode() != null && !cmd.planCode().isBlank())
                ? cmd.planCode().trim().toUpperCase(Locale.ROOT)
                : "TRIAL";
        Plan plan = planRepository.findByCode(requestedPlanCode)
                .orElseGet(() -> planRepository.findByCode("TRIAL").orElseThrow());

        // Step 1: Create Global Tenant, Subscription & Admin UserAccount
        Tenant tenant = new Tenant();
        tenant.setName(cmd.tenantName().trim());
        tenant.setSlug(normalizedSlug);
        tenant.setStatus(Tenant.Status.ACTIVE.name());
        tenant.setBaseCurrency(cmd.baseCurrency() != null && !cmd.baseCurrency().isBlank() ? cmd.baseCurrency().trim().toUpperCase(Locale.ROOT) : "USD");
        tenant.setTimezone(cmd.timezone() != null && !cmd.timezone().isBlank() ? cmd.timezone().trim() : "UTC");
        final Tenant savedTenant = tenantRepository.save(tenant);

        TenantSubscription sub = new TenantSubscription();
        sub.setTenantId(savedTenant.getId());
        sub.setPlanId(plan.getId());
        sub.setStatus("TRIAL".equals(plan.getCode()) ? "TRIAL" : "ACTIVE");
        sub.setStartsAt(Instant.now());
        if ("TRIAL".equals(plan.getCode())) {
            sub.setEndsAt(Instant.now().plus(Duration.ofDays(30)));
        }
        subscriptionRepository.save(sub);

        UserAccount admin = new UserAccount();
        admin.setTenantId(savedTenant.getId());
        admin.setEmail(normalizedEmail);
        admin.setFullName(cmd.adminFullName().trim());
        admin.setPasswordHash(passwordEncoder.encode(cmd.adminPassword()));
        admin.setStatus(UserAccount.Status.ACTIVE.name());
        admin.setLastLoginAt(Instant.now());
        final UserAccount savedAdmin = userAccountRepository.save(admin);

        // Step 2: Inside TenantContext.runAs(newTenantId), seed default roles, reference sequences, settings, outbox & audit
        TenantContext.runAs(savedTenant.getId(), () -> {
            AuditContext.Snapshot adminAudit = new AuditContext.Snapshot(
                    AuditContext.ActorType.USER, savedAdmin.getId(), savedAdmin.getEmail(), null, UUID.randomUUID().toString(), null
            );
            AuditContext.runWith(adminAudit, () -> transactionTemplate.executeWithoutResult(status -> {
                Role adminRole = new Role();
                adminRole.setName("Administrator");
                adminRole.setDescription("Full administrative access to all enabled features");
                adminRole.setSystemRole(true);
                adminRole.setPermissions(Permission.allCodes());
                adminRole = roleRepository.save(adminRole);

                Role operatorRole = new Role();
                operatorRole.setName("Operator");
                operatorRole.setDescription("Standard warehouse and catalog operations");
                operatorRole.setSystemRole(true);
                operatorRole.setPermissions(List.of(
                        Permission.PART_VIEW.getCode(), Permission.PART_CREATE.getCode(), Permission.PART_UPDATE.getCode(),
                        Permission.STOCK_VIEW.getCode(), Permission.STOCK_CREATE.getCode(), Permission.STOCK_UPDATE.getCode(),
                        Permission.COMPANY_VIEW.getCode(), Permission.BUILD_VIEW.getCode(), Permission.ORDER_VIEW.getCode()
                ));
                roleRepository.save(operatorRole);

                Role viewerRole = new Role();
                viewerRole.setName("Viewer");
                viewerRole.setDescription("Read-only access across domains");
                viewerRole.setSystemRole(true);
                viewerRole.setPermissions(List.of(
                        Permission.PART_VIEW.getCode(), Permission.STOCK_VIEW.getCode(),
                        Permission.COMPANY_VIEW.getCode(), Permission.BUILD_VIEW.getCode(), Permission.ORDER_VIEW.getCode()
                ));
                roleRepository.save(viewerRole);

                UserRoleAssignment assignment = new UserRoleAssignment();
                assignment.setUserId(savedAdmin.getId());
                assignment.setRoleId(adminRole.getId());
                userRoleAssignmentRepository.save(assignment);

                referenceSequenceService.seedDefaultsIfEmpty();
                seedDefaultTenantSettings(savedTenant);

                outboxWriter.enqueue(new TenantRegisteredEvent(
                        savedTenant.getId(), savedTenant.getSlug(), plan.getCode(), Instant.now()
                ));
            }));
        });

        return issueTokensForUser(savedAdmin, savedTenant);
    }

    public AuthTokensResponse login(String email, String password) {
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        Optional<UserAccount> userOpt = userAccountRepository.findByEmailIgnoreCase(normalizedEmail);
        if (userOpt.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password.");
        }
        UserAccount user = userOpt.get();
        Tenant tenant = tenantRepository.findById(user.getTenantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Tenant not found."));

        if (!Tenant.Status.ACTIVE.name().equals(tenant.getStatus())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "TENANT_SUSPENDED");
        }
        if (!UserAccount.Status.ACTIVE.name().equals(user.getStatus())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "User account is not active.");
        }

        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            auditService.recordInNewTransactionForTenant(
                    tenant.getId(),
                    "UserAccount",
                    user.getId(),
                    "LOGIN_FAILED",
                    Map.of("email", user.getEmail()),
                    "Invalid password"
            );
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password.");
        }

        user.setLastLoginAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        userAccountRepository.save(user);

        auditService.recordInNewTransactionForTenant(
                tenant.getId(),
                "UserAccount",
                user.getId(),
                "LOGIN",
                Map.of("email", user.getEmail()),
                null
        );

        return issueTokensForUser(user, tenant);
    }

    public AuthTokensResponse refresh(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token is required.");
        }
        String hash = JwtTokenService.sha256Hex(rawRefreshToken);
        RefreshToken existing = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token."));

        if (existing.isRevoked() || existing.getExpiresAt().isBefore(Instant.now())) {
            // Possible token reuse: revoke all active refresh tokens for this user
            for (RefreshToken token : refreshTokenRepository.findByUserIdAndRevokedFalse(existing.getUserId())) {
                token.setRevoked(true);
                refreshTokenRepository.save(token);
            }
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token is expired or already used.");
        }

        // Rotate refresh token
        existing.setRevoked(true);
        refreshTokenRepository.save(existing);

        UserAccount user = userAccountRepository.findById(existing.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found."));
        Tenant tenant = tenantRepository.findById(user.getTenantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Tenant not found."));

        if (!Tenant.Status.ACTIVE.name().equals(tenant.getStatus())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "TENANT_SUSPENDED");
        }
        return issueTokensForUser(user, tenant);
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return;
        }
        String hash = JwtTokenService.sha256Hex(rawRefreshToken);
        refreshTokenRepository.findByTokenHash(hash).ifPresent(rt -> {
            rt.setRevoked(true);
            refreshTokenRepository.save(rt);
            auditService.recordInNewTransactionForTenant(
                    rt.getTenantId(),
                    "UserAccount",
                    rt.getUserId(),
                    "LOGOUT",
                    Map.of(),
                    null
            );
        });
    }

    public AuthTokensResponse acceptInvite(String inviteToken, String fullName, String password) {
        UserAccount user = userAccountRepository.findByInviteToken(inviteToken)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invalid or expired invitation token."));
        Tenant tenant = tenantRepository.findById(user.getTenantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found."));

        user.setFullName(fullName.trim());
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setStatus(UserAccount.Status.ACTIVE.name());
        user.setInviteToken(null);
        user.setLastLoginAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        userAccountRepository.save(user);

        return issueTokensForUser(user, tenant);
    }

    @Transactional
    public String requestPasswordReset(String email) {
        Optional<UserAccount> userOpt = userAccountRepository.findByEmailIgnoreCase(email.trim());
        if (userOpt.isEmpty()) {
            return null;
        }
        UserAccount user = userOpt.get();
        String token = JwtTokenService.generateRandomToken(24);
        user.setResetToken(token);
        user.setResetExpiresAt(Instant.now().plus(Duration.ofHours(1)));
        userAccountRepository.save(user);
        return token;
    }

    @Transactional
    public void resetPassword(String resetToken, String newPassword) {
        UserAccount user = userAccountRepository.findByResetToken(resetToken)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid password reset token."));
        if (user.getResetExpiresAt() == null || user.getResetExpiresAt().isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password reset token has expired.");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setResetToken(null);
        user.setResetExpiresAt(null);
        user.setUpdatedAt(Instant.now());
        userAccountRepository.save(user);

        auditService.recordInNewTransactionForTenant(
                user.getTenantId(),
                "UserAccount",
                user.getId(),
                "PASSWORD_RESET",
                Map.of("email", user.getEmail()),
                null
        );
    }

    public Set<String> resolveEffectivePermissions(UUID tenantId, UUID userId) {
        TransactionTemplate newTx = new TransactionTemplate(transactionTemplate.getTransactionManager());
        newTx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        newTx.setReadOnly(true);
        return TenantContext.callUncheckedAs(tenantId, () ->
                newTx.execute(status -> {
                    List<UserRoleAssignment> assignments = userRoleAssignmentRepository.findByUserId(userId);
                    Set<String> perms = new LinkedHashSet<>();
                    for (UserRoleAssignment a : assignments) {
                        roleRepository.findById(a.getRoleId()).ifPresent(role -> perms.addAll(role.getPermissions()));
                    }
                    return perms;
                })
        );
    }

    private AuthTokensResponse issueTokensForUser(UserAccount user, Tenant tenant) {
        Set<String> permissions = resolveEffectivePermissions(tenant.getId(), user.getId());
        String accessToken = jwtTokenService.createTenantAccessToken(
                user.getId(),
                tenant.getId(),
                user.getEmail(),
                user.getFullName(),
                permissions
        );

        String rawRefreshToken = JwtTokenService.generateRandomToken(32);
        RefreshToken rt = new RefreshToken();
        rt.setUserId(user.getId());
        rt.setTenantId(tenant.getId());
        rt.setTokenHash(JwtTokenService.sha256Hex(rawRefreshToken));
        rt.setExpiresAt(Instant.now().plus(REFRESH_TOKEN_TTL));
        refreshTokenRepository.save(rt);

        return new AuthTokensResponse(
                accessToken,
                rawRefreshToken,
                jwtTokenService.getAccessTokenTtlSeconds(),
                user.getId(),
                tenant.getId(),
                tenant.getName(),
                tenant.getSlug(),
                user.getEmail(),
                user.getFullName(),
                permissions
        );
    }

    private void seedDefaultTenantSettings(Tenant tenant) {
        String[][] defaults = {
                {"org.name", tenant.getName(), "STRING"},
                {"org.base_currency", tenant.getBaseCurrency(), "STRING"},
                {"org.timezone", tenant.getTimezone(), "STRING"},
                {"org.date_format", "YYYY-MM-DD", "STRING"},
                {"stock.allow_negative", "false", "BOOLEAN"},
                {"audit.retention_days", "730", "INTEGER"}
        };
        for (String[] def : defaults) {
            if (tenantSettingRepository.findBySettingKey(def[0]).isEmpty()) {
                TenantSetting setting = new TenantSetting();
                setting.setSettingKey(def[0]);
                setting.setSettingValue(def[1]);
                setting.setValueType(def[2]);
                tenantSettingRepository.save(setting);
            }
        }
    }

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
}
