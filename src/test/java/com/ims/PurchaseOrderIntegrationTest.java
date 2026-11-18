package com.ims;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ims.company.CurrencyService;
import com.ims.config.security.TenantAuthAndContextFilter;
import com.ims.stock.StockReconciliationJob;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Milestone 4 Verification Suite:
 * - Create a Purchase Order in a foreign currency (EUR)
 * - Auto-price line items from SupplierPriceBreak with packQuantity conversion
 * - Place PO -> verifies PartStockSummary.onOrder increments
 * - Receive partial & full deliveries into warehouse bins -> converts packs to internal units,
 *   stamps purchasePrice + currency, moves quantities from onOrder to inStock with zero reconciliation drift
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PurchaseOrderIntegrationTest {

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
    private CurrencyService currencyService;

    @Autowired
    private StockReconciliationJob reconciliationJob;

    @Value("${ims.security.proxy-secret}")
    private String proxySecret;

    private AuthService.AuthTokensResponse tenant;

    @BeforeEach
    void setUp() {
        currencyService.ensureDefaultRatesSeeded();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        tenant = authService.registerTenant(new AuthService.RegisterTenantCommand(
                "Purchasing Corp " + suffix,
                "po-corp-" + suffix,
                "po-" + suffix + "@example.com",
                "Buyer",
                "Password123!",
                "USD",
                "UTC",
                "ENTERPRISE"
        ), true);
    }

    @Test
    void foreignCurrencyPoPlacementPartialAndFullReceivingUpdatesStockAndOnOrderAtomically() throws Exception {
        // 1. Create Part, Warehouse Bin, Supplier (EUR), SupplierPart (packQuantity = 500), PriceBreak (25.00 EUR/pack)
        UUID partId = createPart("RES-0603-1K", "Resistor 1kOhm 0603");
        UUID binId = createLocation("Receiving Bin R1");
        UUID supplierId = createSupplier("EuroComponents GmbH", "EUR");
        UUID spId = createSupplierPart(partId, supplierId, "EU-RES-1K-REEL", new BigDecimal("500"), "EUR");

        mockMvc.perform(post("/api/v1/supplier-parts/" + spId + "/price-breaks")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenant.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "minQuantity", 1,
                                "unitPrice", new BigDecimal("25.00"),
                                "currency", "EUR"
                        ))))
                .andExpect(status().isCreated());

        // 2. Create PO in EUR -> reference PO-0001
        MvcResult poRes = mockMvc.perform(post("/api/v1/purchase-orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenant.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "supplierId", supplierId,
                                "description", "Q4 Resistor Replenishment",
                                "currency", "EUR",
                                "destinationLocationId", binId
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reference").value("PO-0001"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();
        UUID poId = UUID.fromString(objectMapper.readTree(poRes.getResponse().getContentAsString()).get("id").asText());

        // 3. Add line item: 4 packs (4 * 500 = 2,000 internal pcs), auto-priced at 25.00 EUR/pack = 100.00 EUR total
        MvcResult addLineRes = mockMvc.perform(post("/api/v1/purchase-orders/" + poId + "/lines")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenant.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "supplierPartId", spId,
                                "quantity", 4
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalAmount").value(100.0))
                .andReturn();
        UUID lineId = UUID.fromString(objectMapper.readTree(addLineRes.getResponse().getContentAsString())
                .get("lines").get(0).get("id").asText());

        // 4. Place PO -> status becomes PLACED and PartStockSummary.onOrder becomes 2,000
        mockMvc.perform(post("/api/v1/purchase-orders/" + poId + "/place")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenant.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PLACED"));

        mockMvc.perform(get("/api/v1/parts/" + partId + "/stock-summary")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenant.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onOrder").value(2000.0))
                .andExpect(jsonPath("$.inStock").value(0.0));

        // 5. Partial receive: 1 pack (500 pcs) -> status IN_PROGRESS, inStock = 500, onOrder = 1,500
        mockMvc.perform(post("/api/v1/purchase-orders/" + poId + "/receive")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenant.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "lines", List.of(Map.of(
                                        "lineItemId", lineId,
                                        "quantityPacks", 1,
                                        "locationId", binId,
                                        "batchCode", "LOT-EU-01"
                                ))
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderStatus").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.createdStockItems[0].quantity").value(500.0))
                .andExpect(jsonPath("$.createdStockItems[0].currency").value("EUR"));

        mockMvc.perform(get("/api/v1/parts/" + partId + "/stock-summary")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenant.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inStock").value(500.0))
                .andExpect(jsonPath("$.onOrder").value(1500.0));

        // 6. Receive remaining 3 packs (1,500 pcs) -> status COMPLETE, inStock = 2,000, onOrder = 0
        mockMvc.perform(post("/api/v1/purchase-orders/" + poId + "/receive")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenant.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "lines", List.of(Map.of(
                                        "lineItemId", lineId,
                                        "quantityPacks", 3,
                                        "locationId", binId,
                                        "batchCode", "LOT-EU-02"
                                ))
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderStatus").value("COMPLETE"));

        mockMvc.perform(get("/api/v1/parts/" + partId + "/stock-summary")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenant.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inStock").value(2000.0))
                .andExpect(jsonPath("$.onOrder").value(0.0));

        // 7. Verify printable PO document renders
        MvcResult docRes = mockMvc.perform(get("/api/v1/purchase-orders/" + poId + "/document")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenant.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(docRes.getResponse().getContentAsString()).contains("PO-0001", "EuroComponents GmbH", "EU-RES-1K-REEL");

        // 8. Verify zero reconciliation drift
        StockReconciliationJob.ReconciliationReport report = reconciliationJob.reconcileTenant(tenant.tenantId());
        assertThat(report.driftsFoundAndCorrected()).isZero();
    }

    private UUID createPart(String ipn, String name) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/parts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenant.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("ipn", ipn, "name", name))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asText());
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

    private UUID createSupplier(String name, String currency) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/companies")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenant.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", name, "supplier", true, "defaultCurrency", currency
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asText());
    }

    private UUID createSupplierPart(UUID partId, UUID supplierId, String sku, BigDecimal packQty, String currency) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/supplier-parts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenant.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "partId", partId, "supplierId", supplierId, "sku", sku,
                                "packQuantity", packQty, "currency", currency
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asText());
    }
}
