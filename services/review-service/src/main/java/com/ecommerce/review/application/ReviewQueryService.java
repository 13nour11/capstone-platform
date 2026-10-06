package com.ecommerce.review.application;

import com.ecommerce.review.infrastructure.persistence.ReviewRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ReviewQueryService {

    private final ReviewRepository reviews;

    public ReviewQueryService(ReviewRepository reviews) {
        this.reviews = reviews;
    }

    public Page<ReviewView> forProduct(long productId, Pageable pageable) {
        return reviews.findByProductId(productId, pageable).map(ReviewView::from);
    }
}
