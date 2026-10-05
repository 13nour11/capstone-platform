package com.ecommerce.payment.domain.event;

import java.time.Instant;

/** Published to {@code payment-events} when the charge was declined; inventory releases stock on it. */
public record PaymentFailed(String eventId, String orderId, String reason, Instant occurredAt) {
}
