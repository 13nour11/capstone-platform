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
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
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
        "product.service.url=http://localhost:${wiremock.server.port}"
})
class OrderSyncIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @org.springframework.boot.test.mock.mockito.MockBean
    private org.springframework.kafka.core.KafkaTemplate<String, String> kafkaTemplate;

    // No Keycloak in this slice: requests carry an already-decoded token via jwt().
    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static JwtRequestPostProcessor customer() {
        return jwt().jwt(token -> token.subject("customer-integration")
                        .claim("realm_access", Map.of("roles", List.of("CUSTOMER"))))
                .authorities(new com.ecommerce.order.infrastructure.security.KeycloakRealmRoleConverter());
    }

    private static void stubPrice(long productId, String price) {
        stubFor(get(urlPathEqualTo("/api/v1/products/" + productId))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\": " + productId + ", \"price\": " + price + "}")));
    }

    @Test
    @DisplayName("End-to-end: should place order when inventory returns available: true")
    void shouldPlaceOrder_whenInventoryStockAvailable() throws Exception {
        stubFor(get(urlPathEqualTo("/api/v1/inventory/check"))
                .withQueryParam("productId", equalTo("1"))
                .withQueryParam("quantity", equalTo("2"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"productId\": 1, \"requestedQuantity\": 2, \"available\": true}")));
        stubPrice(1L, "49.99");

        CreateOrderRequest request = new CreateOrderRequest(List.of(
                new OrderItemRequest(1L, 2)
        ));

        mockMvc.perform(post("/api/v1/orders")
                        .with(customer())
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
                new OrderItemRequest(2L, 10)
        ));

        mockMvc.perform(post("/api/v1/orders")
                        .with(customer())
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
                new OrderItemRequest(3L, 1)
        ));

        mockMvc.perform(post("/api/v1/orders")
                        .with(customer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("STOCK_CHECK_UNAVAILABLE"));
    }
}
