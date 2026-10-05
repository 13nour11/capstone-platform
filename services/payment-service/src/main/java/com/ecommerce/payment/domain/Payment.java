package com.ecommerce.payment.domain;

import com.ecommerce.payment.domain.exception.RefundNotAllowedException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "payments")
public class Payment {

    @Id
    private UUID id;

    @Column(name = "order_id", nullable = false, updatable = false, length = 64)
    private String orderId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PaymentStatus status;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Payment() {
    }

    private Payment(String orderId, BigDecimal amount, PaymentStatus status, String failureReason, Instant now) {
        this.id = UUID.randomUUID();
        this.orderId = Objects.requireNonNull(orderId, "orderId");
        this.amount = Objects.requireNonNull(amount, "amount");
        this.status = status;
        this.failureReason = failureReason;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static Payment completed(String orderId, BigDecimal amount, Instant now) {
        return new Payment(orderId, amount, PaymentStatus.COMPLETED, null, now);
    }

    public static Payment failed(String orderId, BigDecimal amount, String reason, Instant now) {
        return new Payment(orderId, amount, PaymentStatus.FAILED, Objects.requireNonNull(reason, "reason"), now);
    }

    /**
     * Refunds a completed payment. A second refund is a no-op, so retried refund calls never refund twice.
     *
     * @return {@code true} if this call changed the payment, {@code false} if it was already refunded
     */
    public boolean refund(Instant now) {
        return switch (status) {
            case COMPLETED -> {
                status = PaymentStatus.REFUNDED;
                updatedAt = now;
                yield true;
            }
            case REFUNDED -> false;
            case FAILED -> throw new RefundNotAllowedException(id, status);
        };
    }

    public boolean isSuccessful() {
        return status == PaymentStatus.COMPLETED;
    }

    public UUID getId() {
        return id;
    }

    public String getOrderId() {
        return orderId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
