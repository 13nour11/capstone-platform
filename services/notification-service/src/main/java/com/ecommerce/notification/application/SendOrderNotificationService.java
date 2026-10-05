package com.ecommerce.notification.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ecommerce.notification.domain.OrderNotification;

import io.micrometer.core.instrument.MeterRegistry;

/** FR-11: tells the customer about CONFIRMED and CANCELLED orders, at most once per event. */
@Service
public class SendOrderNotificationService {

    private static final Logger log = LoggerFactory.getLogger(SendOrderNotificationService.class);

    private final NotificationSender sender;
    private final RecentEventIds recentEventIds;
    private final MeterRegistry meterRegistry;

    public SendOrderNotificationService(NotificationSender sender, RecentEventIds recentEventIds,
                                        MeterRegistry meterRegistry) {
        this.sender = sender;
        this.recentEventIds = recentEventIds;
        this.meterRegistry = meterRegistry;
    }

    public void notifyCustomer(OrderNotification notification) {
        if (recentEventIds.contains(notification.eventId())) {
            log.info("Duplicate event ignored eventId={} orderId={}", notification.eventId(), notification.orderId());
            meterRegistry.counter("notifications.duplicates").increment();
            return;
        }
        sender.send(notification);
        // Remembered only after a successful send, so a failed attempt is retried
        recentEventIds.add(notification.eventId());
        meterRegistry.counter("notifications.sent", "type", notification.type().name()).increment();
    }
}
