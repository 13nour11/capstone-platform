package com.ecommerce.payment.infrastructure.persistence;

import com.ecommerce.payment.domain.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID>, PaymentInsertOperations {

    Optional<Payment> findByOrderId(String orderId);
}
