package com.ecommerce.order.domain.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderCancelled(
    String eventId,
    String orderId,
    String customerId,
    String reason,
    Instant occurredAt
) {}

