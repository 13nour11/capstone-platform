package com.ecommerce.notification.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ecommerce.notification.application.NotificationSender;
import com.ecommerce.notification.domain.OrderNotification;

/**
 * Stands in for an e-mail provider: the log line is the "e-mail". With
 * {@code notification.simulate-failure=true} every send fails, which demonstrates retry then DLT.
 */
@Component
public class LoggingNotificationSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationSender.class);

    private final boolean simulateFailure;

    public LoggingNotificationSender(@Value("${notification.simulate-failure:false}") boolean simulateFailure) {
        this.simulateFailure = simulateFailure;
    }

    @Override
    public void send(OrderNotification notification) {
        if (simulateFailure) {
            throw new NotificationDeliveryException("Simulated delivery failure for order " + notification.orderId());
        }
        switch (notification.type()) {
            case ORDER_CONFIRMED -> log.info("NOTIFY customer={} orderId={}: your order is CONFIRMED",
                    notification.customerId(), notification.orderId());
            case ORDER_CANCELLED -> log.info("NOTIFY customer={} orderId={}: your order is CANCELLED reason={}",
                    notification.customerId(), notification.orderId(), notification.reason());
        }
    }

    static class NotificationDeliveryException extends RuntimeException {

        NotificationDeliveryException(String message) {
            super(message);
        }
    }
}
