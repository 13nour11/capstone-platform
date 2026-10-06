package com.ecommerce.payment.domain;

import com.ecommerce.payment.domain.exception.RefundNotAllowedException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentTest {

    private static final Instant NOW = Instant.parse("2026-10-04T10:00:00Z");

    @Test
    void shouldBeRefunded_whenCompletedPaymentIsRefunded() {
        Payment payment = Payment.completed("order-1", new BigDecimal("50.00"), NOW);

        boolean changed = payment.refund(NOW.plusSeconds(60));

        assertThat(changed).isTrue();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(payment.getUpdatedAt()).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    void shouldChangeNothing_whenRefundIsRepeated() {
        Payment payment = Payment.completed("order-1", new BigDecimal("50.00"), NOW);
        payment.refund(NOW);

        boolean changed = payment.refund(NOW.plusSeconds(60));

        assertThat(changed).isFalse();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(payment.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void shouldRejectRefund_whenPaymentFailed() {
        Payment payment = Payment.failed("order-1", new BigDecimal("50.00"), "declined", NOW);

        assertThatThrownBy(() -> payment.refund(NOW)).isInstanceOf(RefundNotAllowedException.class);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void shouldReportSuccessOnlyForCompletedPayments() {
        assertThat(Payment.completed("o", BigDecimal.TEN, NOW).isSuccessful()).isTrue();
        assertThat(Payment.failed("o", BigDecimal.TEN, "declined", NOW).isSuccessful()).isFalse();
    }
}
