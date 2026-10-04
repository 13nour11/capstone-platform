package com.ecommerce.payment.infrastructure.persistence;

import com.ecommerce.payment.domain.Payment;

public interface PaymentInsertOperations {

    /**
     * Inserts the payment unless one already exists for its order.
     *
     * @return {@code true} if this call inserted the row
     */
    boolean insertIfAbsent(Payment payment);
}
