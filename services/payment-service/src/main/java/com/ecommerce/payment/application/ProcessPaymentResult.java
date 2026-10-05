package com.ecommerce.payment.application;

/**
 * @param httpStatus status of the first response for this key ({@code 201} charged, {@code 200} order already paid)
 * @param payment    the payment
 * @param replayed   {@code true} if this is a stored response returned for a retried key
 */
public record ProcessPaymentResult(int httpStatus, PaymentView payment, boolean replayed) {
}
