package com.ecommerce.order.domain;

import com.ecommerce.order.domain.exception.IllegalOrderStateException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderDomainTest {

    @Test
    @DisplayName("Should successfully transition order from PENDING to CONFIRMED")
    void shouldTransitionToConfirmed_whenInPending() {
        Order order = Order.builder()
                .id("ord-1")
                .customerId("cust-1")
                .status(OrderStatus.PENDING)
                .build();

        order.confirm();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    @DisplayName("Should successfully transition order from PENDING to CANCELLED")
    void shouldTransitionToCancelled_whenInPending() {
        Order order = Order.builder()
                .id("ord-1")
                .customerId("cust-1")
                .status(OrderStatus.PENDING)
                .build();

        order.cancel();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("Should throw IllegalOrderStateException when trying to confirm a non-pending order")
    void shouldThrowException_whenConfirmingNonPendingOrder() {
        Order order = Order.builder()
                .id("ord-1")
                .customerId("cust-1")
                .status(OrderStatus.CANCELLED)
                .build();

        assertThatThrownBy(order::confirm)
                .isInstanceOf(IllegalOrderStateException.class)
                .hasMessageContaining("Cannot transition order ord-1 from status CANCELLED to CONFIRMED");
    }

    @Test
    @DisplayName("Should calculate item subtotal correctly")
    void shouldCalculateItemSubtotal() {
        OrderItem item = new OrderItem(10L, 3, new BigDecimal("25.50"));

        assertThat(item.getSubtotal()).isEqualByComparingTo(new BigDecimal("76.50"));
    }
}
