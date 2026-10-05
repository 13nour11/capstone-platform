package com.ecommerce.order.application;

import com.ecommerce.order.api.dto.CreateOrderRequest;
import com.ecommerce.order.domain.event.OrderItemPayload;
import com.ecommerce.order.api.dto.OrderItemRequest;
import com.ecommerce.order.api.dto.OrderItemResponse;
import com.ecommerce.order.api.dto.OrderResponse;

import com.ecommerce.order.domain.Order;
import com.ecommerce.order.domain.OrderItem;
import com.ecommerce.order.domain.OrderStatus;
import com.ecommerce.order.domain.OutboxEvent;
import com.ecommerce.order.domain.OutboxStatus;
import com.ecommerce.order.domain.event.OrderPlaced;
import com.ecommerce.order.infrastructure.persistence.OrderRepository;
import com.ecommerce.order.infrastructure.persistence.OutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class OrderPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(OrderPersistenceService.class);

    private final OrderRepository orderRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final Tracer tracer;

    @org.springframework.beans.factory.annotation.Autowired
    public OrderPersistenceService(OrderRepository orderRepository,
                                   OutboxEventRepository outboxEventRepository,
                                   ObjectMapper objectMapper,
                                   ObjectProvider<Tracer> tracerProvider) {

        this.orderRepository = orderRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
        this.tracer = tracerProvider != null ? tracerProvider.getIfAvailable() : null;
    }

    public OrderPersistenceService(OrderRepository orderRepository,
                                   OutboxEventRepository outboxEventRepository,
                                   ObjectMapper objectMapper) {
        this(orderRepository, outboxEventRepository, objectMapper, null);
    }


    @Transactional
    public OrderResponse persistOrderAndOutbox(String customerId, CreateOrderRequest request) {
        String orderId = UUID.randomUUID().toString();

        BigDecimal totalAmount = BigDecimal.ZERO;
        Order order = Order.builder()
                .id(orderId)
                .customerId(customerId)
                .status(OrderStatus.PENDING)
                .createdAt(Instant.now())
                .build();

        for (OrderItemRequest itemReq : request.items()) {
            OrderItem item = new OrderItem(itemReq.productId(), itemReq.quantity(), itemReq.unitPrice());
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

        log.info("Order placed and OutboxEvent persisted atomically: orderId={}, status=PENDING", saved.getId());
        return mapToResponse(saved);
    }

    public void saveOutboxEvent(String aggregateType, String aggregateId, String eventType, Object payload) {
        try {
            String payloadJson = objectMapper.writeValueAsString(payload);
            String traceparent = resolveTraceparent();

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

    private String resolveTraceparent() {
        try {
            if (tracer != null) {
                Span currentSpan = tracer.currentSpan();
                if (currentSpan != null && currentSpan.context() != null) {
                    String traceId = currentSpan.context().traceId();
                    String spanId = currentSpan.context().spanId();
                    if (traceId != null && spanId != null) {
                        return String.format("00-%32s-%16s-01", traceId, spanId).replace(' ', '0');
                    }
                }
            }
        } catch (Exception ignored) {
        }
        String traceId = UUID.randomUUID().toString().replace("-", "");
        String spanId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        return "00-" + traceId + "-" + spanId + "-01";
    }

    private OrderResponse mapToResponse(Order order) {
        List<OrderItemResponse> itemResponses = order.getItems().stream()
                .map(item -> new OrderItemResponse(
                        item.getId(),
                        item.getProductId(),
                        item.getQuantity(),
                        item.getUnitPrice(),
                        item.getSubtotal()
                ))
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
