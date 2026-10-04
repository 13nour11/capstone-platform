package com.ecommerce.order.domain.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderPlaced(
    String eventId,
    String orderId,
    String customerId,
    BigDecimal totalAmount,
    List<OrderItemPayload> items,
    Instant occurredAt
) {}
