package com.ims.tenant;

import com.ims.common.audit.AuditEvent;
import com.ims.common.audit.AuditService;
import com.ims.common.entitlement.EntitlementService;
import com.ims.common.entitlement.Feature;
import com.ims.common.entitlement.RequiresFeature;
import com.ims.common.io.ImportExportService;
import com.ims.common.sequence.ReferenceSequence;
import com.ims.common.sequence.ReferenceSequenceService;
import com.ims.config.security.JwtTokenService;
import com.ims.config.security.TenantAuthAndContextFilter;
import com.ims.config.security.TenantUserPrincipal;
import com.ims.config.tenant.TenantContext;
import com.ims.part.PartService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Manages tenant-level administration: /api/v1/me profile, Organization profile, Users & Invitations,
 * Roles & Permissions (filtered by enabled features), API Tokens (gated by @RequiresFeature(Feature.API_ACCESS)),
 * Reference Sequences, TenantSettings, and Audit Log queries/exports.
 */
@Service
public class TenantManagementService {

    public record MeResponse(
            UUID userId,
            String email,
            String fullName,
            UUID tenantId,
            String tenantName,
            String tenantSlug,
            String baseCurrency,
            String timezone,
            Set<String> permissions,
            String planCode,
            String planName,
            String subscriptionStatus,
            Instant subscriptionEndsAt,
            Set<String> enabledFeatures,
            Map<String, Long> limits,
            Map<String, Long> usage,
            List<EntitlementService.EffectiveFeature> featureDetails
    ) {}

    public record UserWithRolesView(
            UUID id,
            String email,
            String fullName,
            String status,
            Instant lastLoginAt,
            Instant createdAt,
            List<RoleSummary> roles
    ) {}

    public record RoleSummary(UUID id, String name) {}

    public record InviteUserCommand(String email, String fullName, List<UUID> roleIds) {}

    public record InviteUserResult(UserWithRolesView user, String inviteToken) {}

    public record CreateApiTokenCommand(String name, List<String> scopes, Instant expiresAt) {}

    public record CreatedApiTokenResponse(
            UUID id,
            String name,
            String tokenPrefix,
            String rawToken,
            List<String> scopes,
            Instant expiresAt,
            Instant createdAt
    ) {}

    private final TenantRepository tenantRepository;
    private final UserAccountRepository userAccountRepository;
    private final RoleRepository roleRepository;
    private final UserRoleAssignmentRepository userRoleAssignmentRepository;
    private final ApiTokenRepository apiTokenRepository;
    private final TenantSettingRepository tenantSettingRepository;
    private final ReferenceSequenceService referenceSequenceService;
    private final EntitlementService entitlementService;
    private final AuditService auditService;
    private final ImportExportService importExportService;
    private final PasswordEncoder passwordEncoder;
    private final PartService partService;

    public TenantManagementService(
            TenantRepository tenantRepository,
            UserAccountRepository userAccountRepository,
            RoleRepository roleRepository,
            UserRoleAssignmentRepository userRoleAssignmentRepository,
            ApiTokenRepository apiTokenRepository,
            TenantSettingRepository tenantSettingRepository,
            ReferenceSequenceService referenceSequenceService,
            EntitlementService entitlementService,
            AuditService auditService,
            ImportExportService importExportService,
            PasswordEncoder passwordEncoder,
            PartService partService
    ) {
        this.tenantRepository = tenantRepository;
        this.userAccountRepository = userAccountRepository;
        this.roleRepository = roleRepository;
        this.userRoleAssignmentRepository = userRoleAssignmentRepository;
        this.apiTokenRepository = apiTokenRepository;
        this.tenantSettingRepository = tenantSettingRepository;
        this.referenceSequenceService = referenceSequenceService;
        this.entitlementService = entitlementService;
        this.auditService = auditService;
        this.importExportService = importExportService;
        this.passwordEncoder = passwordEncoder;
        this.partService = partService;
    }

    @Transactional(readOnly = true)
    public MeResponse getMe(TenantUserPrincipal principal) {
        UUID tenantId = TenantContext.require();
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found"));
        UserAccount user = userAccountRepository.findByTenantIdAndId(tenantId, principal.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        EntitlementService.EffectiveEntitlements entitlements = entitlementService.getEffectiveForCurrentTenant();

        long activeUsers = userAccountRepository.countByTenantIdAndStatusNot(tenantId, UserAccount.Status.DISABLED.name());
        long activeTokens = apiTokenRepository.findByTenantIdOrderByCreatedAtDesc(tenantId).stream()
                .filter(t -> !t.isRevoked())
                .count();

        Map<String, Long> usage = new LinkedHashMap<>();
        usage.put(Feature.MAX_USERS.name(), activeUsers);
        usage.put(Feature.MAX_PARTS.name(), partService.countParts());
        usage.put(Feature.MAX_LOCATIONS.name(), 0L);
        usage.put(Feature.MAX_WEBHOOKS.name(), 0L);
        usage.put(Feature.STORAGE_MB.name(), 0L);
        usage.put("ACTIVE_API_TOKENS", activeTokens);

        return new MeResponse(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                tenant.getId(),
                tenant.getName(),
                tenant.getSlug(),
                tenant.getBaseCurrency(),
                tenant.getTimezone(),
                principal.permissions(),
                entitlements.planCode(),
                entitlements.planName(),
                entitlements.subscriptionStatus(),
                entitlements.subscriptionEndsAt(),
                entitlements.enabledFeatureKeys(),
                entitlements.limitsMap(),
                usage,
                List.copyOf(entitlements.features().values())
        );
    }

    @Transactional
    public Tenant updateOrganization(String name, String baseCurrency, String timezone, String logoUrl) {
        UUID tenantId = TenantContext.require();
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found"));

        Map<String, Object> diffs = new LinkedHashMap<>();
        if (name != null && !name.isBlank() && !name.equals(tenant.getName())) {
            diffs.put("name", Map.of("from", tenant.getName(), "to", name.trim()));
            tenant.setName(name.trim());
            upsertSetting("org.name", name.trim(), "STRING");
        }
        if (baseCurrency != null && !baseCurrency.isBlank() && !baseCurrency.equalsIgnoreCase(tenant.getBaseCurrency())) {
            String upper = baseCurrency.trim().toUpperCase(Locale.ROOT);
            diffs.put("baseCurrency", Map.of("from", tenant.getBaseCurrency(), "to", upper));
            tenant.setBaseCurrency(upper);
            upsertSetting("org.base_currency", upper, "STRING");
        }
        if (timezone != null && !timezone.isBlank() && !timezone.equals(tenant.getTimezone())) {
            diffs.put("timezone", Map.of("from", tenant.getTimezone(), "to", timezone.trim()));
            tenant.setTimezone(timezone.trim());
            upsertSetting("org.timezone", timezone.trim(), "STRING");
        }
        if (logoUrl != null) {
            tenant.setLogoUrl(logoUrl.trim());
        }
        tenant.setUpdatedAt(Instant.now());
        Tenant saved = tenantRepository.save(tenant);
        if (!diffs.isEmpty()) {
            auditService.record("Tenant", saved.getId(), "UPDATE", diffs, null);
        }
        return saved;
    }

    @Transactional(readOnly = true)
    public List<UserWithRolesView> listUsers() {
        UUID tenantId = TenantContext.require();
        List<UserAccount> users = userAccountRepository.findByTenantIdOrderByCreatedAtAsc(tenantId);
        List<UserWithRolesView> result = new ArrayList<>();
        for (UserAccount u : users) {
            List<UserRoleAssignment> assignments = userRoleAssignmentRepository.findByUserId(u.getId());
            List<RoleSummary> roles = new ArrayList<>();
            for (UserRoleAssignment a : assignments) {
                roleRepository.findById(a.getRoleId()).ifPresent(r -> roles.add(new RoleSummary(r.getId(), r.getName())));
            }
            result.add(new UserWithRolesView(
                    u.getId(), u.getEmail(), u.getFullName(), u.getStatus(),
                    u.getLastLoginAt(), u.getCreatedAt(), roles
            ));
        }
        return result;
    }

    @Transactional
    public InviteUserResult inviteUser(InviteUserCommand cmd) {
        UUID tenantId = TenantContext.require();
        long currentCount = userAccountRepository.countByTenantIdAndStatusNot(tenantId, UserAccount.Status.DISABLED.name());
        entitlementService.requireWithinLimit(Feature.MAX_USERS, currentCount);

        String email = cmd.email().trim().toLowerCase(Locale.ROOT);
        if (userAccountRepository.existsByEmailIgnoreCase(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email '" + email + "' is already registered.");
        }

        // Validate every roleId belongs to this tenant (via tenant-filtered RoleRepository)
        List<Role> rolesToAssign = new ArrayList<>();
        if (cmd.roleIds() != null) {
            for (UUID roleId : cmd.roleIds()) {
                Role role = roleRepository.findById(roleId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Role not found: " + roleId));
                rolesToAssign.add(role);
            }
        }

        String inviteToken = JwtTokenService.generateRandomToken(24);
        UserAccount invited = new UserAccount();
        invited.setTenantId(tenantId);
        invited.setEmail(email);
        invited.setFullName(cmd.fullName().trim());
        invited.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
        invited.setStatus(UserAccount.Status.INVITED.name());
        invited.setInviteToken(inviteToken);
        invited = userAccountRepository.save(invited);

        List<RoleSummary> assignedSummaries = new ArrayList<>();
        for (Role r : rolesToAssign) {
            UserRoleAssignment assignment = new UserRoleAssignment();
            assignment.setUserId(invited.getId());
            assignment.setRoleId(r.getId());
            userRoleAssignmentRepository.save(assignment);
            assignedSummaries.add(new RoleSummary(r.getId(), r.getName()));
        }

        auditService.record("UserAccount", invited.getId(), "CREATE",
                Map.of("email", invited.getEmail(), "status", invited.getStatus()), "User invited");

        return new InviteUserResult(
                new UserWithRolesView(
                        invited.getId(), invited.getEmail(), invited.getFullName(), invited.getStatus(),
                        invited.getLastLoginAt(), invited.getCreatedAt(), assignedSummaries
                ),
                inviteToken
        );
    }

    @Transactional
    public UserWithRolesView updateUserRoles(UUID userId, List<UUID> roleIds) {
        UUID tenantId = TenantContext.require();
        UserAccount user = userAccountRepository.findByTenantIdAndId(tenantId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        List<Role> verifiedRoles = new ArrayList<>();
        for (UUID roleId : roleIds) {
            Role role = roleRepository.findById(roleId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Role not found: " + roleId));
            verifiedRoles.add(role);
        }

        userRoleAssignmentRepository.deleteByUserId(user.getId());
        userRoleAssignmentRepository.flush();

        List<RoleSummary> summaries = new ArrayList<>();
        for (Role r : verifiedRoles) {
            UserRoleAssignment a = new UserRoleAssignment();
            a.setUserId(user.getId());
            a.setRoleId(r.getId());
            userRoleAssignmentRepository.save(a);
            summaries.add(new RoleSummary(r.getId(), r.getName()));
        }

        auditService.record("UserAccount", user.getId(), "PERMISSION_CHANGED",
                Map.of("roles", summaries.stream().map(RoleSummary::name).toList()), null);

        return new UserWithRolesView(
                user.getId(), user.getEmail(), user.getFullName(), user.getStatus(),
                user.getLastLoginAt(), user.getCreatedAt(), summaries
        );
    }

    @Transactional(readOnly = true)
    public List<Role> listRoles() {
        return roleRepository.findAllByOrderByNameAsc();
    }

    /**
     * Returns only the permissions corresponding to features currently enabled for this tenant.
     */
    @Transactional(readOnly = true)
    public List<String> getAvailablePermissionsForTenant() {
        EntitlementService.EffectiveEntitlements entitlements = entitlementService.getEffectiveForCurrentTenant();
        boolean buildEnabled = entitlements.isEnabled(Feature.MANUFACTURING);
        boolean orderEnabled = entitlements.isEnabled(Feature.PURCHASING)
                || entitlements.isEnabled(Feature.SALES)
                || entitlements.isEnabled(Feature.RETURNS)
                || entitlements.isEnabled(Feature.TRANSFERS);

        List<String> available = new ArrayList<>();
        for (Permission p : Permission.values()) {
            if ("BUILD".equals(p.getDomain()) && !buildEnabled) {
                continue;
            }
            if ("ORDER".equals(p.getDomain()) && !orderEnabled) {
                continue;
            }
            available.add(p.getCode());
        }
        return available;
    }

    @Transactional
    public Role createRole(String name, String description, List<String> permissions) {
        if (roleRepository.findByName(name.trim()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Role '" + name + "' already exists.");
        }
        Set<String> allowed = new LinkedHashSet<>(getAvailablePermissionsForTenant());
        List<String> filtered = permissions.stream().filter(allowed::contains).toList();

        Role role = new Role();
        role.setName(name.trim());
        role.setDescription(description);
        role.setSystemRole(false);
        role.setPermissions(filtered);
        return roleRepository.save(role);
    }

    @Transactional
    public Role updateRole(UUID roleId, String name, String description, List<String> permissions) {
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Role not found"));
        Set<String> allowed = new LinkedHashSet<>(getAvailablePermissionsForTenant());
        List<String> filtered = permissions.stream().filter(allowed::contains).toList();

        if (name != null && !name.isBlank() && !role.isSystemRole()) {
            role.setName(name.trim());
        }
        if (description != null) {
            role.setDescription(description);
        }
        role.setPermissions(filtered);
        Role saved = roleRepository.save(role);
        auditService.record("Role", saved.getId(), "PERMISSION_CHANGED",
                Map.of("roleName", saved.getName(), "permissions", filtered), null);
        return saved;
    }

    @Transactional
    public void deleteRole(UUID roleId) {
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Role not found"));
        if (role.isSystemRole()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "System roles cannot be deleted.");
        }
        roleRepository.delete(role);
    }

    @Transactional(readOnly = true)
    @RequiresFeature(Feature.API_ACCESS)
    public List<ApiToken> listApiTokens() {
        UUID tenantId = TenantContext.require();
        return apiTokenRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    @Transactional
    @RequiresFeature(Feature.API_ACCESS)
    public CreatedApiTokenResponse createApiToken(UUID userId, CreateApiTokenCommand cmd) {
        UUID tenantId = TenantContext.require();
        String randomSecret = JwtTokenService.generateRandomToken(28);
        String rawToken = TenantAuthAndContextFilter.API_TOKEN_PREFIX + randomSecret;
        String prefix = rawToken.substring(0, Math.min(16, rawToken.length()));

        Set<String> allowed = new LinkedHashSet<>(getAvailablePermissionsForTenant());
        List<String> scopes = (cmd.scopes() == null || cmd.scopes().isEmpty())
                ? List.copyOf(allowed)
                : cmd.scopes().stream().filter(allowed::contains).toList();

        ApiToken token = new ApiToken();
        token.setTenantId(tenantId);
        token.setUserId(userId);
        token.setName(cmd.name().trim());
        token.setTokenPrefix(prefix);
        token.setTokenHash(JwtTokenService.sha256Hex(rawToken));
        token.setScopes(String.join(",", scopes));
        token.setExpiresAt(cmd.expiresAt());
        token = apiTokenRepository.save(token);

        auditService.record("ApiToken", token.getId(), "TOKEN_CREATED",
                Map.of("name", token.getName(), "prefix", token.getTokenPrefix(), "scopes", scopes), null);

        return new CreatedApiTokenResponse(
                token.getId(),
                token.getName(),
                token.getTokenPrefix(),
                rawToken,
                scopes,
                token.getExpiresAt(),
                token.getCreatedAt()
        );
    }

    @Transactional
    @RequiresFeature(Feature.API_ACCESS)
    public void revokeApiToken(UUID tokenId) {
        UUID tenantId = TenantContext.require();
        ApiToken token = apiTokenRepository.findByTenantIdAndId(tenantId, tokenId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "API token not found"));
        token.setRevoked(true);
        apiTokenRepository.save(token);
        auditService.record("ApiToken", token.getId(), "TOKEN_REVOKED",
                Map.of("name", token.getName(), "prefix", token.getTokenPrefix()), null);
    }

    @Transactional(readOnly = true)
    public List<TenantSetting> listSettings() {
        return tenantSettingRepository.findAllByOrderBySettingKeyAsc();
    }

    @Transactional
    public TenantSetting upsertSetting(String key, String value, String valueType) {
        TenantSetting setting = tenantSettingRepository.findBySettingKey(key)
                .orElseGet(() -> {
                    TenantSetting s = new TenantSetting();
                    s.setSettingKey(key);
                    s.setValueType(valueType != null ? valueType : "STRING");
                    return s;
                });
        setting.setSettingValue(value);
        if (valueType != null) {
            setting.setValueType(valueType);
        }
        return tenantSettingRepository.save(setting);
    }

    @Transactional(readOnly = true)
    public List<ReferenceSequence> listReferenceSequences() {
        return referenceSequenceService.listAll();
    }

    @Transactional
    public ReferenceSequence updateReferenceSequence(String sequenceKey, String prefix, int padding, long nextValue) {
        return referenceSequenceService.updateFormat(sequenceKey, prefix, padding, nextValue);
    }

    @Transactional(readOnly = true)
    public Page<AuditEvent> searchAuditEvents(
            String entityType,
            UUID entityId,
            UUID actorId,
            String action,
            Instant from,
            Instant to,
            Pageable pageable
    ) {
        return auditService.search(entityType, entityId, actorId, action, from, to, pageable);
    }

    @Transactional
    public byte[] exportAuditEventsCsv(String entityType, UUID entityId, UUID actorId, String action, Instant from, Instant to) {
        Page<AuditEvent> page = auditService.search(entityType, entityId, actorId, action, from, to, Pageable.ofSize(1000));
        List<String> headers = List.of("Occurred At", "Actor Type", "Actor", "Action", "Entity Type", "Entity ID", "Support Session", "Changes");
        List<List<String>> rows = page.getContent().stream()
                .map(e -> List.of(
                        e.getOccurredAt().toString(),
                        e.getActorType(),
                        e.getActorName() != null ? e.getActorName() : "",
                        e.getAction(),
                        e.getEntityType(),
                        e.getEntityId() != null ? e.getEntityId().toString() : "",
                        e.getSupportSessionId() != null ? e.getSupportSessionId().toString() : "",
                        e.getChanges().toString()
                ))
                .toList();
        return importExportService.exportCsv(headers, rows, "AuditEvent");
    }
}
