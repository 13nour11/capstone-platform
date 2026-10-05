package com.ecommerce.inventory.domain.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentFailed(
    String eventId,
    String orderId,
    String reason,
    Instant occurredAt
) {}
