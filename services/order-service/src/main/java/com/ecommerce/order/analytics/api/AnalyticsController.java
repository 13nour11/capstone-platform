package com.ecommerce.order.analytics.api;

import com.ecommerce.order.analytics.api.dto.AnalyticsSummaryResponse;
import com.ecommerce.order.analytics.application.AnalyticsQueryService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/** Bonus B2. ADMIN-only: the API Gateway enforces the role on {@code /api/v1/analytics/**}. */
@RestController
@RequestMapping("/api/v1/analytics")
public class AnalyticsController {

    private final AnalyticsQueryService queryService;

    public AnalyticsController(AnalyticsQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/summary")
    public AnalyticsSummaryResponse summary(
            @RequestParam(defaultValue = "24") @Min(1) @Max(168) int hours) {
        return AnalyticsSummaryResponse.from(queryService.summary(Duration.ofHours(hours)));
    }
}
