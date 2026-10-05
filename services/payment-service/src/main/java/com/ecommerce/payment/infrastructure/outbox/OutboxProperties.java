package com.ecommerce.payment.infrastructure.outbox;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * @param batchSize      maximum events published per poll
 * @param sendTimeout    how long to wait for the broker to acknowledge one event
 */
@Validated
@ConfigurationProperties(prefix = "outbox")
public record OutboxProperties(
        @DefaultValue("50") @Min(1) int batchSize,
        @DefaultValue("5s") @NotNull Duration sendTimeout) {
}
