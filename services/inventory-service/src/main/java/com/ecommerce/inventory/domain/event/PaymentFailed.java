package com.ecommerce.inventory.domain.event;

import java.time.Instant;

public record PaymentFailed(
    String eventId,
    String orderId,
    String reason,
    Instant occurredAt
) {}
