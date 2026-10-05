package com.ecommerce.order.domain.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderConfirmed(
    String eventId,
    String orderId,
    String customerId,
    Instant occurredAt
) {}
