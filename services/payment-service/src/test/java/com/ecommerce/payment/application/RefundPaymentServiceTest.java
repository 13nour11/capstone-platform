package com.ecommerce.payment.application;

import com.ecommerce.payment.domain.Payment;
import com.ecommerce.payment.domain.PaymentStatus;
import com.ecommerce.payment.domain.exception.PaymentNotFoundException;
import com.ecommerce.payment.domain.exception.RefundNotAllowedException;
import com.ecommerce.payment.infrastructure.persistence.PaymentRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RefundPaymentServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-04T10:00:00Z");

    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final RefundPaymentService service =
            new RefundPaymentService(payments, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void shouldReturnRefundedPayment_whenRefundingCompletedPayment() {
        Payment payment = Payment.completed("order-1", new BigDecimal("20.00"), NOW);
        when(payments.findById(payment.getId())).thenReturn(Optional.of(payment));

        PaymentView view = service.refund(payment.getId());

        assertThat(view.status()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    void shouldReturnSameResult_whenRefundIsRetried() {
        Payment payment = Payment.completed("order-1", new BigDecimal("20.00"), NOW);
        when(payments.findById(payment.getId())).thenReturn(Optional.of(payment));

        PaymentView first = service.refund(payment.getId());
        PaymentView second = service.refund(payment.getId());

        assertThat(second).isEqualTo(first);
    }

    @Test
    void shouldFail_whenPaymentDoesNotExist() {
        UUID unknown = UUID.randomUUID();
        when(payments.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.refund(unknown)).isInstanceOf(PaymentNotFoundException.class);
    }

    @Test
    void shouldFail_whenPaymentWasDeclined() {
        Payment payment = Payment.failed("order-1", new BigDecimal("20.00"), "declined", NOW);
        when(payments.findById(payment.getId())).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> service.refund(payment.getId())).isInstanceOf(RefundNotAllowedException.class);
    }
}
