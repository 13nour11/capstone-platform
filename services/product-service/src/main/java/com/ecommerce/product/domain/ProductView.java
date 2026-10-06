package com.ecommerce.product.domain;

import java.math.BigDecimal;

/** Read model (FR-03): one product joined with its category name, loaded in a single query. */
public interface ProductView {

    Long getId();

    String getName();

    String getDescription();

    BigDecimal getPrice();

    Long getCategoryId();

    String getCategoryName();

    /** B1: average of all ratings, null while the product has none. */
    Double getAverageRating();

    /** B1: number of ratings. */
    Long getRatingCount();
}
