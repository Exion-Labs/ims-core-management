package com.ims;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ims.config.security.TenantAuthAndContextFilter;
import com.ims.integration.WebhookDeliveryService;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class IntegrationAndPlatformTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TenantProvisioningPort TenantProvisioningPort;

    @Value("${ims.security.proxy-secret}")
    private String proxySecret;

    private TenantProvisioningPort.AuthTokensResponse tenant;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        tenant = TenantProvisioningPort.registerTenant(new TenantProvisioningPort.ProvisionTenantCommand(
                "Integration Corp " + suffix,
                "int-corp-" + suffix,
                "int-" + suffix + "@example.com",
                "Owner",
                "Password123!",
                "USD",
                "UTC",
                "ENTERPRISE"
        ), true);
    }

    @Test
    void pricingRollupPdfReportsWebhooksSsrfSseAndPlatformConsole() throws Exception {
        String token = tenant.accessToken();

        // 1. BOM Pricing Rollup
        UUID compId = createPart(token, "COMP-R1", "Resistor", false, true);
        UUID asmId = createPart(token, "ASM-PCB", "Controller PCB", true, false);
        UUID supId = createSupplier(token, "Mouser");
        UUID spId = createSupplierPart(token, compId, supId, "MOU-R1");
        mockMvc.perform(post("/api/v1/supplier-parts/" + spId + "/price-breaks")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("minQuantity", 1, "unitPrice", new BigDecimal("2.50"), "currency", "USD"))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/parts/" + asmId + "/bom")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("subPartId", compId, "quantity", 4))))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/parts/" + asmId + "/pricing")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bomMinUnitCost").value(10.0));

        // 2. Custom PDF Report
        MvcResult tplRes = mockMvc.perform(post("/api/v1/reports/templates")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Part Spec Sheet",
                                "entityType", "PART",
                                "templateHtml", "<h1>{{name}}</h1><p>IPN: {{ipn}}</p>"
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID tplId = UUID.fromString(objectMapper.readTree(tplRes.getResponse().getContentAsString()).get("id").asText());

        MvcResult pdfRes = mockMvc.perform(post("/api/v1/reports/" + tplId + "/render?entityId=" + asmId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andReturn();
        byte[] pdfBytes = pdfRes.getResponse().getContentAsByteArray();
        assertThat(new String(pdfBytes, 0, 4)).isEqualTo("%PDF");

        // 3. Webhook SSRF guard & HMAC signing
        mockMvc.perform(post("/api/v1/webhooks")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Bad SSRF Hook",
                                "targetUrl", "http://127.0.0.1:8080/internal",
                                "eventTypes", List.of("*")
                        ))))
                .andExpect(status().isUnprocessableEntity());

        mockMvc.perform(post("/api/v1/webhooks")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Valid External Hook",
                                "targetUrl", "https://example.com/ims-webhook",
                                "eventTypes", List.of("sales_order.shipped")
                        ))))
                .andExpect(status().isCreated());
        assertThat(WebhookDeliveryService.signPayload("secret", "hello")).startsWith("sha256=");

        // 4. Real-Time SSE Ticket
        MvcResult ticketRes = mockMvc.perform(post("/api/v1/events/ticket")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret))
                .andExpect(status().isOk())
                .andReturn();
        String ticket = objectMapper.readTree(ticketRes.getResponse().getContentAsString()).get("ticket").asText();
        mockMvc.perform(get("/api/v1/events?ticket=" + ticket))
                .andExpect(status().isOk());

        // 5. Platform Admin Console & Audience Isolation
        mockMvc.perform(get("/api/platform/v1/tenants")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret))
                .andExpect(status().isUnauthorized());

        MvcResult pLoginRes = mockMvc.perform(post("/api/platform/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "platform@ims.dev",
                                "password", "Password123!"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        String platformToken = objectMapper.readTree(pLoginRes.getResponse().getContentAsString()).get("accessToken").asText();

        // Platform token rejected on tenant API
        mockMvc.perform(get("/api/v1/parts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + platformToken)
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret))
                .andExpect(status().isUnauthorized());

        // List tenants & plans
        mockMvc.perform(get("/api/platform/v1/tenants")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + platformToken)
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret))
                .andExpect(status().isOk());

        MvcResult plansRes = mockMvc.perform(get("/api/platform/v1/plans")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + platformToken)
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode starterPlan = null;
        for (JsonNode p : objectMapper.readTree(plansRes.getResponse().getContentAsString())) {
            if ("STARTER".equals(p.get("code").asText())) starterPlan = p;
        }
        assertThat(starterPlan).isNotNull();

        // Dependency validation: cannot disable core PARTS or enable RETURNS on STARTER without SALES
        mockMvc.perform(put("/api/platform/v1/plans/" + starterPlan.get("id").asText() + "/features/PARTS")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + platformToken)
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("enabled", false))))
                .andExpect(status().isUnprocessableEntity());

        mockMvc.perform(put("/api/platform/v1/plans/" + starterPlan.get("id").asText() + "/features/RETURNS")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + platformToken)
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("enabled", true))))
                .andExpect(status().isUnprocessableEntity());

        // Usage snapshot + Support session
        mockMvc.perform(post("/api/platform/v1/tenants/" + tenant.tenantId() + "/usage-snapshots")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + platformToken)
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partsCount").value(2));

        mockMvc.perform(post("/api/platform/v1/tenants/" + tenant.tenantId() + "/support-sessions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + platformToken)
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("reason", "Investigating BOM pricing"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.impersonationToken").exists());
    }

    private UUID createPart(String token, String ipn, String name, boolean assembly, boolean component) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/parts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "ipn", ipn,
                                "name", name,
                                "assembly", assembly,
                                "component", component,
                                "unit", "pcs"
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asText());
    }

    private UUID createSupplier(String token, String name) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/companies")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", name, "supplier", true, "currency", "USD"))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asText());
    }

    private UUID createSupplierPart(String token, UUID partId, UUID supId, String sku) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/supplier-parts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("partId", partId, "supplierId", supId, "sku", sku, "currency", "USD"))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asText());
    }
}
