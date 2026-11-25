package com.ims;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ims.config.security.TenantAuthAndContextFilter;
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

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class FulfillmentIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TenantProvisioningPort TenantProvisioningPort;

    @Value("${ims.security.proxy-secret}")
    private String proxySecret;

    private String token;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        var tenant = TenantProvisioningPort.registerTenant(new TenantProvisioningPort.ProvisionTenantCommand(
                "Fulfillment Corp " + suffix,
                "ff-corp-" + suffix,
                "ff-" + suffix + "@example.com",
                "Ops",
                "Password123!",
                "USD",
                "UTC",
                "ENTERPRISE"
        ), true);
        token = tenant.accessToken();
    }

    @Test
    void salesShipmentReturnAndTransferFlowWithZeroDrift() throws Exception {
        UUID binA = createLocation("Fulfill-Bin-A");
        UUID binB = createLocation("Fulfill-Bin-B");
        UUID quarantine = createLocation("Quarantine-Bay");

        UUID partId = createSalablePart("SAL-DEV-01", "Salable Sensor");
        UUID stockId = createStock(partId, binA, 20);
        UUID customerId = createCustomer("Orbital Dynamics");

        // 1. Sales Order + Shipment + Allocate + Complete
        MvcResult soRes = mockMvc.perform(post("/api/v1/sales-orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "customerId", customerId,
                                "description", "Q4 Sensor Order",
                                "currency", "USD"
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reference").exists())
                .andReturn();
        UUID soId = UUID.fromString(objectMapper.readTree(soRes.getResponse().getContentAsString()).get("id").asText());

        MvcResult lineRes = mockMvc.perform(post("/api/v1/sales-orders/" + soId + "/lines")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "partId", partId,
                                "quantity", 5,
                                "salePrice", 49.99
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID lineId = UUID.fromString(objectMapper.readTree(lineRes.getResponse().getContentAsString())
                .get("lines").get(0).get("id").asText());

        MvcResult shpRes = mockMvc.perform(post("/api/v1/sales-orders/" + soId + "/shipments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("carrier", "DHL"))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID shpId = UUID.fromString(objectMapper.readTree(shpRes.getResponse().getContentAsString()).get("id").asText());

        mockMvc.perform(post("/api/v1/sales-orders/" + soId + "/allocate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "lineItemId", lineId,
                                "shipmentId", shpId,
                                "stockItemId", stockId,
                                "quantity", 5
                        ))))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/parts/" + partId + "/stock-summary")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inStock").value(20))
                .andExpect(jsonPath("$.allocatedToSales").value(5))
                .andExpect(jsonPath("$.availableStock").value(15));

        mockMvc.perform(post("/api/v1/sales-orders/" + soId + "/shipments/" + shpId + "/complete")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("trackingNumber", "DHL-999"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SHIPPED"));

        mockMvc.perform(get("/api/v1/parts/" + partId + "/stock-summary")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inStock").value(15))
                .andExpect(jsonPath("$.allocatedToSales").value(0));

        MvcResult stockList = mockMvc.perform(get("/api/v1/stock-items?partId=" + partId + "&size=50")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret))
                .andExpect(status().isOk())
                .andReturn();
        UUID shippedStockId = null;
        for (JsonNode node : objectMapper.readTree(stockList.getResponse().getContentAsString()).get("content")) {
            if (!node.get("customerId").isNull()) {
                shippedStockId = UUID.fromString(node.get("id").asText());
            }
        }
        assertThat(shippedStockId).isNotNull();

        // 2. Return Order (RMA) & Restock
        MvcResult rmaRes = mockMvc.perform(post("/api/v1/return-orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "customerId", customerId,
                                "salesOrderId", soId,
                                "description", "Customer return"
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID rmaId = UUID.fromString(objectMapper.readTree(rmaRes.getResponse().getContentAsString()).get("id").asText());

        MvcResult rmaLineRes = mockMvc.perform(post("/api/v1/return-orders/" + rmaId + "/lines")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("stockItemId", shippedStockId))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID rmaLineId = UUID.fromString(objectMapper.readTree(rmaLineRes.getResponse().getContentAsString())
                .get("lines").get(0).get("id").asText());

        mockMvc.perform(post("/api/v1/return-orders/" + rmaId + "/lines/" + rmaLineId + "/receive")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "quarantineLocationId", quarantine,
                                "outcome", "RESTOCK"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETE"));

        // 3. Transfer Order (binA -> binB)
        MvcResult trfRes = mockMvc.perform(post("/api/v1/transfer-orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "sourceLocationId", binA,
                                "destinationLocationId", binB,
                                "description", "Move to Bin B"
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID trfId = UUID.fromString(objectMapper.readTree(trfRes.getResponse().getContentAsString()).get("id").asText());

        mockMvc.perform(post("/api/v1/transfer-orders/" + trfId + "/lines")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "stockItemId", stockId,
                                "quantity", 5
                        ))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/transfer-orders/" + trfId + "/dispatch")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"));

        mockMvc.perform(post("/api/v1/transfer-orders/" + trfId + "/receive")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETE"));

        // 4. Verify zero reconciliation drift via /api/v1/stock/reconcile
        mockMvc.perform(post("/api/v1/stock/reconcile")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.driftsFoundAndCorrected").value(0));
    }

    private UUID createLocation(String name) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/stock-locations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", name))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asText());
    }

    private UUID createSalablePart(String ipn, String name) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/parts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "ipn", ipn,
                                "name", name,
                                "salable", true,
                                "trackable", false,
                                "unit", "pcs"
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asText());
    }

    private UUID createStock(UUID partId, UUID locId, int qty) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/stock-items")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "partId", partId,
                                "locationId", locId,
                                "quantity", qty,
                                "status", "OK"
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asText());
    }

    private UUID createCustomer(String name) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/companies")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header(TenantAuthAndContextFilter.PROXY_SECRET_HEADER, proxySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", name,
                                "customer", true,
                                "currency", "USD"
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asText());
    }
}
