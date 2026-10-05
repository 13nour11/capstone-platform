package com.ecommerce.order.domain.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderPlaced(
    String eventId,
    String orderId,
    String customerId,
    BigDecimal totalAmount,
    List<OrderItemPayload> items,
    Instant occurredAt
) {}
