package com.ecommerce.inventory.domain;

import java.io.Serializable;
import java.util.Objects;

public class ReservationId implements Serializable {

    private String orderId;
    private Long productId;

    public ReservationId() {
    }

    public ReservationId(String orderId, Long productId) {
        this.orderId = orderId;
        this.productId = productId;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ReservationId that)) return false;
        return Objects.equals(orderId, that.orderId) && Objects.equals(productId, that.productId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(orderId, productId);
    }
}
