package com.ims;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ims.config.security.TenantAuthAndContextFilter;
import com.ims.config.tenant.TenantContext;
import com.ims.config.tenant.TenantPredicateInspector;
import com.ims.catalog.domain.model.ParameterTemplate;
import com.ims.catalog.domain.model.PartParameter;
import com.ims.catalog.infrastructure.persistence.PartParameterRepository;
import com.ims.catalog.application.PartService;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Milestone 1 Verification Suite:
 * - Hierarchical PartCategory tree (ltree) creation, rename, and HQL bulk subtree move
 * - Domain rules (structural categories reject parts; virtual parts reject purchaseable=true; locked parts)
 * - ParameterTemplate & PartParameter validation with JSR-385 Indriya physical unit normalization
 * - Template/Variant and Revision parts
 * - 1,000-part CSV import via Phase 0 Import framework
 * - Parameter range query using idx_part_parameters_numeric_range B-tree index (verified via EXPLAIN)
 * - Cross-tenant isolation across all Phase 1 endpoints
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PartCatalogIntegrationTest {

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
    private PartService partService;

    @Autowired
    private PartParameterRepository partParameterRepository;

    @Autowired
    private TenantPredicateInspector predicateInspector;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Value("${ims.security.proxy-secret}")
    private String proxySecret;

    private TenantProvisioningPort.AuthTokensResponse tenantA;
    private TenantProvisioningPort.AuthTokensResponse tenantB;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        tenantA = TenantProvisioningPort.registerTenant(new TenantProvisioningPort.ProvisionTenantCommand(
                "Alpha Electronics " + suffix,
                "alpha-elec-" + suffix,
                "alpha-" + suffix + "@example.com",
                "Alpha Engineer",
                "Password123!",
                "USD",
                "UTC",
                "PRO"
        ), true);

        tenantB = TenantProvisioningPort.registerTenant(new TenantProvisioningPort.ProvisionTenantCommand(
                "Beta Hardware " + suffix,
                "beta-hw-" + suffix,
                "beta-" + suffix + "@example.com",
                "Beta Engineer",
                "Password123!",
                "EUR",
                "UTC",
                "PRO"
        ), true);
    }

    @Test
    void nestedCategoriesSubtreeMoveAndStructuralRuleWorkWithTenantFiltering() throws Exception {
        // 1. Create Electronics (structural = true)
        UUID electronicsId = createCategory(tenantA, "Electronics", null, true);
        // 2. Create Passives under Electronics
        UUID passivesId = createCategory(tenantA, "Passives", electronicsId, false);
        // 3. Create Capacitors under Passives
        UUID capacitorsId = createCategory(tenantA, "Capacitors", passivesId, false);
        // 4. Create MLCC under Capacitors
        UUID mlccId = createCategory(tenantA, "MLCC", capacitorsId, false);

        // Verify initial pathString
        mockMvc.perform(get("/api/v1/part-categories/" + mlccId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantA.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pathString").value("Electronics / Passives / Capacitors / MLCC"))
                .andExpect(jsonPath("$.depth").value(3));

        // 5. Create another root category "Hardware Modules" and move the entire "Passives" subtree under it
        UUID modulesId = createCategory(tenantA, "Hardware Modules", null, true);

        predicateInspector.startRecording();
        try {
            mockMvc.perform(post("/api/v1/part-categories/" + passivesId + "/move")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantA.accessToken())
                            .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("newParentId", modulesId))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.pathString").value("Hardware Modules / Passives"));
        } finally {
            List<String> statements = predicateInspector.stopRecording().stream()
                    .filter(sql -> sql.toLowerCase().contains("part_categories"))
                    .toList();
            assertThat(statements).isNotEmpty();
            assertThat(statements).allMatch(sql -> sql.toLowerCase().contains("tenant_id"));
        }

        // Verify descendant MLCC path and pathString updated automatically
        mockMvc.perform(get("/api/v1/part-categories/" + mlccId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantA.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pathString").value("Hardware Modules / Passives / Capacitors / MLCC"));

        // 6. Moving a category into its own descendant is rejected with 400
        mockMvc.perform(post("/api/v1/part-categories/" + passivesId + "/move")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantA.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("newParentId", mlccId))))
                .andExpect(status().isBadRequest());

        // 7. Assigning a part directly to structural category "Electronics" is rejected with 422
        mockMvc.perform(post("/api/v1/parts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantA.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "ipn", "CAP-0001",
                                "name", "Illegal Structural Assignment",
                                "categoryId", electronicsId
                        ))))
                .andExpect(status().isUnprocessableEntity());

        // 8. Creating a virtual part with purchaseable = true is rejected with 422
        mockMvc.perform(post("/api/v1/parts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantA.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "ipn", "SW-LIC-001",
                                "name", "Firmware License Key",
                                "virtual", true,
                                "purchaseable", true
                        ))))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void typedParametersNormalizeUnitsAndSupportTemplateVariantsRevisionsAndRangeIndexScan() throws Exception {
        UUID catId = createCategory(tenantA, "Capacitors", null, false);

        // 1. Define Capacitance parameter template with unit "F"
        MvcResult createCapTpl = mockMvc.perform(post("/api/v1/parameter-templates")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantA.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Capacitance",
                                "units", "F",
                                "description", "Nominal capacitance in Farads",
                                "defaultCategoryId", catId
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID capTemplateId = UUID.fromString(objectMapper.readTree(createCapTpl.getResponse().getContentAsString()).get("id").asText());

        // 2. Reject incompatible unit (e.g., "10 mA" for Farads)
        UUID basePartId = createPart(tenantA, "CAP-TEMPLATE", "MLCC Capacitor Family", catId, null, null, "A", null, null);
        mockMvc.perform(post("/api/v1/parts/" + basePartId + "/parameters")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantA.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "templateId", capTemplateId,
                                "value", "10 mA"
                        ))))
                .andExpect(status().isBadRequest());

        // 3. Create variant parts with different metric prefixes: 470nF, 4.7uF, 10uF, 100uF
        UUID p470n = createPart(tenantA, "CAP-470N", "MLCC 470nF X7R", catId, basePartId, null, "A", capTemplateId, "470 nF");
        UUID p4u7 = createPart(tenantA, "CAP-4U7", "MLCC 4.7uF X7R", catId, basePartId, null, "A", capTemplateId, "4.7 uF");
        UUID p10u = createPart(tenantA, "CAP-10U", "MLCC 10uF X5R", catId, basePartId, null, "A", capTemplateId, "10 uF");
        UUID p100u = createPart(tenantA, "CAP-100U", "MLCC 100uF X5R", catId, basePartId, null, "A", capTemplateId, "100 uF");

        // Create Revision B of CAP-4U7
        UUID p4u7RevB = createPart(tenantA, "CAP-4U7", "MLCC 4.7uF X7R Rev B", catId, basePartId, p4u7, "B", capTemplateId, "4.7 uF");

        // Verify variants and revisions endpoints
        mockMvc.perform(get("/api/v1/parts/" + basePartId + "/variants")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantA.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5));

        mockMvc.perform(get("/api/v1/parts/" + p4u7 + "/revisions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantA.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        // 4. Import 1,000 parts from CSV via the Phase 0 Import framework
        StringBuilder csv = new StringBuilder("ipn,name,unit,minimumStock,component,purchaseable\n");
        for (int i = 1; i <= 1000; i++) {
            csv.append(String.format("RES-%04d,Precision Resistor %d Ohm,pcs,100,true,true\n", i, i * 10));
        }
        MockMultipartFile csvFile = new MockMultipartFile(
                "file",
                "parts-1000.csv",
                "text/csv",
                csv.toString().getBytes(StandardCharsets.UTF_8)
        );

        MvcResult sessionRes = mockMvc.perform(multipart("/api/v1/imports/sessions")
                        .file(csvFile)
                        .param("targetType", "PART")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantA.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalRows").value(1000))
                .andReturn();
        String sessionId = objectMapper.readTree(sessionRes.getResponse().getContentAsString()).get("sessionId").asText();

        Map<String, Object> mappingRequest = Map.of(
                "columnMapping", Map.of(
                        "ipn", "ipn",
                        "name", "name",
                        "unit", "unit",
                        "minimumStock", "minimumStock",
                        "component", "component",
                        "purchaseable", "purchaseable"
                ),
                "requiredFields", List.of("ipn", "name")
        );

        mockMvc.perform(post("/api/v1/imports/sessions/" + sessionId + "/dry-run")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantA.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mappingRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.validRows").value(1000))
                .andExpect(jsonPath("$.invalidRows").value(0));

        mockMvc.perform(post("/api/v1/imports/sessions/" + sessionId + "/commit")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantA.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mappingRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.committedRows").value(1000));

        // 5. Run a range query for Capacitance between 1uF and 10uF
        MvcResult rangeRes = mockMvc.perform(get("/api/v1/parts")
                        .param("paramTemplateId", capTemplateId.toString())
                        .param("paramMin", "1uF")
                        .param("paramMax", "10uF")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantA.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode rangePage = objectMapper.readTree(rangeRes.getResponse().getContentAsString());
        List<String> matchedIds = new ArrayList<>();
        for (JsonNode item : rangePage.get("content")) {
            matchedIds.add(item.get("id").asText());
        }
        assertThat(matchedIds)
                .containsExactlyInAnyOrder(p4u7.toString(), p4u7RevB.toString(), p10u.toString())
                .doesNotContain(p470n.toString(), p100u.toString());

        // 6. Verify PostgreSQL EXPLAIN uses idx_part_parameters_numeric_range for the range query
        try (Connection conn = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             PreparedStatement ps = conn.prepareStatement(
                     "EXPLAIN SELECT part_id FROM part_parameters WHERE tenant_id = ? AND template_id = ? AND value_numeric BETWEEN ? AND ?"
             )) {
            conn.createStatement().execute("ANALYZE part_parameters");
            conn.createStatement().execute("SET enable_seqscan = off");
            ps.setObject(1, tenantA.tenantId());
            ps.setObject(2, capTemplateId);
            ps.setDouble(3, 1.0e-6);
            ps.setDouble(4, 1.0e-5);
            try (ResultSet rs = ps.executeQuery()) {
                StringBuilder explainPlan = new StringBuilder();
                while (rs.next()) {
                    explainPlan.append(rs.getString(1)).append("\n");
                }
                assertThat(explainPlan.toString()).contains("idx_part_parameters_numeric_range");
            }
        }

        // 7. Cross-tenant isolation: Tenant B gets 404 on Tenant A's part and category
        mockMvc.perform(get("/api/v1/parts/" + p4u7)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantB.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret))
                .andExpect(status().isNotFound());

        mockMvc.perform(put("/api/v1/part-categories/" + catId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantB.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "Hijack"))))
                .andExpect(status().isNotFound());
    }

    private UUID createCategory(TenantProvisioningPort.AuthTokensResponse tenant, String name, UUID parentId, boolean structural) throws Exception {
        Map<String, Object> body = parentId != null
                ? Map.of("name", name, "parentId", parentId, "structural", structural)
                : Map.of("name", name, "structural", structural);
        MvcResult res = mockMvc.perform(post("/api/v1/part-categories")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenant.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asText());
    }

    private UUID createPart(
            TenantProvisioningPort.AuthTokensResponse tenant,
            String ipn,
            String name,
            UUID categoryId,
            UUID variantOfId,
            UUID revisionOfId,
            String revision,
            UUID paramTemplateId,
            String paramValue
    ) throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("ipn", ipn);
        body.put("name", name);
        body.put("categoryId", categoryId);
        body.put("revision", revision);
        if (variantOfId != null) {
            body.put("variantOfId", variantOfId);
        }
        if (revisionOfId != null) {
            body.put("revisionOfId", revisionOfId);
        }
        if (paramTemplateId != null && paramValue != null) {
            body.put("parameters", List.of(Map.of("templateId", paramTemplateId, "value", paramValue)));
        }
        MvcResult res = mockMvc.perform(post("/api/v1/parts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenant.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asText());
    }
}
