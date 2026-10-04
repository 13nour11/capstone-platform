package com.ecommerce.order.domain.exception;

public class OutOfStockException extends RuntimeException {
    private final Long productId;

    public OutOfStockException(Long productId) {
        super(String.format("Product %d is out of stock", productId));
        this.productId = productId;
    }

    public Long getProductId() {
        return productId;
    }
}
