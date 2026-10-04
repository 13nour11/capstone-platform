package com.ecommerce.order.application;

import com.ecommerce.order.api.dto.CreateOrderRequest;
import com.ecommerce.order.api.dto.OrderItemRequest;
import com.ecommerce.order.api.dto.OrderItemResponse;
import com.ecommerce.order.api.dto.OrderResponse;
import com.ecommerce.order.domain.Order;
import com.ecommerce.order.domain.OrderItem;
import com.ecommerce.order.domain.OrderStatus;
import com.ecommerce.order.domain.exception.OrderNotFoundException;
import com.ecommerce.order.infrastructure.client.InventoryServiceClient;
import com.ecommerce.order.infrastructure.persistence.OrderRepository;
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
    private final InventoryServiceClient inventoryServiceClient;

    public OrderServiceImpl(OrderRepository orderRepository, InventoryServiceClient inventoryServiceClient) {
        this.orderRepository = orderRepository;
        this.inventoryServiceClient = inventoryServiceClient;
    }

    @Override
    public OrderResponse createOrder(String customerId, CreateOrderRequest request) {
        log.info("Processing order placement for customerId: {} with {} items", customerId, request.items().size());

        // 1. Synchronous stock pre-check (OpenFeign + Resilience4j) BEFORE opening DB transaction
        for (OrderItemRequest item : request.items()) {
            inventoryServiceClient.verifyStockAvailability(item.productId(), item.quantity());
        }

        // 2. Persist order in isolated DB transaction
        return persistOrder(customerId, request);
    }

    @Transactional
    public OrderResponse persistOrder(String customerId, CreateOrderRequest request) {
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
        log.info("Order successfully created with orderId: {}, status: PENDING, total: {}", saved.getId(), saved.getTotalAmount());
        return mapToResponse(saved);
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
