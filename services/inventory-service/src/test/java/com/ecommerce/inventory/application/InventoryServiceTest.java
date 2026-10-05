package com.ecommerce.inventory.application;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import com.ecommerce.inventory.api.dto.CheckStockResponse;
import com.ecommerce.inventory.api.dto.StockResponse;
import com.ecommerce.inventory.domain.OutboxEvent;
import com.ecommerce.inventory.domain.ProcessedEventId;
import com.ecommerce.inventory.domain.Reservation;
import com.ecommerce.inventory.domain.ReservationStatus;
import com.ecommerce.inventory.domain.Stock;
import com.ecommerce.inventory.domain.event.OrderItemPayload;
import com.ecommerce.inventory.domain.event.OrderPlaced;
import com.ecommerce.inventory.domain.exception.ProductNotFoundException;
import com.ecommerce.inventory.infrastructure.persistence.CancelledOrderRepository;
import com.ecommerce.inventory.infrastructure.persistence.OutboxEventRepository;
import com.ecommerce.inventory.infrastructure.persistence.ProcessedEventRepository;
import com.ecommerce.inventory.infrastructure.persistence.ReservationRepository;
import com.ecommerce.inventory.infrastructure.persistence.StockRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

    @Mock
    private StockRepository stockRepository;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private CancelledOrderRepository cancelledOrderRepository;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private ProcessedEventRepository processedEventRepository;

    private ObjectMapper objectMapper;

    private InventoryServiceImpl inventoryService;

    private Stock testStock;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        inventoryService = new InventoryServiceImpl(
                stockRepository,
                reservationRepository,
                cancelledOrderRepository,
                outboxEventRepository,
                processedEventRepository,
                objectMapper,
                Tracer.NOOP,
                Propagator.NOOP
        );

        testStock = Stock.builder()
                .productId(101L)
                .available(20)
                .reserved(5)
                .version(0L)
                .build();
    }

    @Test
    @DisplayName("Should return available: true when stock is sufficient")
    void shouldReturnAvailableTrue_whenStockIsSufficient() {
        when(stockRepository.findById(101L)).thenReturn(Optional.of(testStock));

        CheckStockResponse response = inventoryService.checkStock(101L, 10);

        assertThat(response.productId()).isEqualTo(101L);
        assertThat(response.requestedQuantity()).isEqualTo(10);
        assertThat(response.available()).isTrue();
    }

    @Test
    @DisplayName("Should return available: false when stock is insufficient")
    void shouldReturnAvailableFalse_whenStockIsInsufficient() {
        when(stockRepository.findById(101L)).thenReturn(Optional.of(testStock));

        CheckStockResponse response = inventoryService.checkStock(101L, 25);

        assertThat(response.productId()).isEqualTo(101L);
        assertThat(response.requestedQuantity()).isEqualTo(25);
        assertThat(response.available()).isFalse();
    }

    @Test
    @DisplayName("Should return available: false when product does not exist")
    void shouldReturnAvailableFalse_whenProductDoesNotExist() {
        when(stockRepository.findById(999L)).thenReturn(Optional.empty());

        CheckStockResponse response = inventoryService.checkStock(999L, 5);

        assertThat(response.productId()).isEqualTo(999L);
        assertThat(response.available()).isFalse();
    }

    @Test
    @DisplayName("Should return stock details when product exists")
    void shouldReturnStockDetails_whenProductExists() {
        when(stockRepository.findById(101L)).thenReturn(Optional.of(testStock));

        StockResponse response = inventoryService.getStock(101L);

        assertThat(response.productId()).isEqualTo(101L);
        assertThat(response.available()).isEqualTo(20);
        assertThat(response.reserved()).isEqualTo(5);
    }

    @Test
    @DisplayName("Should throw ProductNotFoundException when product not found on getStock")
    void shouldThrowException_whenProductNotFound() {
        when(stockRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> inventoryService.getStock(999L))
                .isInstanceOf(ProductNotFoundException.class)
                .hasMessageContaining("999");
    }

    @Test
    @DisplayName("Should adjust available stock quantity successfully")
    void shouldAdjustStock_whenValidQuantityGiven() {
        when(stockRepository.findById(101L)).thenReturn(Optional.of(testStock));
        when(stockRepository.save(any(Stock.class))).thenAnswer(invocation -> invocation.getArgument(0));

        StockResponse response = inventoryService.adjustStock(101L, 50);

        assertThat(response.available()).isEqualTo(50);
        verify(stockRepository).save(any(Stock.class));
    }

    @Test
    @DisplayName("Saga: Should reserve stock and emit InventoryReserved on OrderPlaced")
    void shouldReserveStock_whenOrderPlacedAndStockAvailable() {
        OrderPlaced event = new OrderPlaced(
                "evt-1",
                "ord-1",
                "cust-1",
                new BigDecimal("100.00"),
                List.of(new OrderItemPayload(101L, 2, new BigDecimal("50.00"))),
                Instant.now()
        );

        when(processedEventRepository.existsById(any(ProcessedEventId.class))).thenReturn(false);
        when(stockRepository.reserveStockAtomic(101L, 2)).thenReturn(1);

        inventoryService.processOrderPlaced(event);

        verify(reservationRepository).save(any(Reservation.class));
        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().getEventType()).isEqualTo("InventoryReserved");
        assertThat(outboxCaptor.getValue().getAggregateId()).isEqualTo("ord-1");
    }

    @Test
    @DisplayName("Saga: Should emit InventoryReservationFailed when stock cannot be reserved")
    void shouldEmitReservationFailed_whenInsufficientStock() {
        OrderPlaced event = new OrderPlaced(
                "evt-2",
                "ord-2",
                "cust-1",
                new BigDecimal("100.00"),
                List.of(new OrderItemPayload(101L, 50, new BigDecimal("50.00"))),
                Instant.now()
        );

        when(processedEventRepository.existsById(any(ProcessedEventId.class))).thenReturn(false);
        when(stockRepository.reserveStockAtomic(101L, 50)).thenReturn(0);

        inventoryService.processOrderPlaced(event);

        verify(reservationRepository, never()).save(any(Reservation.class));
        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().getEventType()).isEqualTo("InventoryReservationFailed");
    }

    @Test
    @DisplayName("Saga: Should release reserved stock when releaseReservation is called")
    void shouldReleaseReservedStock() {
        Reservation reservation = new Reservation("ord-3", 101L, 3, ReservationStatus.RESERVED, Instant.now());
        when(processedEventRepository.existsById(any(ProcessedEventId.class))).thenReturn(false);
        when(reservationRepository.findByOrderId("ord-3")).thenReturn(List.of(reservation));
        when(stockRepository.releaseStockAtomic(101L, 3)).thenReturn(1);

        inventoryService.releaseReservation("evt-3", "ord-3", "PAYMENT_FAILED");

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RELEASED);
        verify(stockRepository).releaseStockAtomic(101L, 3);
        verify(reservationRepository).save(reservation);
    }

    @Test
    @DisplayName("NFR-05 Sweeper: Should release orphaned reservations of cancelled orders")
    void shouldReleaseOrphanedReservationsOfCancelledOrders() {
        Reservation reservation = new Reservation("ord-expired", 101L, 2, ReservationStatus.RESERVED, Instant.now().minusSeconds(40));
        when(reservationRepository.findSweepableForCancelledOrders(eq(ReservationStatus.RESERVED), any(Instant.class)))
                .thenReturn(List.of(reservation));
        when(stockRepository.releaseStockAtomic(101L, 2)).thenReturn(1);

        int count = inventoryService.releaseExpiredReservations(30);

        assertThat(count).isEqualTo(1);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RELEASED);
        verify(stockRepository).releaseStockAtomic(101L, 2);
    }

    @Test
    @DisplayName("Oversell guard: sweeper leaves stock of a still-pending order alone")
    void shouldNotReleaseReservationOfOrderStillAwaitingPayment() {
        // The query only returns reservations of cancelled orders, so an order awaiting a slow
        // payment yields nothing to sweep and keeps its stock.
        when(reservationRepository.findSweepableForCancelledOrders(eq(ReservationStatus.RESERVED), any(Instant.class)))
                .thenReturn(List.of());

        int count = inventoryService.releaseExpiredReservations(30);

        assertThat(count).isZero();
        verify(stockRepository, never()).releaseStockAtomic(any(), anyInt());
    }

    @Test
    @DisplayName("Confirm is idempotent across OrderConfirmed and PaymentCompleted")
    void shouldConsumeOnceWhenBothConfirmingEventsArrive() {
        Reservation reservation = new Reservation("ord-9", 101L, 1, ReservationStatus.RESERVED, Instant.now());
        when(processedEventRepository.existsById(any(ProcessedEventId.class))).thenReturn(false);
        when(reservationRepository.findByOrderId("ord-9")).thenReturn(List.of(reservation));

        inventoryService.confirmReservation("evt-order-confirmed", "ord-9");
        // Second confirming event for the same order: the row is CONSUMED now, not RESERVED.
        inventoryService.confirmReservation("evt-payment-completed", "ord-9");

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONSUMED);
        verify(stockRepository, times(1)).confirmStockAtomic(101L, 1);
    }

    @Test
    @DisplayName("Multi-item order: reserves and releases each product by its own quantity")
    void shouldReserveAndReleaseEachProductSeparately() {
        OrderPlaced event = new OrderPlaced(
                "evt-multi", "ord-multi", "cust-1", new BigDecimal("60.00"),
                List.of(new OrderItemPayload(101L, 2, new BigDecimal("10.00")),
                        new OrderItemPayload(202L, 4, new BigDecimal("10.00"))),
                Instant.now());
        when(processedEventRepository.existsById(any(ProcessedEventId.class))).thenReturn(false);
        when(stockRepository.reserveStockAtomic(101L, 2)).thenReturn(1);
        when(stockRepository.reserveStockAtomic(202L, 4)).thenReturn(1);

        inventoryService.processOrderPlaced(event);

        ArgumentCaptor<Reservation> captor = ArgumentCaptor.forClass(Reservation.class);
        verify(reservationRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(Reservation::getProductId, Reservation::getQuantity)
                .containsExactly(tuple(101L, 2), tuple(202L, 4));
    }
}
