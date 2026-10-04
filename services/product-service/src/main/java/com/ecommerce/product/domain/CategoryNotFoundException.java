package com.ecommerce.product.domain;

public class CategoryNotFoundException extends RuntimeException {

    public CategoryNotFoundException(long categoryId) {
        super("Category " + categoryId + " does not exist");
    }
}
