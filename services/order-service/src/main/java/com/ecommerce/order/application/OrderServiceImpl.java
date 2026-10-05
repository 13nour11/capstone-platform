package com.ecommerce.order.application;

import com.ecommerce.order.api.dto.CreateOrderRequest;
import com.ecommerce.order.api.dto.OrderItemRequest;
import com.ecommerce.order.api.dto.OrderItemResponse;
import com.ecommerce.order.api.dto.OrderResponse;
import com.ecommerce.order.domain.Order;
import com.ecommerce.order.domain.OrderItem;
import com.ecommerce.order.domain.OrderStatus;
import com.ecommerce.order.domain.OutboxEvent;
import com.ecommerce.order.domain.OutboxStatus;
import com.ecommerce.order.domain.ProcessedEvent;
import com.ecommerce.order.domain.ProcessedEventId;
import com.ecommerce.order.domain.event.OrderCancelled;
import com.ecommerce.order.domain.event.OrderConfirmed;
import com.ecommerce.order.domain.event.OrderItemPayload;
import com.ecommerce.order.domain.event.OrderPlaced;
import com.ecommerce.order.domain.exception.OrderNotFoundException;
import com.ecommerce.order.infrastructure.client.InventoryServiceClient;
import com.ecommerce.order.infrastructure.persistence.OrderRepository;
import com.ecommerce.order.infrastructure.persistence.OutboxEventRepository;
import com.ecommerce.order.infrastructure.persistence.ProcessedEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class OrderServiceImpl implements OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderServiceImpl.class);

    private final OrderRepository orderRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final InventoryServiceClient inventoryServiceClient;
    private final ObjectMapper objectMapper;

    private final OrderPersistenceService orderPersistenceService;

    public OrderServiceImpl(OrderRepository orderRepository,
                            OutboxEventRepository outboxEventRepository,
                            ProcessedEventRepository processedEventRepository,
                            InventoryServiceClient inventoryServiceClient,
                            ObjectMapper objectMapper,
                            OrderPersistenceService orderPersistenceService) {
        this.orderRepository = orderRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.processedEventRepository = processedEventRepository;
        this.inventoryServiceClient = inventoryServiceClient;
        this.objectMapper = objectMapper;
        this.orderPersistenceService = orderPersistenceService;
    }

    @Override
    public OrderResponse createOrder(String customerId, CreateOrderRequest request) {
        log.info("Processing order placement for customerId: {} with {} items", customerId, request.items().size());

        // 1. Synchronous stock pre-check (OpenFeign + Resilience4j) BEFORE opening DB transaction
        for (OrderItemRequest item : request.items()) {
            inventoryServiceClient.verifyStockAvailability(item.productId(), item.quantity());
        }

        // 2. Persist order and outbox event in ONE database transaction via proxy-managed delegate
        return orderPersistenceService.persistOrderAndOutbox(customerId, request);
    }

    @Transactional
    public void confirmOrder(String eventId, String orderId) {
        if (isAlreadyProcessed(eventId, "order-service")) {
            return;
        }

        orderRepository.findById(orderId).ifPresent(order -> {
            order.confirm();
            orderRepository.save(order);

            OrderConfirmed event = new OrderConfirmed(
                    UUID.randomUUID().toString(),
                    order.getId(),
                    order.getCustomerId(),
                    Instant.now()
            );
            orderPersistenceService.saveOutboxEvent("Order", order.getId(), "OrderConfirmed", event);
            log.info("Order {} confirmed and OrderConfirmed event queued to outbox", orderId);
        });

        markProcessed(eventId, "order-service");
    }

    @Transactional
    public void cancelOrder(String eventId, String orderId, String reason) {
        if (isAlreadyProcessed(eventId, "order-service")) {
            return;
        }

        orderRepository.findById(orderId).ifPresent(order -> {
            order.cancel();
            orderRepository.save(order);

            OrderCancelled event = new OrderCancelled(
                    UUID.randomUUID().toString(),
                    order.getId(),
                    order.getCustomerId(),
                    reason,
                    Instant.now()
            );
            orderPersistenceService.saveOutboxEvent("Order", order.getId(), "OrderCancelled", event);
            log.info("Order {} cancelled (reason: {}) and OrderCancelled event queued to outbox", orderId, reason);
        });

        markProcessed(eventId, "order-service");
    }


    @Override
    @Transactional(readOnly = true)
    public OrderResponse getOrderById(String orderId) {
        log.info("Fetching order by id: {}", orderId);
        return orderRepository.findById(orderId)
                .map(this::mapToResponse)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderResponse> getOrdersByCustomerId(String customerId) {
        log.info("Fetching orders for customer: {}", customerId);
        return orderRepository.findByCustomerIdOrderByCreatedAtDesc(customerId)
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    private boolean isAlreadyProcessed(String eventId, String consumer) {
        return processedEventRepository.existsById(new ProcessedEventId(eventId, consumer));
    }

    private void markProcessed(String eventId, String consumer) {
        processedEventRepository.save(new ProcessedEvent(eventId, consumer, Instant.now()));
    }

    private OrderResponse mapToResponse(Order order) {
        List<OrderItemResponse> itemResponses = order.getItems().stream()
                .map(item -> new OrderItemResponse(
                        item.getId(),
                        item.getProductId(),
                        item.getQuantity(),
                        item.getUnitPrice(),
                        item.getSubtotal()))
                .toList();

        return new OrderResponse(
                order.getId(),
                order.getCustomerId(),
                order.getTotalAmount(),
                order.getStatus(),
                itemResponses,
                order.getCreatedAt()
        );
    }
}
