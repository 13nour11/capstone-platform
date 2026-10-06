package com.ecommerce.payment.application;

import org.junit.jupiter.api.RepeatedTest;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentSimulatorTest {

    @RepeatedTest(20)
    void shouldNeverDecline_whenFailureRateIsZero() {
        assertThat(new PaymentSimulator(new PaymentSimulationProperties(0.0)).declines()).isFalse();
    }

    @RepeatedTest(20)
    void shouldAlwaysDecline_whenFailureRateIsOne() {
        assertThat(new PaymentSimulator(new PaymentSimulationProperties(1.0)).declines()).isTrue();
    }
}
