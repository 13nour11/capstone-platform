package com.ecommerce.order.api.dto;

import com.ecommerce.order.domain.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderResponse(
    String orderId,
    String customerId,
    BigDecimal totalAmount,
    OrderStatus status,
    List<OrderItemResponse> items,
    Instant createdAt
) {}
