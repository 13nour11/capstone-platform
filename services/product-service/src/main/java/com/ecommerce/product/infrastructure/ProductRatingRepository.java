package com.ecommerce.product.infrastructure;

import com.ecommerce.product.domain.ProductRating;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRatingRepository extends JpaRepository<ProductRating, String> {

    /**
     * Idempotent: a redelivered ReviewSubmitted hits the primary key and changes nothing.
     * Not tenant data (ratings are keyed by product id), so this native statement is safe for B3.
     *
     * @return 1 when the rating was new, 0 for a duplicate
     */
    @Modifying
    @Query(value = """
            INSERT INTO product_review_ratings (review_id, product_id, rating)
            VALUES (:reviewId, :productId, :rating)
            ON CONFLICT (review_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("reviewId") String reviewId, @Param("productId") long productId,
                       @Param("rating") int rating);
}
