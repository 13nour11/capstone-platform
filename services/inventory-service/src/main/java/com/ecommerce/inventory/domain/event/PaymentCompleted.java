package com.ecommerce.inventory.domain.event;

import java.time.Instant;

public record PaymentCompleted(
    String eventId,
    String orderId,
    String paymentId,
    Instant occurredAt
) {}
