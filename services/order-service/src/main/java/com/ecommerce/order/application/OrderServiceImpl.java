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
import com.ecommerce.order.infrastructure.client.ProductServiceClient;
import com.ecommerce.order.infrastructure.persistence.OrderRepository;
import com.ecommerce.order.infrastructure.persistence.OutboxEventRepository;
import com.ecommerce.order.infrastructure.persistence.ProcessedEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class OrderServiceImpl implements OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderServiceImpl.class);

    private final OrderRepository orderRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final InventoryServiceClient inventoryServiceClient;
    private final ProductServiceClient productServiceClient;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final Tracer tracer;
    private final Propagator propagator;

    public OrderServiceImpl(OrderRepository orderRepository,
                            OutboxEventRepository outboxEventRepository,
                            ProcessedEventRepository processedEventRepository,
                            InventoryServiceClient inventoryServiceClient,
                            ProductServiceClient productServiceClient,
                            ObjectMapper objectMapper,
                            TransactionTemplate transactionTemplate,
                            Tracer tracer,
                            Propagator propagator) {
        this.orderRepository = orderRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.processedEventRepository = processedEventRepository;
        this.inventoryServiceClient = inventoryServiceClient;
        this.productServiceClient = productServiceClient;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
        this.tracer = tracer;
        this.propagator = propagator;
    }

    @Override
    public OrderResponse createOrder(String customerId, CreateOrderRequest request) {
        log.info("Processing order placement for customerId: {} with {} items", customerId, request.items().size());

        // 1. Stock pre-check and pricing (OpenFeign + Resilience4j) BEFORE opening the DB
        // transaction: a remote call inside it would hold a Hikari connection for its whole duration.
        Map<Long, BigDecimal> prices = new LinkedHashMap<>();
        for (OrderItemRequest item : request.items()) {
            inventoryServiceClient.verifyStockAvailability(item.productId(), item.quantity());
            prices.computeIfAbsent(item.productId(), productServiceClient::currentPrice);
        }

        // 2. Persist order and outbox event in ONE database transaction. Called through the
        // TransactionTemplate because a plain self-call would bypass the proxy and leave each
        // save in its own transaction, which is exactly what the outbox exists to prevent.
        return transactionTemplate.execute(status -> persistOrderAndOutbox(customerId, request, prices));
    }

    OrderResponse persistOrderAndOutbox(String customerId, CreateOrderRequest request,
                                        Map<Long, BigDecimal> prices) {
        String orderId = UUID.randomUUID().toString();

        BigDecimal totalAmount = BigDecimal.ZERO;
        Order order = Order.builder()
                .id(orderId)
                .customerId(customerId)
                .status(OrderStatus.PENDING)
                .createdAt(Instant.now())
                .build();

        for (OrderItemRequest itemReq : request.items()) {
            OrderItem item = new OrderItem(itemReq.productId(), itemReq.quantity(),
                    prices.get(itemReq.productId()));
            order.addItem(item);
            totalAmount = totalAmount.add(item.getSubtotal());
        }

        Order orderToSave = Order.builder()
                .id(order.getId())
                .customerId(order.getCustomerId())
                .totalAmount(totalAmount)
                .status(order.getStatus())
                .createdAt(order.getCreatedAt())
                .items(order.getItems())
                .build();

        Order saved = orderRepository.save(orderToSave);

        // Transactional Outbox: OrderPlaced event
        List<OrderItemPayload> itemPayloads = saved.getItems().stream()
                .map(item -> new OrderItemPayload(item.getProductId(), item.getQuantity(), item.getUnitPrice()))
                .toList();

        OrderPlaced event = new OrderPlaced(
                UUID.randomUUID().toString(),
                saved.getId(),
                saved.getCustomerId(),
                saved.getTotalAmount(),
                itemPayloads,
                saved.getCreatedAt()
        );

        saveOutboxEvent("Order", saved.getId(), "OrderPlaced", event);

        log.info("Order placed and OutboxEvent persisted: orderId={}, status=PENDING", saved.getId());
        return mapToResponse(saved);
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
            saveOutboxEvent("Order", order.getId(), "OrderConfirmed", event);
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
                    reason,
                    Instant.now()
            );
            saveOutboxEvent("Order", order.getId(), "OrderCancelled", event);
            log.info("Order {} cancelled (reason: {}) and OrderCancelled event queued to outbox", orderId, reason);
        });

        markProcessed(eventId, "order-service");
    }

    /**
     * Cancels orders whose saga never finished (NFR-01 bounded): payment may be down for minutes,
     * so the timeout is generous and the OrderCancelled event is what frees the reserved stock.
     */
    @Override
    @Transactional
    public int cancelOrdersPendingLongerThan(Duration timeout) {
        List<Order> stuck = orderRepository.findByStatusAndCreatedAtBefore(
                OrderStatus.PENDING, Instant.now().minus(timeout));

        for (Order order : stuck) {
            order.cancel();
            orderRepository.save(order);
            saveOutboxEvent("Order", order.getId(), "OrderCancelled", new OrderCancelled(
                    UUID.randomUUID().toString(), order.getId(), "SAGA_TIMEOUT", Instant.now()));
            log.warn("Order {} cancelled after {} in PENDING; OrderCancelled queued", order.getId(), timeout);
        }
        return stuck.size();
    }

    @Override
    @Transactional(readOnly = true)
    public OrderResponse getOrderById(String orderId) {
        log.info("Fetching order by id: {}", orderId);
        return orderRepository.findById(orderId)
                .map(this::mapToResponse)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    /**
     * Someone else's order is reported as not found, so the endpoint cannot be used to discover
     * which order ids exist.
     */
    @Override
    @Transactional(readOnly = true)
    public OrderResponse getOrderForCustomer(String orderId, String customerId, boolean admin) {
        return orderRepository.findById(orderId)
                .filter(order -> admin || order.getCustomerId().equals(customerId))
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

    private void saveOutboxEvent(String aggregateType, String aggregateId, String eventType, Object payload) {
        try {
            String payloadJson = objectMapper.writeValueAsString(payload);

            OutboxEvent outboxEvent = new OutboxEvent(
                    UUID.randomUUID().toString(),
                    aggregateType,
                    aggregateId,
                    eventType,
                    payloadJson,
                    currentTraceparent(),
                    OutboxStatus.PENDING,
                    Instant.now()
            );
            outboxEventRepository.save(outboxEvent);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize outbox event payload", e);
            throw new RuntimeException("Outbox serialization failure", e);
        }
    }

    /**
     * The W3C traceparent of the request that wrote the event. The publisher runs on a scheduler
     * thread, so without this the trace would end at the outbox (NFR-06).
     */
    private String currentTraceparent() {
        TraceContext context = tracer.currentTraceContext().context();
        if (context == null) {
            return null;
        }
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(context, carrier, Map::put);
        return carrier.get("traceparent");
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
