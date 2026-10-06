package com.ecommerce.payment.infrastructure.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "outbox.scheduler", name = "enabled", havingValue = "true", matchIfMissing = true)
class OutboxScheduler {

    private final OutboxPublisher publisher;

    OutboxScheduler(OutboxPublisher publisher) {
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${outbox.poll-interval-ms:500}")
    void publish() {
        publisher.publishPending();
    }
}
