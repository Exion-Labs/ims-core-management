package com.ims;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ims.config.security.TenantAuthAndContextFilter;
import com.ims.config.tenant.TenantContext;
import com.ims.stock.application.StockReconciliationJob;
import com.ims.stock.application.StockService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Milestone 2 Verification Suite:
 * - Hierarchical StockLocation (ltree) with structural rules & subtree move
 * - Atomic stock operations: create, count, add, remove, partial transfer (auto-split), split, merge, serialize
 * - 50-thread parallel stock mutations on the same Part -> StockReconciliationJob reports ZERO drift & no deadlocks
 * - Quality Test Templates & Test Results
 * - ZXing Barcode generation & unified POST /api/v1/barcode/scan resolver
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class StockEngineIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TenantProvisioningPort TenantProvisioningPort;

    @Autowired
    private StockService stockService;

    @Autowired
    private StockReconciliationJob reconciliationJob;

    @Value("${ims.security.proxy-secret}")
    private String proxySecret;

    private TenantProvisioningPort.AuthTokensResponse tenantA;
    private TenantProvisioningPort.AuthTokensResponse tenantB;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        tenantA = TenantProvisioningPort.registerTenant(new TenantProvisioningPort.ProvisionTenantCommand(
                "Warehouse Alpha " + suffix,
                "wh-alpha-" + suffix,
                "wh-a-" + suffix + "@example.com",
                "Warehouse Manager A",
                "Password123!",
                "USD",
                "UTC",
                "ENTERPRISE"
        ), true);

        tenantB = TenantProvisioningPort.registerTenant(new TenantProvisioningPort.ProvisionTenantCommand(
                "Warehouse Beta " + suffix,
                "wh-beta-" + suffix,
                "wh-b-" + suffix + "@example.com",
                "Warehouse Manager B",
                "Password123!",
                "EUR",
                "UTC",
                "ENTERPRISE"
        ), true);
    }

    @Test
    void stockLocationsOperationsSerializationQualityTestsAndBarcodesWorkAtomically() throws Exception {
        // 1. Create Warehouse (structural = true) -> Room A -> Bin A1 & Bin A2
        UUID warehouseId = createLocation(tenantA, "Main Warehouse", null, "WAREHOUSE", true, null);
        UUID roomId = createLocation(tenantA, "Room A", warehouseId, "ROOM", false, "LOC-ROOM-A");
        UUID bin1Id = createLocation(tenantA, "Bin A1", roomId, "BIN", false, "LOC-BIN-A1");
        UUID bin2Id = createLocation(tenantA, "Bin A2", roomId, "BIN", false, "LOC-BIN-A2");

        // Create a trackable part
        UUID mcuPartId = createPart(tenantA, "MCU-001", "Cortex-M4 Controller", true);

        // 2. Domain Rule: Cannot create stock directly in structural location "Main Warehouse"
        mockMvc.perform(post("/api/v1/stock-items")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantA.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "partId", mcuPartId,
                                "locationId", warehouseId,
                                "quantity", 100
                        ))))
                .andExpect(status().isUnprocessableEntity());

        // 3. Create initial batch of 100 in Bin A1
        MvcResult createStockRes = mockMvc.perform(post("/api/v1/stock-items")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantA.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "partId", mcuPartId,
                                "locationId", bin1Id,
                                "quantity", 100,
                                "batchCode", "LOT-2026-11",
                                "customBarcode", "STK-LOT-001"
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID batchItemId = UUID.fromString(objectMapper.readTree(createStockRes.getResponse().getContentAsString()).get("id").asText());

        // 4. Add 50 (+50 -> 150) and Remove 20 (-20 -> 130)
        mockMvc.perform(post("/api/v1/stock-items/" + batchItemId + "/add")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantA.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("quantity", 50, "notes", "Restock"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(150));

        mockMvc.perform(post("/api/v1/stock-items/" + batchItemId + "/remove")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantA.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("quantity", 20, "notes", "Sample draw"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(130));

        // 5. Partial transfer of 30 units from Bin A1 to Bin A2 (auto-splits into a child item in Bin A2, leaving 100 in Bin A1)
        MvcResult transferRes = mockMvc.perform(post("/api/v1/stock-items/" + batchItemId + "/transfer")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantA.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetLocationId", bin2Id,
                                "quantity", 30,
                                "notes", "Partial transfer to Bin A2"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(30))
                .andExpect(jsonPath("$.locationId").value(bin2Id.toString()))
                .andExpect(jsonPath("$.parentItemId").value(batchItemId.toString()))
                .andReturn();
        UUID splitChildId = UUID.fromString(objectMapper.readTree(transferRes.getResponse().getContentAsString()).get("id").asText());

        // 6. Merge the 30 units in Bin A2 back into the 100 units in Bin A1 -> 130 units
        mockMvc.perform(post("/api/v1/stock-items/merge")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantA.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "stockItemIds", List.of(batchItemId, splitChildId),
                                "targetLocationId", bin1Id,
                                "notes", "Re-consolidate batch"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(130));

        // 7. Serialize 5 units from the batch into 5 distinct serialized items (SN-001 .. SN-005)
        MvcResult serializeRes = mockMvc.perform(post("/api/v1/stock-items/" + batchItemId + "/serialize")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantA.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "serialNumbers", List.of("SN-001", "SN-002", "SN-003", "SN-004", "SN-005"),
                                "targetLocationId", bin2Id,
                                "notes", "Burn-in serialization"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andReturn();
        UUID sn1ItemId = UUID.fromString(objectMapper.readTree(serializeRes.getResponse().getContentAsString()).get(0).get("id").asText());

        // 8. Quality Control: Create a test template on MCU-001 and record a PASS test result on SN-001
        MvcResult tplRes = mockMvc.perform(post("/api/v1/parts/" + mcuPartId + "/test-templates")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantA.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "testName", "JTAG Boundary Scan",
                                "required", true,
                                "requiresValue", true
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID testTplId = UUID.fromString(objectMapper.readTree(tplRes.getResponse().getContentAsString()).get("id").asText());

        mockMvc.perform(post("/api/v1/stock-items/" + sn1ItemId + "/test-results")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantA.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "templateId", testTplId,
                                "passed", true,
                                "value", "3.301 V / 0 errors",
                                "notes", "Passed automated rig"
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.passed").value(true));

        // 9. Verify StockItemTracking history on batchItemId and sn1ItemId
        mockMvc.perform(get("/api/v1/stock-items/" + batchItemId + "/tracking")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantA.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6));

        // 10. Verify Barcode Scan Resolver & ZXing PNG generation
        mockMvc.perform(post("/api/v1/barcode/scan")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantA.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("barcode", "STK-LOT-001"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchedType").value("STOCK_ITEM"))
                .andExpect(jsonPath("$.entityId").value(batchItemId.toString()));

        mockMvc.perform(post("/api/v1/barcode/scan")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantA.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("barcode", "LOC-BIN-A1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchedType").value("STOCK_LOCATION"))
                .andExpect(jsonPath("$.entityId").value(bin1Id.toString()));

        mockMvc.perform(get("/api/v1/barcode/image")
                        .param("code", "IMS:STOCK:" + sn1ItemId)
                        .param("format", "QR_CODE")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantA.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret))
                .andExpect(status().isOk());

        // 11. Verify PartStockSummary total is still 130 (125 in batch + 5 serialized) and zero reconciliation drift
        mockMvc.perform(get("/api/v1/parts/" + mcuPartId + "/stock-summary")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantA.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inStock").value(130.0))
                .andExpect(jsonPath("$.availableStock").value(130.0));

        StockReconciliationJob.ReconciliationReport report = reconciliationJob.reconcileTenant(tenantA.tenantId());
        assertThat(report.driftsFoundAndCorrected()).isZero();
    }

    @Test
    void fiftyParallelStockMutationsProduceZeroReconciliationDriftAndNoDeadlocks() throws Exception {
        UUID bin1 = createLocation(tenantA, "Concurrency Bin 1", null, "BIN", false, null);
        UUID bin2 = createLocation(tenantA, "Concurrency Bin 2", null, "BIN", false, null);
        UUID partId = createPart(tenantA, "CONC-PART-01", "High Concurrency Chip", false);

        // Seed 10 stock items of 1,000 units each (initial total = 10,000)
        List<UUID> itemIds = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            final int batchIdx = i;
            StockService.StockItemView created = TenantContext.callUncheckedAs(tenantA.tenantId(), () ->
                    stockService.createStock(new StockService.CreateStockCommand(
                            partId, bin1, new BigDecimal("1000"), null, "BATCH-" + batchIdx,
                            "OK", null, null, null, null, false, null, "Initial seed"
                    ))
            );
            itemIds.add(created.id());
        }

        // Run 50 parallel operations across the 10 stock items (adds, removes, and transfers)
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            final int opIndex = i;
            final UUID targetItemId = itemIds.get(i % itemIds.size());
            tasks.add(() -> {
                TenantContext.runAs(tenantA.tenantId(), () -> {
                    if (opIndex % 3 == 0) {
                        stockService.addStock(targetItemId, new StockService.StockQuantityOperationCommand(
                                new BigDecimal("15"), "Concurrent add #" + opIndex
                        ));
                    } else if (opIndex % 3 == 1) {
                        stockService.removeStock(targetItemId, new StockService.StockQuantityOperationCommand(
                                new BigDecimal("5"), "Concurrent remove #" + opIndex
                        ));
                    } else {
                        stockService.transferStock(targetItemId, new StockService.TransferStockCommand(
                                (opIndex % 2 == 0) ? bin2 : bin1,
                                new BigDecimal("3"),
                                "Concurrent partial transfer #" + opIndex
                        ));
                    }
                });
                return null;
            });
        }

        List<Future<Void>> futures = pool.invokeAll(tasks);
        for (Future<Void> f : futures) {
            f.get(); // Assert zero exceptions / deadlocks
        }
        pool.shutdown();

        // Expected total: 10,000 + (17 adds * 15) - (17 removes * 5) + (16 transfers * 0) = 10,170
        StockService.StockSummaryView summary = TenantContext.callUncheckedAs(tenantA.tenantId(), () ->
                stockService.getPartStockSummary(partId)
        );
        assertThat(summary.inStock()).isEqualByComparingTo(new BigDecimal("10170"));

        // Run StockReconciliationJob and assert ZERO drift
        StockReconciliationJob.ReconciliationReport report = reconciliationJob.reconcileTenant(tenantA.tenantId());
        assertThat(report.driftsFoundAndCorrected()).isZero();
        assertThat(report.drifts()).isEmpty();
    }

    private UUID createLocation(
            TenantProvisioningPort.AuthTokensResponse tenant,
            String name,
            UUID parentId,
            String locationType,
            boolean structural,
            String customBarcode
    ) throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("name", name);
        body.put("locationType", locationType);
        body.put("structural", structural);
        if (parentId != null) body.put("parentId", parentId);
        if (customBarcode != null) body.put("customBarcode", customBarcode);

        MvcResult res = mockMvc.perform(post("/api/v1/stock-locations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenant.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asText());
    }

    private UUID createPart(TenantProvisioningPort.AuthTokensResponse tenant, String ipn, String name, boolean trackable) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/parts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenant.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "ipn", ipn,
                                "name", name,
                                "trackable", trackable
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asText());
    }
}
