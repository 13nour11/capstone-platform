package com.ecommerce.order;

import com.ecommerce.order.api.dto.CreateOrderRequest;
import com.ecommerce.order.api.dto.OrderItemRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.contract.wiremock.AutoConfigureWireMock;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@AutoConfigureWireMock(port = 0)
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "inventory.service.url=http://localhost:${wiremock.server.port}",
        // Same guards as config-repo/order-service.yml: the call is cut off after 2 s
        "resilience4j.timelimiter.instances.inventoryService.timeoutDuration=2s",
        "resilience4j.retry.instances.inventoryService.maxAttempts=2",
        "resilience4j.retry.instances.inventoryService.ignoreExceptions=com.ecommerce.order.domain.exception.OutOfStockException",
        "resilience4j.bulkhead.instances.inventoryService.maxConcurrentCalls=20"
})
class OrderSyncIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @org.springframework.boot.test.mock.mockito.MockBean
    private org.springframework.kafka.core.KafkaTemplate<String, String> kafkaTemplate;

    @Test
    @DisplayName("End-to-end: should place order when inventory returns available: true")
    void shouldPlaceOrder_whenInventoryStockAvailable() throws Exception {
        stubFor(get(urlPathEqualTo("/api/v1/inventory/check"))
                .withQueryParam("productId", equalTo("1"))
                .withQueryParam("quantity", equalTo("2"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"productId\": 1, \"requestedQuantity\": 2, \"available\": true}")));

        CreateOrderRequest request = new CreateOrderRequest(List.of(
                new OrderItemRequest(1L, 2, new BigDecimal("49.99"))
        ));

        mockMvc.perform(post("/api/v1/orders")
                        .header("X-User-Id", "customer-integration")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId").isNotEmpty())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.totalAmount").value(99.98));
    }

    @Test
    @DisplayName("End-to-end: should return 409 OUT_OF_STOCK when inventory returns available: false")
    void shouldReturn409_whenInventoryStockMissing() throws Exception {
        stubFor(get(urlPathEqualTo("/api/v1/inventory/check"))
                .withQueryParam("productId", equalTo("2"))
                .withQueryParam("quantity", equalTo("10"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"productId\": 2, \"requestedQuantity\": 10, \"available\": false}")));

        CreateOrderRequest request = new CreateOrderRequest(List.of(
                new OrderItemRequest(2L, 10, new BigDecimal("19.99"))
        ));

        mockMvc.perform(post("/api/v1/orders")
                        .header("X-User-Id", "customer-integration")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OUT_OF_STOCK"))
                .andExpect(jsonPath("$.productId").value(2));
    }

    @Test
    @DisplayName("End-to-end: should return 503 STOCK_CHECK_UNAVAILABLE when inventory service errors out")
    void shouldReturn503_whenInventoryServiceFails() throws Exception {
        stubFor(get(urlPathEqualTo("/api/v1/inventory/check"))
                .withQueryParam("productId", equalTo("3"))
                .withQueryParam("quantity", equalTo("1"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\": \"Internal Server Error\"}")));

        CreateOrderRequest request = new CreateOrderRequest(List.of(
                new OrderItemRequest(3L, 1, new BigDecimal("10.00"))
        ));

        mockMvc.perform(post("/api/v1/orders")
                        .header("X-User-Id", "customer-integration")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("STOCK_CHECK_UNAVAILABLE"));
    }

    @Test
    @DisplayName("Failure scenario 2: a slow inventory is cut off by the TimeLimiter and answered with 503, nothing saved")
    void shouldReturn503_whenInventoryIsTooSlow() throws Exception {
        stubFor(get(urlPathEqualTo("/api/v1/inventory/check"))
                .withQueryParam("productId", equalTo("4"))
                .willReturn(aResponse()
                        .withFixedDelay(3_000)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"productId\": 4, \"requestedQuantity\": 1, \"available\": true}")));

        CreateOrderRequest request = new CreateOrderRequest(List.of(
                new OrderItemRequest(4L, 1, new BigDecimal("10.00"))
        ));

        mockMvc.perform(post("/api/v1/orders")
                        .header("X-User-Id", "customer-slow")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("STOCK_CHECK_UNAVAILABLE"));
    }

    @Test
    @DisplayName("Retry: a failed first stock check is retried once, so the order is still accepted")
    void shouldAcceptOrder_whenInventoryFailsOnceThenRecovers() throws Exception {
        stubFor(get(urlPathEqualTo("/api/v1/inventory/check")).withQueryParam("productId", equalTo("5"))
                .inScenario("flaky").whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("recovered"));
        stubFor(get(urlPathEqualTo("/api/v1/inventory/check")).withQueryParam("productId", equalTo("5"))
                .inScenario("flaky").whenScenarioStateIs("recovered")
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"productId\": 5, \"requestedQuantity\": 1, \"available\": true}")));

        CreateOrderRequest request = new CreateOrderRequest(List.of(
                new OrderItemRequest(5L, 1, new BigDecimal("10.00"))
        ));

        mockMvc.perform(post("/api/v1/orders")
                        .header("X-User-Id", "customer-retry")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }
}
