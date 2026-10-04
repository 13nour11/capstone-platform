package com.ecommerce.payment.domain.event;

import java.math.BigDecimal;
import java.time.Instant;

/** Published to {@code payment-events} when the order was charged. */
public record PaymentCompleted(String eventId, String orderId, String paymentId, BigDecimal totalAmount,
                               Instant occurredAt) {
}
