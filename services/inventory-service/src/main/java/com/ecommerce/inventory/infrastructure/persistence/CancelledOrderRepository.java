package com.ecommerce.inventory.infrastructure.persistence;

import com.ecommerce.inventory.domain.CancelledOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CancelledOrderRepository extends JpaRepository<CancelledOrder, String> {
}
