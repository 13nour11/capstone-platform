package com.ecommerce.product.domain;

public class ProductNotFoundException extends RuntimeException {

    public ProductNotFoundException(long productId) {
        super("Product " + productId + " does not exist");
    }
}
