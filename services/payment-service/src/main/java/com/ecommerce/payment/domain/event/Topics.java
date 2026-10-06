package com.ecommerce.payment.domain.event;

/** Kafka topic names from the Capstone Brief §4. */
public final class Topics {

    public static final String INVENTORY_EVENTS = "inventory-events";
    public static final String PAYMENT_EVENTS = "payment-events";

    private Topics() {
    }
}
