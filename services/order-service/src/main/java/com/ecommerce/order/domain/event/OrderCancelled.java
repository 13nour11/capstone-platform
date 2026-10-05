package com.ecommerce.order.domain.event;

import java.time.Instant;

public record OrderCancelled(
    String eventId,
    String orderId,
    String customerId,
    String reason,
    Instant occurredAt
) {}
