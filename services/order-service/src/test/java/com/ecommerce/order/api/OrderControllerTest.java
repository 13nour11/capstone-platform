package com.ecommerce.order.api;

import com.ecommerce.order.api.dto.CreateOrderRequest;
import com.ecommerce.order.api.dto.OrderItemRequest;
import com.ecommerce.order.api.dto.OrderItemResponse;
import com.ecommerce.order.api.dto.OrderResponse;
import com.ecommerce.order.application.OrderService;
import com.ecommerce.order.domain.OrderStatus;
import com.ecommerce.order.domain.exception.OrderNotFoundException;
import com.ecommerce.order.domain.exception.OutOfStockException;
import com.ecommerce.order.domain.exception.ServiceUnavailableException;
import com.ecommerce.order.infrastructure.security.KeycloakRealmRoleConverter;
import com.ecommerce.order.infrastructure.security.SecurityConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderController.class)
@Import(SecurityConfig.class)
class OrderControllerTest {

    private static final String CUSTOMER_SUB = "customer-1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private OrderService orderService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("POST /api/v1/orders without a token should return 401")
    void shouldReturn401_whenTokenMissing() throws Exception {
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(oneItem(2))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/v1/orders should price the order for the customer in the token")
    void shouldReturn201Created_whenOrderPlacedSuccessfully() throws Exception {
        OrderResponse expectedResponse = new OrderResponse(
                "ord-100",
                CUSTOMER_SUB,
                new BigDecimal("99.98"),
                OrderStatus.PENDING,
                List.of(new OrderItemResponse(1L, 1L, 2, new BigDecimal("49.99"), new BigDecimal("99.98"))),
                Instant.now()
        );

        when(orderService.createOrder(eq(CUSTOMER_SUB), any(CreateOrderRequest.class)))
                .thenReturn(expectedResponse);

        mockMvc.perform(post("/api/v1/orders")
                        .with(customer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(oneItem(2)))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId").value("ord-100"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.totalAmount").value(99.98));
    }

    @Test
    @DisplayName("A forged X-User-Id header cannot change whose order is created")
    void shouldIgnoreClientSuppliedUserHeader() throws Exception {
        when(orderService.createOrder(eq(CUSTOMER_SUB), any(CreateOrderRequest.class)))
                .thenReturn(new OrderResponse("ord-100", CUSTOMER_SUB, new BigDecimal("99.98"),
                        OrderStatus.PENDING, List.of(), Instant.now()));

        mockMvc.perform(post("/api/v1/orders")
                        .with(customer())
                        .header("X-User-Id", "somebody-else")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(oneItem(2)))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customerId").value(CUSTOMER_SUB));
    }

    @Test
    @DisplayName("POST /api/v1/orders should return 409 Conflict with OUT_OF_STOCK code when stock missing")
    void shouldReturn409Conflict_whenStockMissing() throws Exception {
        when(orderService.createOrder(eq(CUSTOMER_SUB), any(CreateOrderRequest.class)))
                .thenThrow(new OutOfStockException(1L));

        mockMvc.perform(post("/api/v1/orders")
                        .with(customer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(oneItem(5)))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OUT_OF_STOCK"))
                .andExpect(jsonPath("$.title").value("Out of stock"))
                .andExpect(jsonPath("$.productId").value(1));
    }

    @Test
    @DisplayName("POST /api/v1/orders should return 503 Service Unavailable when inventory service is down")
    void shouldReturn503Unavailable_whenInventoryDown() throws Exception {
        when(orderService.createOrder(eq(CUSTOMER_SUB), any(CreateOrderRequest.class)))
                .thenThrow(new ServiceUnavailableException("Stock check unavailable, please retry"));

        mockMvc.perform(post("/api/v1/orders")
                        .with(customer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(oneItem(2)))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("STOCK_CHECK_UNAVAILABLE"))
                .andExpect(jsonPath("$.title").value("Service Unavailable"));
    }

    @Test
    @DisplayName("GET /api/v1/orders/{id} should return 200 with order details")
    void shouldReturn200_whenGetOrderById() throws Exception {
        OrderResponse expectedResponse = new OrderResponse(
                "ord-100",
                CUSTOMER_SUB,
                new BigDecimal("99.98"),
                OrderStatus.PENDING,
                List.of(),
                Instant.now()
        );

        when(orderService.getOrderForCustomer("ord-100", CUSTOMER_SUB, false)).thenReturn(expectedResponse);

        mockMvc.perform(get("/api/v1/orders/ord-100").with(customer()).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value("ord-100"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    @DisplayName("GET /api/v1/orders/{id} should return 404 when order does not exist or belongs to someone else")
    void shouldReturn404_whenOrderNotFound() throws Exception {
        when(orderService.getOrderForCustomer("ord-999", CUSTOMER_SUB, false))
                .thenThrow(new OrderNotFoundException("ord-999"));

        mockMvc.perform(get("/api/v1/orders/ord-999").with(customer()).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"))
                .andExpect(jsonPath("$.title").value("Order Not Found"));
    }

    @Test
    @DisplayName("An ADMIN may read any customer's order")
    void shouldLetAdminReadAnyOrder() throws Exception {
        when(orderService.getOrderForCustomer("ord-100", "admin-1", true))
                .thenReturn(new OrderResponse("ord-100", CUSTOMER_SUB, new BigDecimal("99.98"),
                        OrderStatus.PENDING, List.of(), Instant.now()));

        mockMvc.perform(get("/api/v1/orders/ord-100").with(role("admin-1", "ADMIN")).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value("ord-100"));
    }

    private static CreateOrderRequest oneItem(int quantity) {
        return new CreateOrderRequest(List.of(new OrderItemRequest(1L, quantity)));
    }

    private static JwtRequestPostProcessor customer() {
        return role(CUSTOMER_SUB, "CUSTOMER");
    }

    /** A Keycloak-shaped token; the role converter under test reads realm_access.roles. */
    private static JwtRequestPostProcessor role(String subject, String role) {
        return jwt().jwt(token -> token.subject(subject).claim("realm_access", Map.of("roles", List.of(role))))
                .authorities(new KeycloakRealmRoleConverter());
    }
}
