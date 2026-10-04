package com.ecommerce.payment.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, String>, IdempotencyKeyClaimOperations {
}
