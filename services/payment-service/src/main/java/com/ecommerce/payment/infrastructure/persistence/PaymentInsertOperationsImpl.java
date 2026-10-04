package com.ecommerce.payment.infrastructure.persistence;

import com.ecommerce.payment.domain.Payment;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.sql.Types;

class PaymentInsertOperationsImpl implements PaymentInsertOperations {

    private final JdbcClient jdbcClient;

    PaymentInsertOperationsImpl(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public boolean insertIfAbsent(Payment payment) {
        int inserted = jdbcClient.sql("""
                        INSERT INTO payments (id, order_id, amount, status, failure_reason, created_at, updated_at, version)
                        VALUES (:id, :orderId, :amount, :status, :failureReason, :createdAt, :updatedAt, 0)
                        ON CONFLICT (order_id) DO NOTHING
                        """)
                .param("id", payment.getId())
                .param("orderId", payment.getOrderId())
                .param("amount", payment.getAmount())
                .param("status", payment.getStatus().name())
                .param("failureReason", payment.getFailureReason(), Types.VARCHAR)
                .param("createdAt", Timestamp.from(payment.getCreatedAt()))
                .param("updatedAt", Timestamp.from(payment.getUpdatedAt()))
                .update();
        return inserted == 1;
    }
}
