package com.ecommerce.payment.application;

import com.ecommerce.payment.domain.Payment;
import com.ecommerce.payment.infrastructure.persistence.PaymentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;

/**
 * Charges an order at most once. The unique constraint on {@code payments.order_id} decides
 * the winner when two requests race, so no check-then-insert gap exists.
 */
@Service
public class ChargePaymentService {

    private final PaymentRepository payments;
    private final PaymentSimulator simulator;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public ChargePaymentService(PaymentRepository payments, PaymentSimulator simulator,
                                MeterRegistry meterRegistry, Clock clock) {
        this.payments = payments;
        this.simulator = simulator;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ChargeResult charge(String orderId, BigDecimal amount) {
        var existing = payments.findByOrderId(orderId);
        if (existing.isPresent()) {
            return new ChargeResult(existing.get(), false);
        }

        Instant now = clock.instant();
        Payment candidate = simulator.declines()
                ? Payment.failed(orderId, amount, PaymentSimulator.DECLINED_REASON, now)
                : Payment.completed(orderId, amount, now);

        boolean created = payments.insertIfAbsent(candidate);
        Payment payment = payments.findByOrderId(orderId)
                .orElseThrow(() -> new IllegalStateException("Payment for order " + orderId + " vanished"));
        if (created) {
            AfterCommit.run(() -> meterRegistry.counter("payments.processed",
                    "outcome", payment.getStatus().name()).increment());
        }
        return new ChargeResult(payment, created);
    }
}
