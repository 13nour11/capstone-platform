package com.ecommerce.review.domain;

public class DuplicateReviewException extends RuntimeException {

    public DuplicateReviewException(long productId) {
        super("You have already reviewed product " + productId);
    }
}
