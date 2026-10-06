package com.ecommerce.order.domain.event;

import java.time.Instant;

/** customerId was added after the first freeze (fields are only ever added, ADD §3.2): notification needs it. */
public record OrderCancelled(
    String eventId,
    String orderId,
    String customerId,
    String reason,
    Instant occurredAt
) {}
