package com.ecommerce.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * An order whose saga ended in cancellation. Recorded when the cancel event is consumed so the
 * sweeper can release stock held for a dead saga without touching an order still awaiting payment.
 */
@Entity
@Table(name = "cancelled_order")
public class CancelledOrder {

    @Id
    @Column(name = "order_id", length = 64)
    private String orderId;

    @Column(name = "cancelled_at", nullable = false)
    private Instant cancelledAt;

    public CancelledOrder() {
    }

    public CancelledOrder(String orderId, Instant cancelledAt) {
        this.orderId = orderId;
        this.cancelledAt = cancelledAt;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public void setCancelledAt(Instant cancelledAt) {
        this.cancelledAt = cancelledAt;
    }
}
