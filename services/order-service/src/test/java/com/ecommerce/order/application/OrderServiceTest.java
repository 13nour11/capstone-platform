package com.ecommerce.order.application;

import com.ecommerce.order.api.dto.CreateOrderRequest;
import com.ecommerce.order.api.dto.OrderItemRequest;
import com.ecommerce.order.api.dto.OrderResponse;
import com.ecommerce.order.domain.Order;
import com.ecommerce.order.domain.OrderStatus;
import com.ecommerce.order.domain.exception.OrderNotFoundException;
import com.ecommerce.order.domain.exception.OutOfStockException;
import com.ecommerce.order.domain.exception.ServiceUnavailableException;
import com.ecommerce.order.infrastructure.client.InventoryServiceClient;
import com.ecommerce.order.infrastructure.outbox.OutboxTraceContext;
import com.ecommerce.order.infrastructure.persistence.OrderRepository;
import com.ecommerce.order.infrastructure.persistence.OutboxEventRepository;
import com.ecommerce.order.infrastructure.persistence.ProcessedEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private InventoryServiceClient inventoryServiceClient;

    @Mock
    private OutboxTraceContext traceContext;

    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    private OrderServiceImpl orderService;

    private CreateOrderRequest createOrderRequest;

    @BeforeEach
    void setUp() {
        objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        objectMapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        orderService = new OrderServiceImpl(
                orderRepository,
                outboxEventRepository,
                processedEventRepository,
                inventoryServiceClient,
                objectMapper,
                TransactionOperations.withoutTransaction(),
                traceContext
        );
        createOrderRequest = new CreateOrderRequest(List.of(
                new OrderItemRequest(1L, 2, new BigDecimal("49.99"))
        ));
    }

    @Test
    @DisplayName("Should create order with PENDING status when stock is available")
    void shouldCreateOrderSuccessfully_whenStockIsAvailable() {
        doNothing().when(inventoryServiceClient).verifyStockAvailability(1L, 2);
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OrderResponse response = orderService.createOrder("cust-1", createOrderRequest);

        assertThat(response).isNotNull();
        assertThat(response.customerId()).isEqualTo("cust-1");
        assertThat(response.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(response.totalAmount()).isEqualByComparingTo(new BigDecimal("99.98"));
        assertThat(response.items()).hasSize(1);
        verify(inventoryServiceClient).verifyStockAvailability(1L, 2);
        verify(orderRepository).save(any(Order.class));
    }

    @Test
    @DisplayName("Should throw OutOfStockException and never save order when stock is unavailable")
    void shouldThrowOutOfStockException_andNotSaveOrder_whenStockUnavailable() {
        doThrow(new OutOfStockException(1L))
                .when(inventoryServiceClient).verifyStockAvailability(1L, 2);

        assertThatThrownBy(() -> orderService.createOrder("cust-1", createOrderRequest))
                .isInstanceOf(OutOfStockException.class)
                .hasMessageContaining("Product 1 is out of stock");

        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    @DisplayName("Should throw ServiceUnavailableException and never save order when inventory service fails")
    void shouldThrowServiceUnavailableException_andNotSaveOrder_whenInventoryFails() {
        doThrow(new ServiceUnavailableException("Stock check unavailable, please retry"))
                .when(inventoryServiceClient).verifyStockAvailability(1L, 2);

        assertThatThrownBy(() -> orderService.createOrder("cust-1", createOrderRequest))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageContaining("Stock check unavailable");

        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    @DisplayName("Should return order details when order exists")
    void shouldGetOrderById_whenOrderExists() {
        Order order = Order.builder()
                .id("ord-123")
                .customerId("cust-1")
                .totalAmount(new BigDecimal("99.98"))
                .status(OrderStatus.PENDING)
                .createdAt(Instant.now())
                .build();
        when(orderRepository.findById("ord-123")).thenReturn(Optional.of(order));

        OrderResponse response = orderService.getOrderById("cust-1", "ord-123");

        assertThat(response.orderId()).isEqualTo("ord-123");
        assertThat(response.status()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    @DisplayName("Should throw OrderNotFoundException when order does not exist")
    void shouldThrowException_whenOrderNotFound() {
        when(orderRepository.findById("ord-999")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.getOrderById("cust-1", "ord-999"))
                .isInstanceOf(OrderNotFoundException.class)
                .hasMessageContaining("ord-999");
    }

    @Test
    @DisplayName("FR-10: another customer's order is reported as not found")
    void shouldThrowNotFound_whenOrderBelongsToAnotherCustomer() {
        Order order = Order.builder()
                .id("ord-123")
                .customerId("cust-1")
                .totalAmount(new BigDecimal("99.98"))
                .status(OrderStatus.PENDING)
                .createdAt(Instant.now())
                .build();
        when(orderRepository.findById("ord-123")).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.getOrderById("cust-2", "ord-123"))
                .isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    @DisplayName("FR-05: the order and its OrderPlaced outbox row are written with the same event id")
    void shouldWriteOrderPlacedToOutbox_whenOrderIsCreated() {
        doNothing().when(inventoryServiceClient).verifyStockAvailability(1L, 2);
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(traceContext.currentTraceparent()).thenReturn("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");

        orderService.createOrder("cust-1", createOrderRequest);

        org.mockito.ArgumentCaptor<com.ecommerce.order.domain.OutboxEvent> outbox =
                org.mockito.ArgumentCaptor.forClass(com.ecommerce.order.domain.OutboxEvent.class);
        verify(outboxEventRepository).save(outbox.capture());
        assertThat(outbox.getValue().getEventType()).isEqualTo("OrderPlaced");
        assertThat(outbox.getValue().getPayload()).contains("\"eventId\":\"" + outbox.getValue().getId() + "\"");
        assertThat(outbox.getValue().getTraceparent()).startsWith("00-4bf92f3577b34da6");
    }
}
