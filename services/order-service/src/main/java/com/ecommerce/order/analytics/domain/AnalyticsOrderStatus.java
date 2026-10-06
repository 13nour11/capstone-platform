package com.ecommerce.order.analytics.domain;

/** Status of an order in the analytics read model; CONFIRMED and CANCELLED are final. */
public enum AnalyticsOrderStatus {
    PENDING,
    CONFIRMED,
    CANCELLED
}
