package com.ecommerce.payment.infrastructure.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Instant;

class IdempotencyKeyClaimOperationsImpl implements IdempotencyKeyClaimOperations {

    private final JdbcClient jdbcClient;

    IdempotencyKeyClaimOperationsImpl(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public boolean claim(String idempotencyKey, String requestHash, Instant now) {
        int inserted = jdbcClient.sql("""
                        INSERT INTO idempotency_keys (idempotency_key, request_hash, created_at)
                        VALUES (:key, :hash, :createdAt)
                        ON CONFLICT (idempotency_key) DO NOTHING
                        """)
                .param("key", idempotencyKey)
                .param("hash", requestHash)
                .param("createdAt", Timestamp.from(now))
                .update();
        return inserted == 1;
    }
}
