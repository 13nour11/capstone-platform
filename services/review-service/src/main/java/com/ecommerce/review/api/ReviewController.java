package com.ecommerce.review.api;

import com.ecommerce.review.api.dto.ReviewPageResponse;
import com.ecommerce.review.api.dto.SubmitReviewRequest;
import com.ecommerce.review.application.ReviewQueryService;
import com.ecommerce.review.application.ReviewView;
import com.ecommerce.review.application.SubmitReviewService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Bonus B1. Reviews live under their product: GET is public, POST needs the CUSTOMER role. */
@Validated
@RestController
@RequestMapping("/api/v1/products/{productId}/reviews")
public class ReviewController {

    private final SubmitReviewService submitReviews;
    private final ReviewQueryService queries;

    public ReviewController(SubmitReviewService submitReviews, ReviewQueryService queries) {
        this.submitReviews = submitReviews;
        this.queries = queries;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReviewView submit(@PathVariable long productId, @Valid @RequestBody SubmitReviewRequest request,
                             @AuthenticationPrincipal Jwt jwt) {
        // The reviewer is always the token subject, never a field the client sends
        return submitReviews.submit(productId, jwt.getSubject(), request.rating(), request.comment());
    }

    @GetMapping
    public ReviewPageResponse list(@PathVariable long productId,
                                   @RequestParam(defaultValue = "0") @Min(0) int page,
                                   @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ReviewPageResponse.from(queries.forProduct(productId, pageable));
    }
}
