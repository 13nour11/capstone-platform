package com.ecommerce.inventory.application;

import com.ecommerce.inventory.api.dto.CheckStockResponse;
import com.ecommerce.inventory.api.dto.StockResponse;
import com.ecommerce.inventory.domain.CancelledOrder;
import com.ecommerce.inventory.domain.OutboxEvent;
import com.ecommerce.inventory.domain.OutboxStatus;
import com.ecommerce.inventory.domain.ProcessedEvent;
import com.ecommerce.inventory.domain.ProcessedEventId;
import com.ecommerce.inventory.domain.Reservation;
import com.ecommerce.inventory.domain.ReservationStatus;
import com.ecommerce.inventory.domain.Stock;
import com.ecommerce.inventory.domain.event.InventoryReleased;
import com.ecommerce.inventory.domain.event.InventoryReservationFailed;
import com.ecommerce.inventory.domain.event.InventoryReserved;
import com.ecommerce.inventory.domain.event.LowStock;
import com.ecommerce.inventory.domain.event.OrderItemPayload;
import com.ecommerce.inventory.domain.event.OrderPlaced;
import com.ecommerce.inventory.domain.exception.ProductNotFoundException;
import com.ecommerce.inventory.infrastructure.outbox.OutboxTraceContext;
import com.ecommerce.inventory.infrastructure.persistence.CancelledOrderRepository;
import com.ecommerce.inventory.infrastructure.persistence.OutboxEventRepository;
import com.ecommerce.inventory.infrastructure.persistence.ProcessedEventRepository;
import com.ecommerce.inventory.infrastructure.persistence.ReservationRepository;
import com.ecommerce.inventory.infrastructure.persistence.StockRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class InventoryServiceImpl implements InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryServiceImpl.class);
    private static final String CONSUMER_NAME = "inventory-service";

    private final StockRepository stockRepository;
    private final ReservationRepository reservationRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final CancelledOrderRepository cancelledOrderRepository;
    private final OutboxTraceContext traceContext;
    private final ObjectMapper objectMapper;
    private final int lowStockThreshold;

    public InventoryServiceImpl(StockRepository stockRepository,
                                ReservationRepository reservationRepository,
                                OutboxEventRepository outboxEventRepository,
                                ProcessedEventRepository processedEventRepository,
                                CancelledOrderRepository cancelledOrderRepository,
                                OutboxTraceContext traceContext,
                                ObjectMapper objectMapper,
                                @Value("${inventory.low-stock.threshold:5}") int lowStockThreshold) {
        this.stockRepository = stockRepository;
        this.reservationRepository = reservationRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.processedEventRepository = processedEventRepository;
        this.cancelledOrderRepository = cancelledOrderRepository;
        this.traceContext = traceContext;
        this.objectMapper = objectMapper;
        this.lowStockThreshold = lowStockThreshold;
    }

    @Override
    @Transactional(readOnly = true)
    public CheckStockResponse checkStock(Long productId, int quantity) {
        log.info("Checking stock for productId: {}, requested quantity: {}", productId, quantity);
        return stockRepository.findById(productId)
                .map(stock -> new CheckStockResponse(productId, quantity, stock.hasAvailable(quantity)))
                .orElse(new CheckStockResponse(productId, quantity, false));
    }

    @Override
    @Transactional(readOnly = true)
    public StockResponse getStock(Long productId) {
        log.info("Getting stock details for productId: {}", productId);
        Stock stock = stockRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));
        return new StockResponse(stock.getProductId(), stock.getAvailable(), stock.getReserved());
    }

    @Override
    @Transactional(readOnly = true)
    public List<StockResponse> getLowStock() {
        return stockRepository.findByAvailableLessThanOrderByProductIdAsc(lowStockThreshold).stream()
                .map(stock -> new StockResponse(stock.getProductId(), stock.getAvailable(), stock.getReserved()))
                .toList();
    }

    @Override
    @Transactional
    public StockResponse adjustStock(Long productId, int newAvailable) {
        log.info("Adjusting stock for productId: {} to new available quantity: {}", productId, newAvailable);
        Stock stock = stockRepository.findById(productId)
                .orElseGet(() -> Stock.builder()
                        .productId(productId)
                        .available(0)
                        .reserved(0)
                        .build());
        stock.adjustAvailable(newAvailable);
        alertIfLow(stock);
        Stock saved = stockRepository.save(stock);
        return new StockResponse(saved.getProductId(), saved.getAvailable(), saved.getReserved());
    }

    @Override
    @Transactional
    public void processOrderPlaced(OrderPlaced event) {
        if (isAlreadyProcessed(event.eventId())) {
            log.info("Event {} already processed by {}, skipping", event.eventId(), CONSUMER_NAME);
            return;
        }
        List<OrderItemPayload> items = event.items();
        if (items == null || items.isEmpty()) {
            log.warn("OrderPlaced event {} has no items", event.orderId());
            markProcessed(event.eventId());
            return;
        }
        // NFR-05: the order was cancelled before its OrderPlaced arrived; never reserve for it
        if (cancelledOrderRepository.existsById(event.orderId())) {
            log.info("Order {} is already cancelled, not reserving stock", event.orderId());
            markProcessed(event.eventId());
            return;
        }

        List<OrderItemPayload> reservedItems = new ArrayList<>();
        boolean allReserved = true;
        for (OrderItemPayload item : items) {
            int updated = stockRepository.reserveStockAtomic(item.productId(), item.quantity());
            if (updated > 0) {
                reservedItems.add(item);
            } else {
                allReserved = false;
                log.warn("Insufficient stock to reserve productId: {} quantity: {} for order: {}",
                        item.productId(), item.quantity(), event.orderId());
                break;
            }
        }

        if (allReserved) {
            // FR-07: one reservation per order line, so release and confirm touch exactly the reserved products
            Instant now = Instant.now();
            for (OrderItemPayload item : items) {
                reservationRepository.save(new Reservation(event.orderId(), item.productId(), item.quantity(),
                        ReservationStatus.RESERVED, now));
                stockRepository.findById(item.productId()).ifPresent(this::alertIfLow);
            }
            String eventId = UUID.randomUUID().toString();
            saveOutboxEvent(eventId, "Inventory", event.orderId(), "InventoryReserved", new InventoryReserved(
                    eventId, event.orderId(), event.customerId(), event.totalAmount(), now));
            log.info("Stock reserved and InventoryReserved outbox event created for order: {}", event.orderId());
        } else {
            // Compensate partial reservations in this batch
            for (OrderItemPayload item : reservedItems) {
                stockRepository.releaseStockAtomic(item.productId(), item.quantity());
            }
            String eventId = UUID.randomUUID().toString();
            saveOutboxEvent(eventId, "Inventory", event.orderId(), "InventoryReservationFailed",
                    new InventoryReservationFailed(eventId, event.orderId(), "INSUFFICIENT_STOCK", Instant.now()));
            log.info("Reservation failed and InventoryReservationFailed outbox event created for order: {}",
                    event.orderId());
        }
        markProcessed(event.eventId());
    }

    @Override
    @Transactional
    public void releaseReservation(String eventId, String orderId, String reason) {
        if (isAlreadyProcessed(eventId)) {
            log.info("Event {} already processed by {}, skipping release for order: {}", eventId, CONSUMER_NAME, orderId);
            return;
        }
        if (!cancelledOrderRepository.existsById(orderId)) {
            cancelledOrderRepository.save(new CancelledOrder(orderId, Instant.now()));
        }
        int released = release(reservationRepository.findByOrderId(orderId));
        if (released > 0) {
            String releasedEventId = UUID.randomUUID().toString();
            saveOutboxEvent(releasedEventId, "Inventory", orderId, "InventoryReleased",
                    new InventoryReleased(releasedEventId, orderId, reason, Instant.now()));
            log.info("Released {} reservation(s) for order: {} (reason: {})", released, orderId, reason);
        }
        markProcessed(eventId);
    }

    @Override
    @Transactional
    public void confirmReservation(String eventId, String orderId) {
        if (isAlreadyProcessed(eventId)) {
            log.info("Event {} already processed by {}, skipping confirm for order: {}", eventId, CONSUMER_NAME, orderId);
            return;
        }
        List<Reservation> reservations = reservationRepository.findByOrderId(orderId);
        for (Reservation reservation : reservations) {
            if (reservation.getStatus() == ReservationStatus.RESERVED) {
                stockRepository.confirmStockAtomic(reservation.getProductId(), reservation.getQuantity());
                reservation.setStatus(ReservationStatus.CONSUMED);
                reservationRepository.save(reservation);
                log.info("Consumed stock reservation for order: {} product: {}", orderId, reservation.getProductId());
            }
        }

        // Both OrderConfirmed and PaymentCompleted confirm the same order, so finding the rows
        // already CONSUMED is the normal second pass. Stock that was released, or never reserved
        // at all, means the order was confirmed against goods nobody is holding (ADD Decision 6-2).
        if (reservations.isEmpty() || reservations.stream().anyMatch(r -> r.getStatus() == ReservationStatus.RELEASED)) {
            log.error("ALERT oversell: order {} confirmed without held stock (reservations: {})",
                    orderId,
                    reservations.stream().map(r -> r.getProductId() + "=" + r.getStatus()).toList());
        }
        markProcessed(eventId);
    }

    /**
     * NFR-05 safety net: releases every reservation still RESERVED for an order this service knows is cancelled.
     * Reservations of orders that are merely slow (for example payment-service is down) are never touched.
     */
    @Override
    @Transactional
    public int releaseReservationsOfCancelledOrders() {
        List<Reservation> orphans = reservationRepository.findByStatusForCancelledOrders(ReservationStatus.RESERVED);
        int count = release(orphans);
        if (count > 0) {
            log.info("NFR-05 Sweeper: released {} reservation(s) of cancelled orders", count);
        }
        return count;
    }

    private int release(List<Reservation> reservations) {
        int count = 0;
        for (Reservation reservation : reservations) {
            if (reservation.getStatus() == ReservationStatus.RESERVED) {
                stockRepository.releaseStockAtomic(reservation.getProductId(), reservation.getQuantity());
                reservation.setStatus(ReservationStatus.RELEASED);
                reservationRepository.save(reservation);
                stockRepository.findById(reservation.getProductId()).ifPresent(this::alertIfLow);
                count++;
            }
        }
        return count;
    }

    /**
     * B4: queues one LowStock alert when this product just dropped below the threshold. The flag change is written
     * with the caller's transaction (managed entity, or the caller's save).
     */
    private void alertIfLow(Stock stock) {
        if (stock.evaluateLowStock(lowStockThreshold)) {
            String eventId = UUID.randomUUID().toString();
            saveOutboxEvent(eventId, "Stock", String.valueOf(stock.getProductId()), "LowStock",
                    new LowStock(eventId, stock.getProductId(), stock.getAvailable(), lowStockThreshold, Instant.now()));
            log.info("LowStock alert queued for product {} (available {} < {})",
                    stock.getProductId(), stock.getAvailable(), lowStockThreshold);
        }
    }

    private void saveOutboxEvent(String eventId, String aggregateType, String aggregateId, String eventType,
                                 Object payload) {
        try {
            String payloadJson = objectMapper.writeValueAsString(payload);
            // The row id is the event id, so the eventId header and the payload agree
            outboxEventRepository.save(new OutboxEvent(eventId, aggregateType, aggregateId, eventType, payloadJson,
                    traceContext.currentTraceparent(), OutboxStatus.PENDING, Instant.now()));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize outbox event payload", e);
            throw new IllegalStateException("Outbox serialization failure", e);
        }
    }

    private boolean isAlreadyProcessed(String eventId) {
        return processedEventRepository.existsById(new ProcessedEventId(eventId, CONSUMER_NAME));
    }

    private void markProcessed(String eventId) {
        processedEventRepository.save(new ProcessedEvent(eventId, CONSUMER_NAME, Instant.now()));
    }
}
