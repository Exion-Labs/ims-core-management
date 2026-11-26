package com.ims;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ims.common.idempotency.IdempotencyCleanupJob;
import com.ims.common.idempotency.IdempotencyProperties;
import com.ims.common.idempotency.IdempotencyRecordRepository;
import com.ims.config.security.TenantAuthAndContextFilter;
import com.ims.config.tenant.TenantContext;
import com.ims.identity.spi.TenantProvisioningPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Verifies the default-on Idempotency-Key layer: replay, key reuse, concurrency, rollback on failure,
 * tenant isolation, expiry/cleanup, header policy and exemptions.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class IdempotencyIntegrationTest {

    private static final String KEY_HEADER = "Idempotency-Key";
    private static final String REPLAYED_HEADER = "Idempotent-Replayed";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TenantProvisioningPort tenantProvisioningPort;

    @Autowired
    private IdempotencyRecordRepository recordRepository;

    @Autowired
    private IdempotencyProperties properties;

    @Autowired
    private IdempotencyCleanupJob cleanupJob;

    @Value("${ims.security.proxy-secret}")
    private String proxySecret;

    private TenantProvisioningPort.AuthTokensResponse tenantA;
    private TenantProvisioningPort.AuthTokensResponse tenantB;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        tenantA = register("Idem Alpha " + suffix, "idem-a-" + suffix);
        tenantB = register("Idem Beta " + suffix, "idem-b-" + suffix);
    }

    private TenantProvisioningPort.AuthTokensResponse register(String name, String slug) {
        return tenantProvisioningPort.registerTenant(new TenantProvisioningPort.ProvisionTenantCommand(
                name, slug, slug + "@example.com", "Owner " + slug, "Password123!", "USD", "UTC", "ENTERPRISE"
        ), true);
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    void sameKeyAndBodyReplaysStoredResponseWithoutReExecuting() throws Exception {
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = partBody("IDEM-REPLAY-" + key.substring(0, 6));

        MvcResult first = postJson(tenantA, "/api/v1/parts", body, key);
        MvcResult second = postJson(tenantA, "/api/v1/parts", body, key);

        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(second.getResponse().getStatus()).isEqualTo(201);
        assertThat(first.getResponse().getHeader(REPLAYED_HEADER)).isNull();
        assertThat(second.getResponse().getHeader(REPLAYED_HEADER)).isEqualTo("true");
        assertThat(json(second).get("id").asText()).isEqualTo(json(first).get("id").asText());

        // Control: without a key the duplicate IPN is rejected, proving the replay did not re-execute.
        MvcResult noKey = postJson(tenantA, "/api/v1/parts", body, null);
        assertThat(noKey.getResponse().getStatus()).isBetween(400, 499);
    }

    @Test
    void replayedStockOperationAppliesExactlyOnce() throws Exception {
        UUID locationId = createLocation(tenantA, "Idem Bin");
        UUID partId = createPart(tenantA, "IDEM-STK-" + UUID.randomUUID().toString().substring(0, 6));
        MvcResult created = postJson(tenantA, "/api/v1/stock-items", Map.of(
                "partId", partId, "locationId", locationId, "quantity", 100), null);
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        UUID itemId = UUID.fromString(json(created).get("id").asText());

        String key = UUID.randomUUID().toString();
        Map<String, Object> add = Map.of("quantity", 15, "notes", "idempotent add");
        MvcResult a1 = postJson(tenantA, "/api/v1/stock-items/" + itemId + "/add", add, key);
        MvcResult a2 = postJson(tenantA, "/api/v1/stock-items/" + itemId + "/add", add, key);
        assertThat(a1.getResponse().getStatus()).isBetween(200, 299);
        assertThat(a2.getResponse().getStatus()).isEqualTo(a1.getResponse().getStatus());
        assertThat(a2.getResponse().getHeader(REPLAYED_HEADER)).isEqualTo("true");

        MvcResult item = mockMvc.perform(authed(get("/api/v1/stock-items/" + itemId), tenantA)).andReturn();
        assertThat(json(item).get("quantity").asDouble()).isEqualTo(115.0);
    }

    @Test
    void sameKeyWithDifferentPayloadIsRejected() throws Exception {
        String key = UUID.randomUUID().toString();
        MvcResult first = postJson(tenantA, "/api/v1/parts", partBody("IDEM-DIFF-A-" + key.substring(0, 5)), key);
        MvcResult second = postJson(tenantA, "/api/v1/parts", partBody("IDEM-DIFF-B-" + key.substring(0, 5)), key);

        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(second.getResponse().getStatus()).isEqualTo(422);
        assertThat(json(second).get("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void concurrentDuplicatesExecuteOnceAndAllReturnTheSameResult() throws Exception {
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = partBody("IDEM-RACE-" + key.substring(0, 6));
        int threads = 5;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<MvcResult>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                start.await();
                return postJson(tenantA, "/api/v1/parts", body, key);
            });
        }
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (Callable<MvcResult> task : tasks) {
            futures.add(pool.submit(task));
        }
        start.countDown();

        String firstId = null;
        long replayed = 0;
        for (Future<MvcResult> future : futures) {
            MvcResult result = future.get();
            assertThat(result.getResponse().getStatus()).isEqualTo(201);
            String id = json(result).get("id").asText();
            firstId = firstId == null ? id : firstId;
            assertThat(id).isEqualTo(firstId);
            if ("true".equals(result.getResponse().getHeader(REPLAYED_HEADER))) {
                replayed++;
            }
        }
        pool.shutdown();
        assertThat(replayed).isEqualTo(threads - 1);
    }

    @Test
    void failedRequestStoresNothingSoItCanBeRetried() throws Exception {
        String ipn = "IDEM-FAIL-" + UUID.randomUUID().toString().substring(0, 6);
        createPart(tenantA, ipn);
        long before = countRecords(tenantA);

        MvcResult failed = postJson(tenantA, "/api/v1/parts", partBody(ipn), UUID.randomUUID().toString());
        assertThat(failed.getResponse().getStatus()).isBetween(400, 499);
        assertThat(failed.getResponse().getHeader(REPLAYED_HEADER)).isNull();
        assertThat(countRecords(tenantA)).isEqualTo(before);
    }

    @Test
    void keysAreIsolatedPerTenant() throws Exception {
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = partBody("IDEM-TENANT-" + key.substring(0, 6));

        MvcResult a = postJson(tenantA, "/api/v1/parts", body, key);
        MvcResult b = postJson(tenantB, "/api/v1/parts", body, key);

        assertThat(a.getResponse().getStatus()).isEqualTo(201);
        assertThat(b.getResponse().getStatus()).isEqualTo(201);
        assertThat(b.getResponse().getHeader(REPLAYED_HEADER)).isNull();
        assertThat(json(b).get("id").asText()).isNotEqualTo(json(a).get("id").asText());
    }

    @Test
    void expiredRecordIsTreatedAsNewAndCleanupPurgesIt() throws Exception {
        Duration originalTtl = properties.getTtl();
        try {
            properties.setTtl(Duration.ofMillis(1));
            String key = UUID.randomUUID().toString();
            Map<String, Object> body = partBody("IDEM-EXP-" + key.substring(0, 6));
            assertThat(postJson(tenantA, "/api/v1/parts", body, key).getResponse().getStatus()).isEqualTo(201);

            Thread.sleep(50);
            // Expired: re-executed (and rejected as a duplicate IPN) instead of replayed.
            MvcResult again = postJson(tenantA, "/api/v1/parts", body, key);
            assertThat(again.getResponse().getHeader(REPLAYED_HEADER)).isNull();
            assertThat(again.getResponse().getStatus()).isBetween(400, 499);

            assertThat(cleanupJob.purgeExpired()).isGreaterThanOrEqualTo(0);
            assertThat(countRecords(tenantA)).isZero();
        } finally {
            properties.setTtl(originalTtl);
        }
    }

    @Test
    void headerPolicyAndValidation() throws Exception {
        // Invalid key
        MvcResult invalid = postJson(tenantA, "/api/v1/parts", partBody("IDEM-INV-1"), "bad key with spaces");
        assertThat(invalid.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(invalid).get("code").asText()).isEqualTo("IDEMPOTENCY_KEY_INVALID");

        // Missing key is allowed by default, rejected once sessions are required.
        assertThat(postJson(tenantA, "/api/v1/parts", partBody("IDEM-NOKEY-1"), null).getResponse().getStatus())
                .isEqualTo(201);
        properties.setRequireForSessions(true);
        try {
            MvcResult missing = postJson(tenantA, "/api/v1/parts", partBody("IDEM-NOKEY-2"), null);
            assertThat(missing.getResponse().getStatus()).isEqualTo(400);
            assertThat(json(missing).get("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
            assertThat(postJson(tenantA, "/api/v1/parts", partBody("IDEM-NOKEY-3"), UUID.randomUUID().toString())
                    .getResponse().getStatus()).isEqualTo(201);
        } finally {
            properties.setRequireForSessions(false);
        }
    }

    @Test
    void exemptEndpointsAreUntouched() throws Exception {
        long before = countRecords(tenantA);
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("partIds", List.of(UUID.randomUUID()));

        MvcResult r1 = postJson(tenantA, "/api/v1/stock/summaries", body, key);
        MvcResult r2 = postJson(tenantA, "/api/v1/stock/summaries", body, key);

        assertThat(r1.getResponse().getStatus()).isBetween(200, 299);
        assertThat(r2.getResponse().getHeader(REPLAYED_HEADER)).isNull();
        assertThat(countRecords(tenantA)).isEqualTo(before);
    }

    // ---------------------------------------------------------------------------------------------

    private long countRecords(TenantProvisioningPort.AuthTokensResponse tenant) {
        return TenantContext.callUncheckedAs(tenant.tenantId(), recordRepository::count);
    }

    private static Map<String, Object> partBody(String ipn) {
        return Map.of("ipn", ipn, "name", "Part " + ipn, "trackable", false);
    }

    private UUID createPart(TenantProvisioningPort.AuthTokensResponse tenant, String ipn) throws Exception {
        MvcResult res = postJson(tenant, "/api/v1/parts", partBody(ipn), null);
        assertThat(res.getResponse().getStatus()).isEqualTo(201);
        return UUID.fromString(json(res).get("id").asText());
    }

    private UUID createLocation(TenantProvisioningPort.AuthTokensResponse tenant, String name) throws Exception {
        MvcResult res = postJson(tenant, "/api/v1/stock-locations", Map.of(
                "name", name, "locationType", "BIN", "structural", false), null);
        assertThat(res.getResponse().getStatus()).isEqualTo(201);
        return UUID.fromString(json(res).get("id").asText());
    }

    private MvcResult postJson(
            TenantProvisioningPort.AuthTokensResponse tenant,
            String path,
            Object body,
            String idempotencyKey
    ) throws Exception {
        MockHttpServletRequestBuilder request = authed(post(path), tenant)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
        if (idempotencyKey != null) {
            request.header(KEY_HEADER, idempotencyKey);
        }
        return mockMvc.perform(request).andReturn();
    }

    private MockHttpServletRequestBuilder authed(
            MockHttpServletRequestBuilder builder,
            TenantProvisioningPort.AuthTokensResponse tenant
    ) {
        return builder
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenant.accessToken())
                .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret);
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
