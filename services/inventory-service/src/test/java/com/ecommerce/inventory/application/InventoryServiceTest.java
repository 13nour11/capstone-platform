package com.ecommerce.inventory.application;

import com.ecommerce.inventory.api.dto.CheckStockResponse;
import com.ecommerce.inventory.api.dto.StockResponse;
import com.ecommerce.inventory.domain.CancelledOrder;
import com.ecommerce.inventory.domain.OutboxEvent;
import com.ecommerce.inventory.domain.ProcessedEventId;
import com.ecommerce.inventory.domain.Reservation;
import com.ecommerce.inventory.domain.ReservationStatus;
import com.ecommerce.inventory.domain.Stock;
import com.ecommerce.inventory.domain.event.OrderItemPayload;
import com.ecommerce.inventory.domain.event.OrderPlaced;
import com.ecommerce.inventory.domain.exception.ProductNotFoundException;
import com.ecommerce.inventory.infrastructure.outbox.OutboxTraceContext;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private CancelledOrderRepository cancelledOrderRepository;

    @Mock
    private OutboxTraceContext traceContext;

    private ObjectMapper objectMapper;

    private InventoryServiceImpl inventoryService;

    private Stock testStock;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        inventoryService = new InventoryServiceImpl(
                stockRepository,
                reservationRepository,
                outboxEventRepository,
                processedEventRepository,
                cancelledOrderRepository,
                traceContext,
                objectMapper,
                5
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
        verify(cancelledOrderRepository).save(any(CancelledOrder.class));
        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().getEventType()).isEqualTo("InventoryReleased");
    }

    @Test
    @DisplayName("NFR-05 Sweeper: Should release reservations of cancelled orders")
    void shouldReleaseReservationsOfCancelledOrders() {
        Reservation reservation = new Reservation("ord-cancelled", 101L, 2, ReservationStatus.RESERVED, Instant.now());
        when(reservationRepository.findByStatusForCancelledOrders(ReservationStatus.RESERVED))
                .thenReturn(List.of(reservation));
        when(stockRepository.releaseStockAtomic(101L, 2)).thenReturn(1);
        int count = inventoryService.releaseReservationsOfCancelledOrders();
        assertThat(count).isEqualTo(1);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RELEASED);
        verify(stockRepository).releaseStockAtomic(101L, 2);
    }

    @Test
    @DisplayName("FR-07: Should reserve every product of a multi-item order, one reservation per line")
    void shouldReserveEachLine_whenOrderHasSeveralProducts() {
        OrderPlaced event = new OrderPlaced("evt-5", "ord-5", "cust-1", new BigDecimal("80.00"),
                List.of(new OrderItemPayload(101L, 2, new BigDecimal("20.00")),
                        new OrderItemPayload(102L, 1, new BigDecimal("40.00"))),
                Instant.now());
        when(processedEventRepository.existsById(any(ProcessedEventId.class))).thenReturn(false);
        when(stockRepository.reserveStockAtomic(101L, 2)).thenReturn(1);
        when(stockRepository.reserveStockAtomic(102L, 1)).thenReturn(1);
        inventoryService.processOrderPlaced(event);
        ArgumentCaptor<Reservation> reservations = ArgumentCaptor.forClass(Reservation.class);
        verify(reservationRepository, times(2)).save(reservations.capture());
        assertThat(reservations.getAllValues()).extracting(Reservation::getProductId).containsExactly(101L, 102L);
        assertThat(reservations.getAllValues()).extracting(Reservation::getQuantity).containsExactly(2, 1);
    }

    @Test
    @DisplayName("NFR-05: Should not reserve stock for an order that is already cancelled")
    void shouldNotReserve_whenOrderAlreadyCancelled() {
        OrderPlaced event = new OrderPlaced("evt-6", "ord-6", "cust-1", new BigDecimal("20.00"),
                List.of(new OrderItemPayload(101L, 1, new BigDecimal("20.00"))), Instant.now());
        when(processedEventRepository.existsById(any(ProcessedEventId.class))).thenReturn(false);
        when(cancelledOrderRepository.existsById("ord-6")).thenReturn(true);
        inventoryService.processOrderPlaced(event);
        verify(stockRepository, never()).reserveStockAtomic(any(), anyInt());
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("Idempotency: a redelivered OrderPlaced reserves nothing")
    void shouldSkip_whenEventAlreadyProcessed() {
        OrderPlaced event = new OrderPlaced("evt-7", "ord-7", "cust-1", new BigDecimal("20.00"),
                List.of(new OrderItemPayload(101L, 1, new BigDecimal("20.00"))), Instant.now());
        when(processedEventRepository.existsById(any(ProcessedEventId.class))).thenReturn(true);
        inventoryService.processOrderPlaced(event);
        verify(stockRepository, never()).reserveStockAtomic(any(), anyInt());
    }

    @Test
    @DisplayName("B4: Should queue exactly one LowStock alert when stock drops below the threshold")
    void shouldQueueOneLowStockAlert_whenStockDropsBelowThreshold() {
        when(stockRepository.findById(101L)).thenReturn(Optional.of(testStock));
        when(stockRepository.save(any(Stock.class))).thenAnswer(invocation -> invocation.getArgument(0));
        inventoryService.adjustStock(101L, 3);
        inventoryService.adjustStock(101L, 2);
        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository, times(1)).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().getEventType()).isEqualTo("LowStock");
        assertThat(outboxCaptor.getValue().getAggregateId()).isEqualTo("101");
    }

    @Test
    @DisplayName("B4: Should alert again after stock went back above the threshold")
    void shouldAlertAgain_afterRestock() {
        when(stockRepository.findById(101L)).thenReturn(Optional.of(testStock));
        when(stockRepository.save(any(Stock.class))).thenAnswer(invocation -> invocation.getArgument(0));
        inventoryService.adjustStock(101L, 1);
        inventoryService.adjustStock(101L, 40);
        inventoryService.adjustStock(101L, 1);
        verify(outboxEventRepository, times(2)).save(any(OutboxEvent.class));
    }
}
