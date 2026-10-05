package com.ecommerce.order.infrastructure.persistence;

import com.ecommerce.order.domain.Order;
import com.ecommerce.order.domain.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface OrderRepository extends JpaRepository<Order, String> {

    List<Order> findByCustomerIdOrderByCreatedAtDesc(String customerId);

    List<Order> findByStatusAndCreatedAtBefore(OrderStatus status, Instant cutoff);
}
