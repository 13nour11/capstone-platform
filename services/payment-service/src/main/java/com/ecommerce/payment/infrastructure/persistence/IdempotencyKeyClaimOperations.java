package com.ecommerce.payment.infrastructure.persistence;

import java.time.Instant;

public interface IdempotencyKeyClaimOperations {

    /**
     * Claims the key for this request. A concurrent request with the same key waits on the
     * primary key until the first transaction ends, then sees the stored result.
     *
     * @return {@code true} if this call claimed the key, {@code false} if it was already used
     */
    boolean claim(String idempotencyKey, String requestHash, Instant now);
}
