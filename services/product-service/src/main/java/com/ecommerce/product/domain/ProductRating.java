package com.ecommerce.product.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Bonus B1 read model: one rating per review, copied from ReviewSubmitted events. */
@Entity
@Table(name = "product_review_ratings")
public class ProductRating {

    @Id
    @Column(name = "review_id", length = 64)
    private String reviewId;

    @Column(name = "product_id", nullable = false)
    private long productId;

    @Column(nullable = false)
    private int rating;

    protected ProductRating() {
    }

    public String getReviewId() {
        return reviewId;
    }

    public long getProductId() {
        return productId;
    }

    public int getRating() {
        return rating;
    }
}
