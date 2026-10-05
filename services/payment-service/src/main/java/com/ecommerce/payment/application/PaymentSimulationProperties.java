package com.ecommerce.payment.application;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * @param failureRate share of charges the simulated provider declines, from 0.0 (never) to 1.0 (always)
 */
@Validated
@ConfigurationProperties(prefix = "payment.simulation")
public record PaymentSimulationProperties(
        @DefaultValue("0.0") @DecimalMin("0.0") @DecimalMax("1.0") double failureRate) {
}
