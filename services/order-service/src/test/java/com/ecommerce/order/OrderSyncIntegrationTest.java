package com.ecommerce.order;

import com.ecommerce.order.api.dto.CreateOrderRequest;
import com.ecommerce.order.api.dto.OrderItemRequest;
import com.ecommerce.order.domain.exception.ServiceUnavailableException;
import com.ecommerce.order.infrastructure.client.InventoryServiceClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
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

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.findAll;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
        "product.service.url=http://localhost:${wiremock.server.port}",
        // The resilience settings of config-repo/order-service.yml that these tests depend on
        // (ResilienceConfigContractTest keeps the two in step). The bulkhead is shrunk to 1 so the
        // test can fill it with a single slow call.
        "resilience4j.circuitbreaker.instances.inventoryService.slidingWindowSize=10",
        "resilience4j.circuitbreaker.instances.inventoryService.minimumNumberOfCalls=5",
        "resilience4j.circuitbreaker.instances.inventoryService.failureRateThreshold=50",
        "resilience4j.circuitbreaker.instances.inventoryService.ignoreExceptions[0]=com.ecommerce.order.domain.exception.OutOfStockException",
        "resilience4j.retry.instances.inventoryService.maxAttempts=2",
        "resilience4j.retry.instances.inventoryService.waitDuration=100ms",
        "resilience4j.retry.instances.inventoryService.ignoreExceptions[0]=com.ecommerce.order.domain.exception.OutOfStockException",
        "resilience4j.retry.instances.inventoryService.ignoreExceptions[1]=io.github.resilience4j.circuitbreaker.CallNotPermittedException",
        "resilience4j.retry.instances.inventoryService.ignoreExceptions[2]=io.github.resilience4j.bulkhead.BulkheadFullException",
        "resilience4j.bulkhead.instances.inventoryService.maxConcurrentCalls=1",
        "resilience4j.bulkhead.instances.inventoryService.maxWaitDuration=0",
        "spring.cloud.openfeign.client.config.inventory-service.readTimeout=1000"
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

    @Autowired
    private CircuitBreakerRegistry circuitBreakers;

    @Autowired
    private InventoryServiceClient inventoryServiceClient;

    @BeforeEach
    void closeTheCircuit() {
        circuitBreakers.circuitBreaker("inventoryService").reset();
    }

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

    @Test
    @DisplayName("FR-06: a run of sold-out products is answered 409 and never opens the circuit")
    void shouldKeepCircuitClosed_whenManyOrdersAreOutOfStock() throws Exception {
        stubFor(get(urlPathEqualTo("/api/v1/inventory/check"))
                .withQueryParam("productId", equalTo("4"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"productId\": 4, \"requestedQuantity\": 1, \"available\": false}")));

        for (int i = 0; i < 8; i++) {
            placeOrder(4L, 1).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("OUT_OF_STOCK"));
        }

        // One call per order: a business "no" is not retried.
        verify(8, getRequestedFor(urlPathEqualTo("/api/v1/inventory/check")).withQueryParam("productId", equalTo("4")));
        assertThat(circuitBreakers.circuitBreaker("inventoryService").getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("NFR-01: a slow inventory is cut off by the Feign read timeout and answered 503")
    void shouldReturn503_whenInventoryIsTooSlow() throws Exception {
        stubFor(get(urlPathEqualTo("/api/v1/inventory/check"))
                .withQueryParam("productId", equalTo("5"))
                .willReturn(aResponse()
                        .withFixedDelay(3_000)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"productId\": 5, \"requestedQuantity\": 1, \"available\": true}")));

        long started = System.nanoTime();
        placeOrder(5L, 1).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("STOCK_CHECK_UNAVAILABLE"));

        // Two attempts of ~1 s each, well before the 3 s the slow inventory would have taken twice.
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(4_500));
    }

    @Test
    @DisplayName("NFR-01: repeated inventory failures open the circuit, and further orders fail fast with 503")
    void shouldOpenCircuit_whenInventoryKeepsFailing() throws Exception {
        stubFor(get(urlPathEqualTo("/api/v1/inventory/check"))
                .withQueryParam("productId", equalTo("6"))
                .willReturn(aResponse().withStatus(500)));

        for (int i = 0; i < 5; i++) {
            placeOrder(6L, 1).andExpect(status().isServiceUnavailable());
        }
        assertThat(circuitBreakers.circuitBreaker("inventoryService").getState()).isEqualTo(CircuitBreaker.State.OPEN);

        int callsBefore = findAll(getRequestedFor(urlPathEqualTo("/api/v1/inventory/check"))).size();
        placeOrder(6L, 1).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("STOCK_CHECK_UNAVAILABLE"));
        assertThat(findAll(getRequestedFor(urlPathEqualTo("/api/v1/inventory/check")))).hasSize(callsBefore);
    }

    @Test
    @DisplayName("Bulkhead: when every slot is busy on a slow inventory, the next caller gets 503 at once")
    void shouldRejectImmediately_whenBulkheadIsFull() throws Exception {
        stubFor(get(urlPathEqualTo("/api/v1/inventory/check"))
                .withQueryParam("productId", equalTo("7"))
                .willReturn(aResponse()
                        .withFixedDelay(800)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"productId\": 7, \"requestedQuantity\": 1, \"available\": true}")));

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> slow = pool.submit(() -> inventoryServiceClient.verifyStockAvailability(7L, 1));
            Thread.sleep(200);

            long started = System.nanoTime();
            assertThatThrownBy(() -> inventoryServiceClient.verifyStockAvailability(7L, 1))
                    .isInstanceOf(ServiceUnavailableException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(400));

            slow.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    private org.springframework.test.web.servlet.ResultActions placeOrder(long productId, int quantity) throws Exception {
        stubPrice(productId, "10.00");
        return mockMvc.perform(post("/api/v1/orders")
                .with(customer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        new CreateOrderRequest(List.of(new OrderItemRequest(productId, quantity))))));
    }
}
