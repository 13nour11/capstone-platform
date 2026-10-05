package com.ecommerce.inventory.domain.event;

import java.time.Instant;

public record OrderConfirmed(
    String eventId,
    String orderId,
    String customerId,
    Instant occurredAt
) {}
