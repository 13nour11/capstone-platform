package com.ecommerce.order;

import com.ecommerce.order.api.dto.CreateOrderRequest;
import com.ecommerce.order.api.dto.OrderItemRequest;
import com.ecommerce.order.infrastructure.security.KeycloakRealmRoleConverter;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.contract.wiremock.AutoConfigureWireMock;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.resetAllRequests;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FR-14 end to end against stubs: the stock check carries order-service's own client-credentials
 * token (not the customer's), and the token is fetched once and reused.
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureWireMock(port = 0)
@TestPropertySource(properties = {
        "inventory.service.url=http://localhost:${wiremock.server.port}",
        "product.service.url=http://localhost:${wiremock.server.port}",
        "order.service-auth.enabled=true",
        "spring.security.oauth2.client.registration.order-service.provider=keycloak",
        "spring.security.oauth2.client.registration.order-service.client-id=order-service",
        "spring.security.oauth2.client.registration.order-service.client-secret=test-secret",
        "spring.security.oauth2.client.registration.order-service.authorization-grant-type=client_credentials",
        "spring.security.oauth2.client.provider.keycloak.token-uri=http://localhost:${wiremock.server.port}/realms/test/token",
        "resilience4j.retry.instances.inventoryService.maxAttempts=1"
})
class ServiceTokenIntegrationTest {

    private static final String TOKEN_PATH = "/realms/test/token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CircuitBreakerRegistry circuitBreakers;

    @Autowired
    private OAuth2AuthorizedClientService authorizedClients;

    @MockitoBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void setUp() {
        resetAllRequests();
        circuitBreakers.circuitBreaker("inventoryService").reset();
        authorizedClients.removeAuthorizedClient("order-service", "order-service");
        stubFor(get(urlPathEqualTo("/api/v1/products/1")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{\"id\": 1, \"price\": 10.00}")));
        stubFor(get(urlPathEqualTo("/api/v1/inventory/check")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"productId\": 1, \"requestedQuantity\": 1, \"available\": true}")));
    }

    @Test
    @DisplayName("FR-14: the stock check carries the service token, fetched once for several orders")
    void shouldSendServiceToken_andReuseIt() throws Exception {
        stubFor(post(urlPathEqualTo(TOKEN_PATH)).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"access_token\":\"svc-token-1\",\"token_type\":\"Bearer\",\"expires_in\":300}")));

        placeOrder().andExpect(status().isCreated());
        placeOrder().andExpect(status().isCreated());

        verify(2, getRequestedFor(urlPathEqualTo("/api/v1/inventory/check"))
                .withHeader("Authorization", equalTo("Bearer svc-token-1")));
        verify(1, postRequestedFor(urlPathEqualTo(TOKEN_PATH))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.containing("grant_type=client_credentials")));
    }

    @Test
    @DisplayName("FR-14: if Keycloak refuses the token, the order is answered 503 and no order is stored")
    void shouldReturn503_whenTokenCannotBeObtained() throws Exception {
        stubFor(post(urlPathEqualTo(TOKEN_PATH)).willReturn(aResponse().withStatus(401)
                .withHeader("Content-Type", "application/json").withBody("{\"error\":\"unauthorized_client\"}")));

        placeOrder().andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("STOCK_CHECK_UNAVAILABLE"));
        verify(0, getRequestedFor(urlPathEqualTo("/api/v1/inventory/check")));
    }

    private ResultActions placeOrder() throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/orders")
                .with(jwt().jwt(token -> token.subject("customer-fr14")
                                .claim("realm_access", Map.of("roles", List.of("CUSTOMER"))))
                        .authorities(new KeycloakRealmRoleConverter()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateOrderRequest(List.of(new OrderItemRequest(1L, 1))))));
    }
}
