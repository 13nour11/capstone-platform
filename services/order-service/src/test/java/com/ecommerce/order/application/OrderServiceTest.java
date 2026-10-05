package com.ecommerce.order.application;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import com.ecommerce.order.domain.OutboxEvent;
import org.mockito.ArgumentCaptor;
import java.time.Duration;
import com.ecommerce.order.api.dto.CreateOrderRequest;
import com.ecommerce.order.api.dto.OrderItemRequest;
import com.ecommerce.order.api.dto.OrderResponse;
import com.ecommerce.order.domain.Order;
import com.ecommerce.order.domain.OrderStatus;
import com.ecommerce.order.domain.exception.IllegalOrderStateException;
import com.ecommerce.order.domain.exception.OrderNotFoundException;
import com.ecommerce.order.domain.exception.OutOfStockException;
import com.ecommerce.order.domain.exception.ServiceUnavailableException;
import com.ecommerce.order.infrastructure.client.InventoryServiceClient;
import com.ecommerce.order.infrastructure.client.CachedProductPrices;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
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
    private CachedProductPrices productPrices;

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
                productPrices,
                objectMapper,
                directTransactionTemplate(),
                Tracer.NOOP,
                Propagator.NOOP
        );
        createOrderRequest = new CreateOrderRequest(List.of(
                new OrderItemRequest(1L, 2)
        ));
        // The catalogue prices the order; tests that fail before pricing simply never use this.
        lenient().when(productPrices.currentPrice(1L)).thenReturn(new BigDecimal("49.99"));
    }

    /** Runs the callback straight through, so the test exercises the real transaction boundary call. */
    private static TransactionTemplate directTransactionTemplate() {
        return new TransactionTemplate(mock(PlatformTransactionManager.class));
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
    @DisplayName("Should return order details to the customer who placed it")
    void shouldGetOrder_whenCallerOwnsIt() {
        when(orderRepository.findById("ord-123")).thenReturn(Optional.of(order("cust-1")));

        OrderResponse response = orderService.getOrderForCustomer("ord-123", "cust-1", false);

        assertThat(response.orderId()).isEqualTo("ord-123");
        assertThat(response.status()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    @DisplayName("Another customer's order is reported as not found, so ids cannot be probed")
    void shouldHideOrderOwnedBySomeoneElse() {
        when(orderRepository.findById("ord-123")).thenReturn(Optional.of(order("cust-1")));

        assertThatThrownBy(() -> orderService.getOrderForCustomer("ord-123", "cust-2", false))
                .isInstanceOf(OrderNotFoundException.class)
                .hasMessageContaining("ord-123");
    }

    @Test
    @DisplayName("An ADMIN may read any customer's order")
    void shouldLetAdminReadAnyOrder() {
        when(orderRepository.findById("ord-123")).thenReturn(Optional.of(order("cust-1")));

        assertThat(orderService.getOrderForCustomer("ord-123", "admin-1", true).orderId()).isEqualTo("ord-123");
    }

    @Test
    @DisplayName("Should throw OrderNotFoundException when order does not exist")
    void shouldThrowException_whenOrderNotFound() {
        when(orderRepository.findById("ord-999")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.getOrderForCustomer("ord-999", "cust-1", false))
                .isInstanceOf(OrderNotFoundException.class)
                .hasMessageContaining("ord-999");
    }

    @Test
    @DisplayName("F10: a stuck PENDING order is cancelled and OrderCancelled is queued to the outbox")
    void shouldCancelOrdersPendingLongerThanTimeout() {
        Order stuck = order("cust-1");
        when(orderRepository.findByStatusAndCreatedAtBefore(eq(OrderStatus.PENDING), any(Instant.class)))
                .thenReturn(List.of(stuck));

        int cancelled = orderService.cancelOrdersPendingLongerThan(Duration.ofMinutes(10));

        assertThat(cancelled).isEqualTo(1);
        assertThat(stuck.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        ArgumentCaptor<OutboxEvent> outbox = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(outbox.capture());
        assertThat(outbox.getValue().getEventType()).isEqualTo("OrderCancelled");
        assertThat(outbox.getValue().getPayload()).contains("SAGA_TIMEOUT");
    }

    @Test
    @DisplayName("NFR-10: a cancel for an order the timeout already cancelled is a no-op, not a DLT record")
    void shouldIgnoreCancel_whenOrderIsAlreadyCancelled() {
        Order cancelled = order("cust-1");
        cancelled.cancel();
        when(orderRepository.findById("ord-123")).thenReturn(Optional.of(cancelled));

        orderService.cancelOrder("evt-late-fail", "ord-123", "CARD_DECLINED");

        verify(outboxEventRepository, never()).save(any());
        verify(processedEventRepository).save(any());
    }

    @Test
    @DisplayName("NFR-10: a second confirmation for a CONFIRMED order is a no-op")
    void shouldIgnoreConfirm_whenOrderIsAlreadyConfirmed() {
        Order confirmed = order("cust-1");
        confirmed.confirm();
        when(orderRepository.findById("ord-123")).thenReturn(Optional.of(confirmed));

        orderService.confirmOrder("evt-dup-pay", "ord-123");

        verify(outboxEventRepository, never()).save(any());
        verify(processedEventRepository).save(any());
    }

    @Test
    @DisplayName("NFR-10: PaymentCompleted for a CANCELLED order is a conflict and is not marked processed")
    void shouldRejectConfirm_whenOrderIsAlreadyCancelled() {
        Order cancelled = order("cust-1");
        cancelled.cancel();
        when(orderRepository.findById("ord-123")).thenReturn(Optional.of(cancelled));

        assertThatThrownBy(() -> orderService.confirmOrder("evt-late-pay", "ord-123"))
                .isInstanceOf(IllegalOrderStateException.class);

        verify(outboxEventRepository, never()).save(any());
        verify(processedEventRepository, never()).save(any());
    }

    private static Order order(String customerId) {
        return Order.builder()
                .id("ord-123")
                .customerId(customerId)
                .totalAmount(new BigDecimal("99.98"))
                .status(OrderStatus.PENDING)
                .createdAt(Instant.now())
                .build();
    }
}
