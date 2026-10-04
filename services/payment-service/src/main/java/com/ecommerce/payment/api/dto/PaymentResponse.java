package com.ecommerce.payment.api.dto;

import com.ecommerce.payment.application.PaymentView;
import com.ecommerce.payment.domain.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(UUID paymentId, String orderId, BigDecimal amount, PaymentStatus status,
                              String failureReason, Instant createdAt) {

    public static PaymentResponse from(PaymentView view) {
        return new PaymentResponse(view.paymentId(), view.orderId(), view.amount(), view.status(),
                view.failureReason(), view.createdAt());
    }
}
