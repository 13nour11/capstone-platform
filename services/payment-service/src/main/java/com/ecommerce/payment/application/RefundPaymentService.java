package com.ecommerce.payment.application;

import com.ecommerce.payment.domain.Payment;
import com.ecommerce.payment.domain.exception.PaymentNotFoundException;
import com.ecommerce.payment.infrastructure.persistence.PaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/** {@code POST /api/v1/payments/{id}/refund}: refunds a completed payment once; a repeated call changes nothing. */
@Service
public class RefundPaymentService {

    private final PaymentRepository payments;
    private final Clock clock;

    public RefundPaymentService(PaymentRepository payments, Clock clock) {
        this.payments = payments;
        this.clock = clock;
    }

    @Transactional
    public PaymentView refund(UUID paymentId) {
        Payment payment = payments.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
        payment.refund(clock.instant());
        return PaymentView.of(payment);
    }
}
