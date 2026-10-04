package com.ecommerce.payment.domain.exception;

import com.ecommerce.payment.domain.PaymentStatus;

import java.util.UUID;

public class RefundNotAllowedException extends RuntimeException {

    public RefundNotAllowedException(UUID paymentId, PaymentStatus status) {
        super("Payment " + paymentId + " cannot be refunded in status " + status);
    }
}
