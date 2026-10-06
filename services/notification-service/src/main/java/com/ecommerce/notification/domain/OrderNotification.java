package com.ecommerce.notification.domain;

/**
 * A message to one customer about one order.
 *
 * @param eventId the id of the Kafka event that caused it, used to ignore redeliveries
 * @param reason  why the order was cancelled; null for confirmations
 */
public record OrderNotification(String eventId, Type type, String orderId, String customerId, String reason) {

    public enum Type {
        ORDER_CONFIRMED,
        ORDER_CANCELLED
    }
}
