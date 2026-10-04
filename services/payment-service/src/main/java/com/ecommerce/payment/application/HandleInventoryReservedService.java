package com.ecommerce.payment.application;

import com.ecommerce.payment.domain.Payment;
import com.ecommerce.payment.domain.event.InventoryReserved;
import com.ecommerce.payment.domain.event.PaymentCompleted;
import com.ecommerce.payment.domain.event.PaymentFailed;
import com.ecommerce.payment.domain.event.Topics;
import com.ecommerce.payment.infrastructure.outbox.OutboxWriter;
import com.ecommerce.payment.infrastructure.persistence.ProcessedEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Saga step: charges the order whose stock was reserved and records the outcome event.
 * The dedup row, the payment and the outbox row commit together or not at all.
 */
@Service
public class HandleInventoryReservedService {

    static final String CONSUMER = "payment-service";
    private static final Logger log = LoggerFactory.getLogger(HandleInventoryReservedService.class);

    private final ProcessedEventRepository processedEvents;
    private final ChargePaymentService chargePaymentService;
    private final OutboxWriter outbox;
    private final Clock clock;

    public HandleInventoryReservedService(ProcessedEventRepository processedEvents,
                                          ChargePaymentService chargePaymentService,
                                          OutboxWriter outbox, Clock clock) {
        this.processedEvents = processedEvents;
        this.chargePaymentService = chargePaymentService;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Transactional
    public void handle(InventoryReserved event) {
        Instant now = clock.instant();
        if (!processedEvents.markProcessed(event.eventId(), CONSUMER, now)) {
            log.info("Skipping duplicate InventoryReserved {} for order {}", event.eventId(), event.orderId());
            return;
        }

        Payment payment = chargePaymentService.charge(event.orderId(), event.totalAmount()).payment();
        UUID eventId = UUID.randomUUID();
        Object outcome = payment.isSuccessful()
                ? new PaymentCompleted(eventId.toString(), payment.getOrderId(), payment.getId().toString(),
                        payment.getAmount(), now)
                : new PaymentFailed(eventId.toString(), payment.getOrderId(), failureReason(payment), now);
        outbox.write(eventId, payment.getOrderId(), Topics.PAYMENT_EVENTS, outcome);
        log.info("Order {} payment {} -> {}", payment.getOrderId(), payment.getId(), payment.getStatus());
    }

    private static String failureReason(Payment payment) {
        return payment.getFailureReason() != null
                ? payment.getFailureReason()
                : "Payment is " + payment.getStatus();
    }
}
