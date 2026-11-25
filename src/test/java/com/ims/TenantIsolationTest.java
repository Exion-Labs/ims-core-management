package com.ims;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ims.common.audit.AuditEvent;
import com.ims.common.audit.AuditService;
import com.ims.entitlement.spi.EntitlementFacade;
import com.ims.entitlement.spi.Feature;
import com.ims.entitlement.domain.model.TenantFeatureOverride;
import com.ims.entitlement.infrastructure.persistence.TenantFeatureOverrideRepository;
import com.ims.common.outbox.OutboxDispatcher;
import com.ims.common.outbox.OutboxEvent;
import com.ims.common.outbox.OutboxEventHandler;
import com.ims.common.outbox.OutboxEventRepository;
import com.ims.common.outbox.OutboxWriter;
import com.ims.config.security.TenantAuthAndContextFilter;
import com.ims.config.tenant.TenantContext;
import com.ims.config.tenant.TenantPredicateInspector;
import com.ims.identity.spi.TenantProvisioningPort;
import com.ims.identity.domain.model.Role;
import com.ims.identity.infrastructure.persistence.RoleRepository;
import com.ims.identity.domain.model.TenantSetting;
import com.ims.identity.infrastructure.persistence.TenantSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Milestone 0 Verification Suite using Testcontainers PostgreSQL.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class TenantIsolationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @TestConfiguration
    static class TestOutboxConfig {
        static final AtomicBoolean FAIL_NEXT_SIMULATED_EVENT = new AtomicBoolean(false);
        static final AtomicBoolean HANDLED_SIMULATED_EVENT = new AtomicBoolean(false);

        @Bean
        public OutboxEventHandler simulatedFailureHandler() {
            return new OutboxEventHandler() {
                @Override
                public boolean supports(String eventType) {
                    return "test.simulated".equals(eventType);
                }

                @Override
                public void handle(OutboxEvent event) {
                    // Verify TenantContext is automatically bound to the event's tenant inside OutboxDispatcher
                    assertThat(TenantContext.require()).isEqualTo(event.getTenantId());
                    if (FAIL_NEXT_SIMULATED_EVENT.getAndSet(false)) {
                        throw new RuntimeException("Simulated transient delivery failure");
                    }
                    HANDLED_SIMULATED_EVENT.set(true);
                }
            };
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TenantProvisioningPort TenantProvisioningPort;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private TenantSettingRepository tenantSettingRepository;

    @Autowired
    private AuditService auditService;

    @Autowired
    private OutboxWriter outboxWriter;

    @Autowired
    private OutboxDispatcher outboxDispatcher;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private TenantFeatureOverrideRepository overrideRepository;

    @Autowired
    private EntitlementFacade EntitlementFacade;

    @Autowired
    private TenantPredicateInspector predicateInspector;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Value("${ims.security.proxy-secret}")
    private String proxySecret;

    private TenantProvisioningPort.AuthTokensResponse tenantA;
    private TenantProvisioningPort.AuthTokensResponse tenantB;

    @BeforeEach
    void setUpTenants() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        tenantA = TenantProvisioningPort.registerTenant(new TenantProvisioningPort.ProvisionTenantCommand(
                "Tenant Alpha " + suffix,
                "tenant-a-" + suffix,
                "admin-a-" + suffix + "@example.com",
                "Alpha Admin",
                "Password123!",
                "USD",
                "UTC",
                "STARTER"
        ), true);

        tenantB = TenantProvisioningPort.registerTenant(new TenantProvisioningPort.ProvisionTenantCommand(
                "Tenant Beta " + suffix,
                "tenant-b-" + suffix,
                "admin-b-" + suffix + "@example.com",
                "Beta Admin",
                "Password123!",
                "EUR",
                "UTC",
                "PRO"
        ), true);
    }

    @Test
    void queryWithoutBoundTenantThrowsFast() {
        assertThat(TenantContext.isBound()).isFalse();
        assertThatThrownBy(() -> roleRepository.findAll())
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("without a bound tenant in TenantContext");
    }

    @Test
    void sessionJwtWithoutBffProxySecretIsRejectedWith401() throws Exception {
        mockMvc.perform(get("/api/v1/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantA.accessToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("MISSING_OR_INVALID_PROXY_SECRET"));

        // With valid X-Proxy-Secret, succeeds
        mockMvc.perform(get("/api/v1/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantA.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(tenantA.tenantId().toString()));
    }

    @Test
    void tenantACannotReadUpdateDeleteOrReferenceTenantBEntities() throws Exception {
        // Create a custom role in Tenant B
        MvcResult createRoleB = mockMvc.perform(post("/api/v1/settings/roles")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantB.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "BetaCustomRole",
                                "description", "Owned by Tenant B",
                                "permissions", List.of("PART:VIEW")
                        ))))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode roleBNode = objectMapper.readTree(createRoleB.getResponse().getContentAsString());
        UUID roleBId = UUID.fromString(roleBNode.get("id").asText());

        // 1. Tenant A listing roles never sees Tenant B's role
        MvcResult listA = mockMvc.perform(get("/api/v1/settings/roles")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantA.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(listA.getResponse().getContentAsString()).doesNotContain(roleBId.toString());

        // 2. Tenant A attempting to update Tenant B's role gets 404
        mockMvc.perform(put("/api/v1/settings/roles/" + roleBId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantA.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "HijackedRole",
                                "description", "Attempted cross-tenant mutation",
                                "permissions", List.of("PART:VIEW")
                        ))))
                .andExpect(status().isNotFound());

        // 3. Tenant A attempting to delete Tenant B's role gets 404
        mockMvc.perform(delete("/api/v1/settings/roles/" + roleBId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantA.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret))
                .andExpect(status().isNotFound());

        // 4. Tenant A attempting to attach Tenant B's roleId as a foreign key in user invitation gets 404
        mockMvc.perform(post("/api/v1/settings/users/invite")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantA.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "invited-" + UUID.randomUUID() + "@example.com",
                                "fullName", "Invited User",
                                "roleIds", List.of(roleBId.toString())
                        ))))
                .andExpect(status().isNotFound());
    }

    @Test
    void tenantPredicateInspectorVerifiesEverySqlStatementIncludesTenantId() {
        predicateInspector.startRecording();
        try {
            TenantContext.runAs(tenantA.tenantId(), () -> {
                List<Role> roles = roleRepository.findAllByOrderByNameAsc();
                assertThat(roles).isNotEmpty();
            });
        } finally {
            List<String> statements = predicateInspector.stopRecording();
            assertThat(statements).isNotEmpty();
            assertThat(statements).allMatch(sql -> sql.toLowerCase().contains("tenant_id"));
        }
    }

    @Test
    void auditEventsAreCapturedAtomicallyWithFieldDiffsNotOnRollbackAndIsolatedPerTenant() throws Exception {
        // 1. Update a @Audited entity (TenantSetting) in Tenant A
        TenantContext.runAs(tenantA.tenantId(), () ->
                transactionTemplate.executeWithoutResult(status -> {
                    TenantSetting setting = tenantSettingRepository.findBySettingKey("org.date_format").orElseThrow();
                    setting.setSettingValue("DD/MM/YYYY");
                    tenantSettingRepository.saveAndFlush(setting);
                })
        );

        // Verify AuditEvent was written with before/after diff
        TenantContext.runAs(tenantA.tenantId(), () -> {
            Page<AuditEvent> audits = auditService.search(
                    "TenantSetting", null, null, "UPDATE", null, null, PageRequest.of(0, 10)
            );
            assertThat(audits.getContent()).isNotEmpty();
            AuditEvent latest = audits.getContent().getFirst();
            assertThat(latest.getChanges()).containsKey("settingValue");
        });

        // 2. Roll back a transaction and verify NO audit entry is persisted for the rolled-back mutation
        assertThatThrownBy(() ->
                TenantContext.runAs(tenantA.tenantId(), () ->
                        transactionTemplate.executeWithoutResult(status -> {
                            TenantSetting setting = tenantSettingRepository.findBySettingKey("org.date_format").orElseThrow();
                            setting.setSettingValue("ROLLED_BACK_FORMAT");
                            tenantSettingRepository.saveAndFlush(setting);
                            throw new RuntimeException("Force rollback");
                        })
                )
        ).isInstanceOf(RuntimeException.class);

        TenantContext.runAs(tenantA.tenantId(), () -> {
            Page<AuditEvent> audits = auditService.search(
                    "TenantSetting", null, null, "UPDATE", null, null, PageRequest.of(0, 10)
            );
            assertThat(audits.getContent())
                    .noneMatch(a -> a.getChanges().toString().contains("ROLLED_BACK_FORMAT"));
        });

        // 3. Failed login is recorded in Tenant A's audit log
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", tenantA.email(),
                                "password", "WrongPassword!"
                        ))))
                .andExpect(status().isUnauthorized());

        TenantContext.runAs(tenantA.tenantId(), () -> {
            Page<AuditEvent> failedLogins = auditService.search(
                    "UserAccount", null, null, "LOGIN_FAILED", null, null, PageRequest.of(0, 10)
            );
            assertThat(failedLogins.getContent()).isNotEmpty();
        });

        // 4. Tenant B never sees Tenant A's audit events
        TenantContext.runAs(tenantB.tenantId(), () -> {
            Page<AuditEvent> bFailedLogins = auditService.search(
                    "UserAccount", null, null, "LOGIN_FAILED", null, null, PageRequest.of(0, 10)
            );
            assertThat(bFailedLogins.getContent()).isEmpty();
        });
    }

    @Test
    void featureEntitlementDisableBlocksTenantAWith403WhileTenantBSucceeds() throws Exception {
        // Seed an override disabling API_ACCESS for Tenant A
        TenantFeatureOverride override = new TenantFeatureOverride();
        override.setTenantId(tenantA.tenantId());
        override.setFeatureKey(Feature.API_ACCESS.name());
        override.setEnabled(false);
        override.setReason("Testing feature entitlement gate");
        overrideRepository.save(override);
        EntitlementFacade.evictCache(tenantA.tenantId());

        // Tenant A gets 403 FEATURE_NOT_ENABLED on /api/v1/settings/api-tokens
        mockMvc.perform(get("/api/v1/settings/api-tokens")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantA.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FEATURE_NOT_ENABLED"))
                .andExpect(jsonPath("$.feature").value("API_ACCESS"));

        // Tenant B succeeds with 200 OK
        mockMvc.perform(get("/api/v1/settings/api-tokens")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantB.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret))
                .andExpect(status().isOk());
    }

    @Test
    void transactionalOutboxDispatchesAndRetriesAfterSimulatedFailure() {
        TestOutboxConfig.FAIL_NEXT_SIMULATED_EVENT.set(true);
        TestOutboxConfig.HANDLED_SIMULATED_EVENT.set(false);

        OutboxEvent event = TenantContext.callUncheckedAs(tenantA.tenantId(), () ->
                transactionTemplate.execute(status ->
                        outboxWriter.enqueue("test.simulated", "TestAggregate", UUID.randomUUID(), Map.of("hello", "world"))
                )
        );

        // First dispatch fails and schedules retry
        outboxDispatcher.dispatchBatch();
        OutboxEvent afterFirstAttempt = outboxEventRepository.findById(event.getId()).orElseThrow();
        assertThat(afterFirstAttempt.getAttempts()).isEqualTo(1);
        assertThat(afterFirstAttempt.getStatus()).isEqualTo(OutboxEvent.Status.PENDING.name());
        assertThat(afterFirstAttempt.getLastError()).contains("Simulated transient delivery failure");

        // Fast-forward nextAttemptAt so second dispatch runs immediately
        afterFirstAttempt.setNextAttemptAt(java.time.Instant.now().minusSeconds(5));
        outboxEventRepository.save(afterFirstAttempt);

        outboxDispatcher.dispatchBatch();
        OutboxEvent afterRetry = outboxEventRepository.findById(event.getId()).orElseThrow();
        assertThat(afterRetry.getStatus()).isEqualTo(OutboxEvent.Status.PUBLISHED.name());
        assertThat(afterRetry.getPublishedAt()).isNotNull();
        assertThat(TestOutboxConfig.HANDLED_SIMULATED_EVENT.get()).isTrue();
    }

    @Test
    void exportOpenApiTenantSnapshot() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs/tenant"))
                .andExpect(status().isOk())
                .andReturn();
        String json = result.getResponse().getContentAsString();
        assertThat(json).contains("/api/v1/me");
        Files.writeString(Path.of("openapi-tenant.json"), json);
    }
}
