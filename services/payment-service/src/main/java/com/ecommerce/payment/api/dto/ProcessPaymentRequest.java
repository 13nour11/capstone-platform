package com.ecommerce.payment.api.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record ProcessPaymentRequest(
        @NotBlank @Size(max = 64) String orderId,
        @NotNull @Positive @Digits(integer = 10, fraction = 2) BigDecimal amount) {
}
