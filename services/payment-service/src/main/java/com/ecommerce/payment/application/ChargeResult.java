package com.ecommerce.payment.application;

import com.ecommerce.payment.domain.Payment;

/**
 * @param payment the order's payment
 * @param created {@code true} if this call charged the order, {@code false} if it already had a payment
 */
public record ChargeResult(Payment payment, boolean created) {
}
