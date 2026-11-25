package com.ims.identity.application;

import com.ims.common.audit.AuditContext;
import com.ims.common.events.TenantRegisteredEvent;
import com.ims.common.outbox.OutboxWriter;
import com.ims.common.sequence.ReferenceSequenceService;
import com.ims.config.tenant.TenantContext;
import com.ims.entitlement.spi.EntitlementAdminPort;
import com.ims.identity.domain.model.Permission;
import com.ims.identity.domain.model.Role;
import com.ims.identity.domain.model.Tenant;
import com.ims.identity.domain.model.TenantSetting;
import com.ims.identity.domain.model.UserAccount;
import com.ims.identity.domain.model.UserRoleAssignment;
import com.ims.identity.infrastructure.persistence.RoleRepository;
import com.ims.identity.infrastructure.persistence.TenantRepository;
import com.ims.identity.infrastructure.persistence.TenantSettingRepository;
import com.ims.identity.infrastructure.persistence.UserAccountRepository;
import com.ims.identity.infrastructure.persistence.UserRoleAssignmentRepository;
import com.ims.identity.spi.TenantBootstrapContributor;
import com.ims.identity.spi.TenantProvisioningPort;
import com.ims.shared.exception.ConflictException;
import com.ims.shared.exception.ForbiddenException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class TenantProvisioningService implements TenantProvisioningPort {

    private final TenantRepository tenantRepository;
    private final UserAccountRepository userAccountRepository;
    private final RoleRepository roleRepository;
    private final UserRoleAssignmentRepository userRoleAssignmentRepository;
    private final TenantSettingRepository tenantSettingRepository;
    private final ReferenceSequenceService referenceSequenceService;
    private final EntitlementAdminPort entitlementAdminPort;
    private final AuthenticationService authenticationService;
    private final OutboxWriter outboxWriter;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transactionTemplate;
    private final List<TenantBootstrapContributor> bootstrapContributors;
    private final boolean publicSignupEnabled;

    public TenantProvisioningService(
            TenantRepository tenantRepository,
            UserAccountRepository userAccountRepository,
            RoleRepository roleRepository,
            UserRoleAssignmentRepository userRoleAssignmentRepository,
            TenantSettingRepository tenantSettingRepository,
            ReferenceSequenceService referenceSequenceService,
            EntitlementAdminPort entitlementAdminPort,
            AuthenticationService authenticationService,
            OutboxWriter outboxWriter,
            PasswordEncoder passwordEncoder,
            TransactionTemplate transactionTemplate,
            List<TenantBootstrapContributor> bootstrapContributors,
            @Value("${ims.signup.enabled:true}") boolean publicSignupEnabled
    ) {
        this.tenantRepository = tenantRepository;
        this.userAccountRepository = userAccountRepository;
        this.roleRepository = roleRepository;
        this.userRoleAssignmentRepository = userRoleAssignmentRepository;
        this.tenantSettingRepository = tenantSettingRepository;
        this.referenceSequenceService = referenceSequenceService;
        this.entitlementAdminPort = entitlementAdminPort;
        this.authenticationService = authenticationService;
        this.outboxWriter = outboxWriter;
        this.passwordEncoder = passwordEncoder;
        this.transactionTemplate = transactionTemplate;
        this.bootstrapContributors = bootstrapContributors;
        this.publicSignupEnabled = publicSignupEnabled;
    }

    @Override
    public AuthTokensResponse registerTenant(ProvisionTenantCommand cmd, boolean bypassSignupGate) {
        if (!bypassSignupGate && !publicSignupEnabled) {
            throw new ForbiddenException("Public tenant self-signup is disabled.");
        }
        String normalizedSlug = cmd.tenantSlug().trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-");
        String normalizedEmail = cmd.adminEmail().trim().toLowerCase(Locale.ROOT);

        if (tenantRepository.existsBySlug(normalizedSlug)) {
            throw new ConflictException("Tenant slug '" + normalizedSlug + "' is already taken.");
        }
        if (userAccountRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new ConflictException("Email '" + normalizedEmail + "' is already registered.");
        }

        Tenant tenant = new Tenant();
        tenant.setName(cmd.tenantName().trim());
        tenant.setSlug(normalizedSlug);
        tenant.setStatus(Tenant.Status.ACTIVE.name());
        tenant.setBaseCurrency(cmd.baseCurrency() != null && !cmd.baseCurrency().isBlank() ? cmd.baseCurrency().trim().toUpperCase(Locale.ROOT) : "USD");
        tenant.setTimezone(cmd.timezone() != null && !cmd.timezone().isBlank() ? cmd.timezone().trim() : "UTC");
        final Tenant savedTenant = tenantRepository.save(tenant);

        final String assignedPlanCode = entitlementAdminPort.assignInitialPlan(savedTenant.getId(), cmd.planCode());

        UserAccount admin = new UserAccount();
        admin.setTenantId(savedTenant.getId());
        admin.setEmail(normalizedEmail);
        admin.setFullName(cmd.adminFullName().trim());
        admin.setPasswordHash(passwordEncoder.encode(cmd.adminPassword()));
        admin.setStatus(UserAccount.Status.ACTIVE.name());
        admin.setLastLoginAt(Instant.now());
        final UserAccount savedAdmin = userAccountRepository.save(admin);

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

                for (TenantBootstrapContributor contributor : bootstrapContributors) {
                    contributor.seedDefaults(savedTenant.getId(), savedTenant.getName(), savedTenant.getBaseCurrency(), savedTenant.getTimezone());
                }

                outboxWriter.enqueue(new TenantRegisteredEvent(
                        savedTenant.getId(), savedTenant.getSlug(), assignedPlanCode, Instant.now()
                ));
            }));
        });

        return authenticationService.issueTokensForUser(savedAdmin, savedTenant);
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
}
