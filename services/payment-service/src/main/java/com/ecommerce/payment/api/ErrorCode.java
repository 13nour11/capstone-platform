package com.ecommerce.payment.api;

/** Stable error codes returned in the {@code code} property of every error response. */
public enum ErrorCode {
    VALIDATION_ERROR,
    IDEMPOTENCY_KEY_REUSED,
    PAYMENT_NOT_FOUND,
    REFUND_NOT_ALLOWED,
    CONCURRENT_UPDATE,
    INTERNAL_ERROR
}
