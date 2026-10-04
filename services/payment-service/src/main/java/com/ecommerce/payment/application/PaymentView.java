package com.ecommerce.payment.application;

import com.ecommerce.payment.domain.Payment;
import com.ecommerce.payment.domain.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Read model of a payment returned by the use cases and stored as the idempotent response. */
public record PaymentView(UUID paymentId, String orderId, BigDecimal amount, PaymentStatus status,
                          String failureReason, Instant createdAt) {

    static PaymentView of(Payment payment) {
        return new PaymentView(payment.getId(), payment.getOrderId(), payment.getAmount(), payment.getStatus(),
                payment.getFailureReason(), payment.getCreatedAt());
    }
}
