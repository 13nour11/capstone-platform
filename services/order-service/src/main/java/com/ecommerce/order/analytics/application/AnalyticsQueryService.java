package com.ecommerce.order.analytics.application;

import com.ecommerce.order.analytics.domain.AnalyticsSummary;
import com.ecommerce.order.analytics.infrastructure.AnalyticsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

@Service
public class AnalyticsQueryService {

    private final AnalyticsRepository repository;

    public AnalyticsQueryService(AnalyticsRepository repository) {
        this.repository = repository;
    }

    /** Orders placed in the last {@code window}, totalled and broken down per hour. */
    @Transactional(readOnly = true)
    public AnalyticsSummary summary(Duration window) {
        Instant to = Instant.now();
        Instant from = to.minus(window);
        return AnalyticsSummary.of(from, to, repository.hourlyStats(from, to));
    }
}
