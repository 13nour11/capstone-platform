package com.ecommerce.review.api.dto;

import com.ecommerce.review.application.ReviewView;
import org.springframework.data.domain.Page;

import java.util.List;

public record ReviewPageResponse(List<ReviewView> content, int page, int size, long totalElements, int totalPages) {

    public static ReviewPageResponse from(Page<ReviewView> page) {
        return new ReviewPageResponse(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(),
                page.getTotalPages());
    }
}
