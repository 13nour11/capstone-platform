package com.ecommerce.product.application;

import java.math.BigDecimal;

import com.ecommerce.product.domain.Product;
import com.ecommerce.product.domain.ProductView;

/** What clients see for one product (FR-03: includes the category name). Also the cached value. */
public record ProductDetails(
        Long id,
        String name,
        String description,
        BigDecimal price,
        Long categoryId,
        String categoryName) {

    static ProductDetails from(ProductView view) {
        return new ProductDetails(view.getId(), view.getName(), view.getDescription(), view.getPrice(),
                view.getCategoryId(), view.getCategoryName());
    }

    static ProductDetails from(Product product) {
        return new ProductDetails(product.getId(), product.getName(), product.getDescription(), product.getPrice(),
                product.getCategory().getId(), product.getCategory().getName());
    }
}
