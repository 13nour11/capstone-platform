package com.ecommerce.payment.application;

import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/** Stands in for an external payment provider; the failure rate drives the compensation path in tests and demos. */
@Component
public class PaymentSimulator {

    static final String DECLINED_REASON = "Payment declined by provider (simulated)";

    private final PaymentSimulationProperties properties;

    public PaymentSimulator(PaymentSimulationProperties properties) {
        this.properties = properties;
    }

    public boolean declines() {
        return ThreadLocalRandom.current().nextDouble() < properties.failureRate();
    }
}
