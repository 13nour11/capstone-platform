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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderController.class)
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private OrderService orderService;

    @Test
    @DisplayName("POST /api/v1/orders should return 201 Created with status PENDING when successful")
    void shouldReturn201Created_whenOrderPlacedSuccessfully() throws Exception {
        CreateOrderRequest request = new CreateOrderRequest(List.of(
                new OrderItemRequest(1L, 2, new BigDecimal("49.99"))
        ));

        OrderResponse expectedResponse = new OrderResponse(
                "ord-100",
                "customer-1",
                new BigDecimal("99.98"),
                OrderStatus.PENDING,
                List.of(new OrderItemResponse(1L, 1L, 2, new BigDecimal("49.99"), new BigDecimal("99.98"))),
                Instant.now()
        );

        when(orderService.createOrder(eq("customer-1"), any(CreateOrderRequest.class)))
                .thenReturn(expectedResponse);

        mockMvc.perform(post("/api/v1/orders")
                        .header("X-User-Id", "customer-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId").value("ord-100"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.totalAmount").value(99.98));
    }

    @Test
    @DisplayName("POST /api/v1/orders should return 409 Conflict with OUT_OF_STOCK code when stock missing")
    void shouldReturn409Conflict_whenStockMissing() throws Exception {
        CreateOrderRequest request = new CreateOrderRequest(List.of(
                new OrderItemRequest(1L, 5, new BigDecimal("49.99"))
        ));

        when(orderService.createOrder(eq("customer-1"), any(CreateOrderRequest.class)))
                .thenThrow(new OutOfStockException(1L));

        mockMvc.perform(post("/api/v1/orders")
                        .header("X-User-Id", "customer-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OUT_OF_STOCK"))
                .andExpect(jsonPath("$.title").value("Out of stock"))
                .andExpect(jsonPath("$.productId").value(1));
    }

    @Test
    @DisplayName("POST /api/v1/orders should return 503 Service Unavailable when inventory service is down")
    void shouldReturn503Unavailable_whenInventoryDown() throws Exception {
        CreateOrderRequest request = new CreateOrderRequest(List.of(
                new OrderItemRequest(1L, 2, new BigDecimal("49.99"))
        ));

        when(orderService.createOrder(eq("customer-1"), any(CreateOrderRequest.class)))
                .thenThrow(new ServiceUnavailableException("Stock check unavailable, please retry"));

        mockMvc.perform(post("/api/v1/orders")
                        .header("X-User-Id", "customer-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
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
                "customer-1",
                new BigDecimal("99.98"),
                OrderStatus.PENDING,
                List.of(),
                Instant.now()
        );

        when(orderService.getOrderById("ord-100")).thenReturn(expectedResponse);

        mockMvc.perform(get("/api/v1/orders/ord-100")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value("ord-100"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    @DisplayName("GET /api/v1/orders/{id} should return 404 when order does not exist")
    void shouldReturn404_whenOrderNotFound() throws Exception {
        when(orderService.getOrderById("ord-999"))
                .thenThrow(new OrderNotFoundException("ord-999"));

        mockMvc.perform(get("/api/v1/orders/ord-999")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"))
                .andExpect(jsonPath("$.title").value("Order Not Found"));
    }
}
