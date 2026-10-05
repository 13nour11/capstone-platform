package com.ecommerce.inventory.application;

import com.ecommerce.inventory.api.dto.CheckStockResponse;
import com.ecommerce.inventory.api.dto.StockResponse;
import com.ecommerce.inventory.domain.OutboxEvent;
import com.ecommerce.inventory.domain.OutboxStatus;
import com.ecommerce.inventory.domain.ProcessedEvent;
import com.ecommerce.inventory.domain.ProcessedEventId;
import com.ecommerce.inventory.domain.Reservation;
import com.ecommerce.inventory.domain.ReservationStatus;
import com.ecommerce.inventory.domain.Stock;
import com.ecommerce.inventory.domain.event.InventoryReservationFailed;
import com.ecommerce.inventory.domain.event.InventoryReserved;
import com.ecommerce.inventory.domain.event.OrderItemPayload;
import com.ecommerce.inventory.domain.event.OrderPlaced;
import com.ecommerce.inventory.domain.exception.ProductNotFoundException;
import com.ecommerce.inventory.infrastructure.persistence.OutboxEventRepository;
import com.ecommerce.inventory.infrastructure.persistence.ProcessedEventRepository;
import com.ecommerce.inventory.infrastructure.persistence.ReservationRepository;
import com.ecommerce.inventory.infrastructure.persistence.StockRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private final ObjectMapper objectMapper;

    public InventoryServiceImpl(StockRepository stockRepository,
                                ReservationRepository reservationRepository,
                                OutboxEventRepository outboxEventRepository,
                                ProcessedEventRepository processedEventRepository,
                                ObjectMapper objectMapper) {
        this.stockRepository = stockRepository;
        this.reservationRepository = reservationRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.processedEventRepository = processedEventRepository;
        this.objectMapper = objectMapper;
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
    @Transactional
    public StockResponse adjustStock(Long productId, int newAvailable) {
        log.info("Adjusting stock for productId: {} to new available quantity: {}", productId, newAvailable);
        Stock stock = stockRepository.findById(productId)
                .orElseGet(() -> Stock.builder()
                        .productId(productId)
                        .available(0)
                        .reserved(0)
                        .version(0L)
                        .build());
        stock.adjustAvailable(newAvailable);
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
            // Create reservation record
            OrderItemPayload firstItem = items.get(0);
            int totalQuantity = items.stream().mapToInt(OrderItemPayload::quantity).sum();
            Reservation reservation = new Reservation(
                    event.orderId(),
                    firstItem.productId(),
                    totalQuantity,
                    ReservationStatus.RESERVED,
                    Instant.now()
            );
            reservationRepository.save(reservation);

            // Queue InventoryReserved event to outbox
            InventoryReserved outboxPayload = new InventoryReserved(
                    UUID.randomUUID().toString(),
                    event.orderId(),
                    event.customerId(),
                    event.totalAmount(),
                    Instant.now()
            );
            saveOutboxEvent("Inventory", event.orderId(), "InventoryReserved", outboxPayload);
            log.info("Stock reserved and InventoryReserved outbox event created for order: {}", event.orderId());
        } else {
            // Compensate partial reservations in this batch
            for (OrderItemPayload item : reservedItems) {
                stockRepository.releaseStockAtomic(item.productId(), item.quantity());
            }

            // Queue InventoryReservationFailed event to outbox
            InventoryReservationFailed outboxPayload = new InventoryReservationFailed(
                    UUID.randomUUID().toString(),
                    event.orderId(),
                    "INSUFFICIENT_STOCK",
                    Instant.now()
            );
            saveOutboxEvent("Inventory", event.orderId(), "InventoryReservationFailed", outboxPayload);
            log.info("Reservation failed and InventoryReservationFailed outbox event created for order: {}", event.orderId());
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

        reservationRepository.findById(orderId).ifPresent(reservation -> {
            if (reservation.getStatus() == ReservationStatus.RESERVED) {
                stockRepository.releaseStockAtomic(reservation.getProductId(), reservation.getQuantity());
                reservation.setStatus(ReservationStatus.RELEASED);
                reservationRepository.save(reservation);
                log.info("Released stock reservation for order: {} (reason: {})", orderId, reason);
            }
        });

        markProcessed(eventId);
    }

    @Override
    @Transactional
    public void confirmReservation(String eventId, String orderId) {
        if (isAlreadyProcessed(eventId)) {
            log.info("Event {} already processed by {}, skipping confirm for order: {}", eventId, CONSUMER_NAME, orderId);
            return;
        }

        reservationRepository.findById(orderId).ifPresent(reservation -> {
            if (reservation.getStatus() == ReservationStatus.RESERVED) {
                stockRepository.confirmStockAtomic(reservation.getProductId(), reservation.getQuantity());
                reservation.setStatus(ReservationStatus.CONSUMED);
                reservationRepository.save(reservation);
                log.info("Consumed stock reservation for order: {}", orderId);
            }
        });

        markProcessed(eventId);
    }

    @Override
    @Transactional
    public int releaseExpiredReservations(int ttlSeconds) {
        Instant cutoff = Instant.now().minusSeconds(ttlSeconds);
        List<Reservation> expired = reservationRepository.findByStatusAndCreatedAtBefore(ReservationStatus.RESERVED, cutoff);

        int count = 0;
        for (Reservation reservation : expired) {
            stockRepository.releaseStockAtomic(reservation.getProductId(), reservation.getQuantity());
            reservation.setStatus(ReservationStatus.RELEASED);
            reservationRepository.save(reservation);
            count++;
            log.info("NFR-05 Sweeper: released expired reservation for order: {} (age > {}s)",
                    reservation.getOrderId(), ttlSeconds);
        }
        return count;
    }

    private void saveOutboxEvent(String aggregateType, String aggregateId, String eventType, Object payload) {
        try {
            String payloadJson = objectMapper.writeValueAsString(payload);
            String traceparent = UUID.randomUUID().toString();

            OutboxEvent outboxEvent = new OutboxEvent(
                    UUID.randomUUID().toString(),
                    aggregateType,
                    aggregateId,
                    eventType,
                    payloadJson,
                    traceparent,
                    OutboxStatus.PENDING,
                    Instant.now()
            );
            outboxEventRepository.save(outboxEvent);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize outbox event payload", e);
            throw new RuntimeException("Outbox serialization failure", e);
        }
    }

    private boolean isAlreadyProcessed(String eventId) {
        return processedEventRepository.existsById(new ProcessedEventId(eventId, CONSUMER_NAME));
    }

    private void markProcessed(String eventId) {
        processedEventRepository.save(new ProcessedEvent(eventId, CONSUMER_NAME, Instant.now()));
    }
}
