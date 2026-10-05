package com.ecommerce.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** NFR-05: an order this service has seen cancelled (PaymentFailed or OrderCancelled). */
@Entity
@Table(name = "cancelled_order")
public class CancelledOrder {

    @Id
    @Column(name = "order_id", length = 64)
    private String orderId;

    @Column(name = "cancelled_at", nullable = false)
    private Instant cancelledAt;

    protected CancelledOrder() {
    }

    public CancelledOrder(String orderId, Instant cancelledAt) {
        this.orderId = orderId;
        this.cancelledAt = cancelledAt;
    }

    public String getOrderId() {
        return orderId;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }
}
