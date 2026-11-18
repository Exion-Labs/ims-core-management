package com.ims;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ims.build.BuildService;
import com.ims.config.security.TenantAuthAndContextFilter;
import com.ims.config.tenant.TenantContext;
import com.ims.stock.StockItem;
import com.ims.stock.StockReconciliationJob;
import com.ims.stock.StockService;
import com.ims.tenant.AuthService;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Milestone 5 Verification Suite:
 * - BOM cycle detection (A -> B -> A rejected with 422)
 * - Complete production build consuming both untracked batches and serialized sub-components
 * - Finished serialized assembly carries installedInId genealogy on tracked sub-components
 * - Concurrent allocations against the same stock prevent overallocation via PESSIMISTIC_WRITE
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ManufacturingIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthService authService;

    @Autowired
    private BuildService buildService;

    @Autowired
    private StockService stockService;

    @Autowired
    private StockReconciliationJob reconciliationJob;

    @Value("${ims.security.proxy-secret}")
    private String proxySecret;

    private AuthService.AuthTokensResponse tenant;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        tenant = authService.registerTenant(new AuthService.RegisterTenantCommand(
                "Factory Corp " + suffix,
                "factory-" + suffix,
                "factory-" + suffix + "@example.com",
                "Production Lead",
                "Password123!",
                "USD",
                "UTC",
                "ENTERPRISE"
        ), true);
    }

    @Test
    void bomCycleDetectionAndFullProductionBuildWithSerializedGenealogyWorkAtomically() throws Exception {
        UUID binId = createLocation("Production Bin P1");

        // 1. Create Assembly (Drone Controller), Sub-Assembly (Power Module), and Untracked Resistor
        UUID droneId = createPart("ASM-DRONE-1", "Flight Controller Assembly", true, true);
        UUID pwrId = createPart("SUB-PWR-1", "Power Regulator Module", true, true);
        UUID resId = createPart("CMP-RES-10K", "10kOhm Resistor", false, false);

        // Build BOM: Drone -> Power Module (1), Drone -> Resistor (4); Power Module -> Resistor (2)
        addBomItem(droneId, pwrId, 1, "U1");
        addBomItem(droneId, resId, 4, "R1-R4");
        addBomItem(pwrId, resId, 2, "R10-R11");

        // Cycle detection: Adding Drone to Power Module's BOM must fail with 422
        mockMvc.perform(post("/api/v1/parts/" + pwrId + "/bom")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenant.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "subPartId", droneId,
                                "quantity", 1
                        ))))
                .andExpect(status().isUnprocessableEntity());

        // 2. Seed Stock: 100 Resistors (batch) + 1 serialized Power Module (SN-PWR-001)
        UUID resStockId = createStock(resId, binId, new BigDecimal("100"), null, "LOT-RES");
        UUID pwrStockId = createStock(pwrId, binId, BigDecimal.ONE, "SN-PWR-001", null);

        // Max buildable for Drone = 1 (limited by 1 Power Module)
        mockMvc.perform(get("/api/v1/parts/" + droneId + "/max-buildable")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenant.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxBuildable").value(1));

        // 3. Create Build Order for 1 Drone Controller -> BO-0001
        MvcResult boRes = mockMvc.perform(post("/api/v1/builds")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenant.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "partId", droneId,
                                "quantity", 1,
                                "destinationLocationId", binId
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reference").value("BO-0001"))
                .andExpect(jsonPath("$.lines.length()").value(2))
                .andReturn();
        JsonNode boNode = objectMapper.readTree(boRes.getResponse().getContentAsString());
        UUID buildId = UUID.fromString(boNode.get("id").asText());
        UUID pwrLineId = UUID.fromString(boNode.get("lines").get(0).get("id").asText());
        UUID resLineId = UUID.fromString(boNode.get("lines").get(1).get("id").asText());

        // 4. Create incomplete serialized build output (isBuilding = true, SN-DRONE-001)
        MvcResult outRes = mockMvc.perform(post("/api/v1/builds/" + buildId + "/outputs")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenant.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "quantity", 1,
                                "serialNumber", "SN-DRONE-001",
                                "locationId", binId
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.building").value(true))
                .andReturn();
        UUID outputItemId = UUID.fromString(objectMapper.readTree(outRes.getResponse().getContentAsString()).get("id").asText());

        // 5. Allocate serialized Power Module + 4 untracked Resistors to the build output
        mockMvc.perform(post("/api/v1/builds/" + buildId + "/allocate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenant.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "buildLineId", pwrLineId,
                                "stockItemId", pwrStockId,
                                "quantity", 1,
                                "installIntoId", outputItemId
                        ))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/builds/" + buildId + "/allocate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenant.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "buildLineId", resLineId,
                                "stockItemId", resStockId,
                                "quantity", 4,
                                "installIntoId", outputItemId
                        ))))
                .andExpect(status().isCreated());

        // 6. Complete Build Output -> Drone becomes inStock = 1, Resistors drop 100 -> 96,
        //    and SN-PWR-001 has installedInId == outputItemId!
        mockMvc.perform(post("/api/v1/builds/" + buildId + "/complete-output")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenant.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "outputStockItemId", outputItemId,
                                "destinationLocationId", binId
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        // Verify genealogy
        mockMvc.perform(get("/api/v1/stock-items/" + pwrStockId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenant.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.installedInId").value(outputItemId.toString()));

        // Verify resistor remaining stock = 96
        mockMvc.perform(get("/api/v1/parts/" + resId + "/stock-summary")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenant.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inStock").value(96.0))
                .andExpect(jsonPath("$.allocatedToBuilds").value(0.0));

        // Verify Drone summary: inStock = 1, inProduction = 0
        mockMvc.perform(get("/api/v1/parts/" + droneId + "/stock-summary")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenant.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inStock").value(1.0))
                .andExpect(jsonPath("$.inProduction").value(0.0));

        // 7. Concurrent allocation test: 10 threads each try to allocate 15 units from the remaining 96 resistors (only 6 can succeed)
        List<BuildService.BuildOrderView> concurrentBuilds = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            BuildService.BuildOrderView b = TenantContext.callUncheckedAs(tenant.tenantId(), () ->
                    buildService.createBuildOrder(new BuildService.CreateBuildOrderCommand(
                            droneId, "Conc Build " + System.nanoTime(), new BigDecimal("15"),
                            null, binId, binId, null, null, false
                    ))
            );
            concurrentBuilds.add(b);
        }

        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        AtomicInteger successes = new AtomicInteger(0);
        List<Callable<Void>> tasks = new ArrayList<>();
        for (BuildService.BuildOrderView b : concurrentBuilds) {
            UUID resLine = b.lines().stream()
                    .filter(l -> l.subPartId().equals(resId))
                    .findFirst().orElseThrow().id();
            tasks.add(() -> {
                try {
                    TenantContext.runAs(tenant.tenantId(), () ->
                            buildService.allocateStock(b.id(), new BuildService.AllocateStockCommand(
                                    resLine, resStockId, new BigDecimal("15"), null
                            ))
                    );
                    successes.incrementAndGet();
                } catch (Exception ignored) {
                }
                return null;
            });
        }
        List<Future<Void>> futures = pool.invokeAll(tasks);
        for (Future<Void> f : futures) f.get();
        pool.shutdown();

        // Floor(96 / 15) = 6 -> never overallocates!
        assertThat(successes.get()).isEqualTo(6);
        StockService.StockSummaryView resSummary = TenantContext.callUncheckedAs(tenant.tenantId(), () ->
                stockService.getPartStockSummary(resId)
        );
        assertThat(resSummary.allocatedToBuilds()).isEqualByComparingTo(new BigDecimal("90"));
        assertThat(resSummary.availableStock()).isEqualByComparingTo(new BigDecimal("6"));

        StockReconciliationJob.ReconciliationReport report = reconciliationJob.reconcileTenant(tenant.tenantId());
        assertThat(report.driftsFoundAndCorrected()).isZero();
    }

    private UUID createLocation(String name) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/stock-locations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenant.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", name, "locationType", "BIN"))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asText());
    }

    private UUID createPart(String ipn, String name, boolean assembly, boolean trackable) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/parts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenant.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "ipn", ipn, "name", name, "assembly", assembly, "trackable", trackable
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asText());
    }

    private void addBomItem(UUID assemblyId, UUID subPartId, int qty, String ref) throws Exception {
        mockMvc.perform(post("/api/v1/parts/" + assemblyId + "/bom")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenant.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "subPartId", subPartId, "quantity", qty, "reference", ref
                        ))))
                .andExpect(status().isCreated());
    }

    private UUID createStock(UUID partId, UUID locId, BigDecimal qty, String serial, String batch) throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("partId", partId);
        body.put("locationId", locId);
        body.put("quantity", qty);
        if (serial != null) body.put("serialNumber", serial);
        if (batch != null) body.put("batchCode", batch);

        MvcResult res = mockMvc.perform(post("/api/v1/stock-items")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenant.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asText());
    }
}
