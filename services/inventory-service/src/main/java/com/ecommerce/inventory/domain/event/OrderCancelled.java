package com.ecommerce.inventory.domain.event;

import java.time.Instant;

public record OrderCancelled(
    String eventId,
    String orderId,
    String reason,
    Instant occurredAt
) {}
