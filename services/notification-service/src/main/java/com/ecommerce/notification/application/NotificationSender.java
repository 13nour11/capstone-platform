package com.ecommerce.notification.application;

import com.ecommerce.notification.domain.OrderNotification;

/** Delivery channel (e-mail in production). Throws when the message could not be delivered. */
public interface NotificationSender {

    void send(OrderNotification notification);
}
