package com.ecommerce.order.infrastructure.persistence;

import com.ecommerce.order.domain.Order;
import com.ecommerce.order.domain.OrderItem;
import com.ecommerce.order.domain.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.ecommerce.order.support.PostgresTestcontainersConfig;
import org.springframework.data.domain.PageRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(PostgresTestcontainersConfig.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        // The migrations build the schema, so a drift between them and the entities fails here
        // rather than at startup against the real database.
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
class OrderRepositoryTest {

    @Autowired
    private OrderRepository orderRepository;

    @Test
    @DisplayName("Should save order with cascaded items and retrieve by id")
    void shouldSaveAndRetrieveOrderWithItems() {
        Order order = Order.builder()
                .id("test-ord-1")
                .customerId("cust-100")
                .totalAmount(new BigDecimal("199.98"))
                .status(OrderStatus.PENDING)
                .createdAt(Instant.now())
                .items(List.of(
                        new OrderItem(1L, 2, new BigDecimal("99.99"))
                ))
                .build();

        orderRepository.saveAndFlush(order);

        Optional<Order> found = orderRepository.findById("test-ord-1");
        assertThat(found).isPresent();
        assertThat(found.get().getCustomerId()).isEqualTo("cust-100");
        assertThat(found.get().getItems()).hasSize(1);
        assertThat(found.get().getItems().get(0).getProductId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Should find orders by customerId ordered by creation time descending")
    void shouldFindOrdersByCustomerId() {
        Order order1 = Order.builder()
                .id("ord-a")
                .customerId("cust-200")
                .totalAmount(new BigDecimal("50.00"))
                .status(OrderStatus.PENDING)
                .createdAt(Instant.now().minusSeconds(60))
                .build();

        Order order2 = Order.builder()
                .id("ord-b")
                .customerId("cust-200")
                .totalAmount(new BigDecimal("75.00"))
                .status(OrderStatus.CONFIRMED)
                .createdAt(Instant.now())
                .build();

        orderRepository.saveAllAndFlush(List.of(order1, order2));

        List<Order> orders = orderRepository.findByCustomerIdOrderByCreatedAtDesc("cust-200", PageRequest.of(0, 20));
        assertThat(orders).hasSize(2);
        assertThat(orders.get(0).getId()).isEqualTo("ord-b");
        assertThat(orders.get(1).getId()).isEqualTo("ord-a");

        // One page at a time: the second page of size 1 holds only the older order
        assertThat(orderRepository.findByCustomerIdOrderByCreatedAtDesc("cust-200", PageRequest.of(1, 1)))
                .extracting(Order::getId).containsExactly("ord-a");
    }
}
