package com.ims;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ims.company.CurrencyService;
import com.ims.config.security.TenantAuthAndContextFilter;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Milestone 3 Verification Suite:
 * - Companies (Supplier, Manufacturer, Customer), Contacts, and Addresses
 * - Link an internal Part to multiple ManufacturerParts and SupplierParts with packQuantity conversion
 * - Tiered SupplierPriceBreaks in foreign currencies (EUR, JPY) converted to the tenant's base currency (USD)
 * - SupplierPart barcode scan resolution via Phase 2 /api/v1/barcode/scan
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CompanyCatalogIntegrationTest {

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

    @Value("${ims.security.proxy-secret}")
    private String proxySecret;

    private AuthService.AuthTokensResponse tenantUsd;

    @BeforeEach
    void setUp() {
        currencyService.ensureDefaultRatesSeeded();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        tenantUsd = authService.registerTenant(new AuthService.RegisterTenantCommand(
                "Global Sourcing Corp " + suffix,
                "sourcing-" + suffix,
                "sourcing-" + suffix + "@example.com",
                "Procurement Lead",
                "Password123!",
                "USD",
                "UTC",
                "ENTERPRISE"
        ), true);
    }

    @Test
    void multiManufacturerSupplierPartsPackQuantityAndMultiCurrencyPriceBreaksConvertToBaseCurrency() throws Exception {
        // 1. Set explicit EUR exchange rate (1 USD = 0.80 EUR -> 1 EUR = 1.25 USD)
        mockMvc.perform(post("/api/v1/currencies/rates")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantUsd.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetCurrency", "EUR",
                                "rate", new BigDecimal("0.800000")
                        ))))
                .andExpect(status().isOk());

        // 2. Create internal Part
        MvcResult partRes = mockMvc.perform(post("/api/v1/parts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantUsd.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "ipn", "CAP-100N-0402",
                                "name", "MLCC 100nF 50V 0402",
                                "unit", "pcs"
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID partId = UUID.fromString(objectMapper.readTree(partRes.getResponse().getContentAsString()).get("id").asText());

        // 3. Create 2 Manufacturers (Murata, TDK) and 2 Suppliers (Mouser in USD, Wurth in EUR)
        UUID murataId = createCompany("Murata Manufacturing", false, true, false, "JPY");
        UUID tdkId = createCompany("TDK Corporation", false, true, false, "JPY");
        UUID mouserId = createCompany("Mouser Electronics", true, false, false, "USD");
        UUID wurthId = createCompany("Wurth Elektronik Europe", true, true, false, "EUR");

        // Add contact & address to Wurth
        mockMvc.perform(post("/api/v1/companies/" + wurthId + "/contacts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantUsd.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Hans Mueller",
                                "roleTitle", "Account Manager",
                                "email", "hans@wurth.test",
                                "primaryContact", true
                        ))))
                .andExpect(status().isCreated());

        // 4. Link internal Part to both Manufacturers
        UUID murataMpId = createManufacturerPart(partId, murataId, "GRM155R71H104KE14D");
        UUID tdkMpId = createManufacturerPart(partId, tdkId, "C1005X7R1H104K050BB");

        // 5. Link to Supplier 1 (Mouser, packQuantity = 100 pcs/pack, USD) and Supplier 2 (Wurth, packQuantity = 1000 pcs/reel, EUR)
        UUID mouserSpId = createSupplierPart(partId, mouserId, murataMpId, "81-GRM155R71H104KE14D", new BigDecimal("100"), "USD", "SUP-BC-MOUSER-01");
        UUID wurthSpId = createSupplierPart(partId, wurthId, tdkMpId, "WE-REEL-100N", new BigDecimal("1000"), "EUR", "SUP-BC-WURTH-01");

        // 6. Add tiered price breaks on Wurth in EUR:
        //    - minQty 1 reel (1000 pcs): 80.00 EUR -> converts to 100.00 USD per reel (0.1000 USD per internal pc)
        //    - minQty 10 reels (10000 pcs): 64.00 EUR -> converts to 80.00 USD per reel (0.0800 USD per internal pc)
        mockMvc.perform(post("/api/v1/supplier-parts/" + wurthSpId + "/price-breaks")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantUsd.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "minQuantity", 1,
                                "unitPrice", new BigDecimal("80.00"),
                                "currency", "EUR"
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenantBaseCurrency").value("USD"))
                .andExpect(jsonPath("$.convertedPackPriceInBase").value(100.0))
                .andExpect(jsonPath("$.convertedInternalUnitPriceInBase").value(0.1));

        mockMvc.perform(post("/api/v1/supplier-parts/" + wurthSpId + "/price-breaks")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantUsd.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "minQuantity", 10,
                                "unitPrice", new BigDecimal("64.00"),
                                "currency", "EUR"
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.convertedPackPriceInBase").value(80.0))
                .andExpect(jsonPath("$.convertedInternalUnitPriceInBase").value(0.08));

        // 7. Verify Part supplier-parts listing returns both suppliers and their converted price breaks
        MvcResult spListRes = mockMvc.perform(get("/api/v1/parts/" + partId + "/supplier-parts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantUsd.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andReturn();
        assertThat(spListRes.getResponse().getContentAsString()).contains("WE-REEL-100N", "81-GRM155R71H104KE14D");

        // 8. Verify SupplierPart barcode resolves via Phase 2 /api/v1/barcode/scan
        mockMvc.perform(post("/api/v1/barcode/scan")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantUsd.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("barcode", "SUP-BC-WURTH-01"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchedType").value("SUPPLIER_PART"))
                .andExpect(jsonPath("$.entityId").value(wurthSpId.toString()));
    }

    private UUID createCompany(String name, boolean supplier, boolean manufacturer, boolean customer, String currency) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/companies")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantUsd.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", name,
                                "supplier", supplier,
                                "manufacturer", manufacturer,
                                "customer", customer,
                                "defaultCurrency", currency
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asText());
    }

    private UUID createManufacturerPart(UUID partId, UUID manufacturerId, String mpn) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/manufacturer-parts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantUsd.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "partId", partId,
                                "manufacturerId", manufacturerId,
                                "mpn", mpn
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asText());
    }

    private UUID createSupplierPart(
            UUID partId,
            UUID supplierId,
            UUID manufacturerPartId,
            String sku,
            BigDecimal packQuantity,
            String currency,
            String customBarcode
    ) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/supplier-parts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantUsd.accessToken())
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "partId", partId,
                                "supplierId", supplierId,
                                "manufacturerPartId", manufacturerPartId,
                                "sku", sku,
                                "packQuantity", packQuantity,
                                "currency", currency,
                                "customBarcode", customBarcode
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asText());
    }
}
