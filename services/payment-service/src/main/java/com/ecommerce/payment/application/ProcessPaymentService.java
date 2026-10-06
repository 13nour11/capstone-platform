package com.ecommerce.payment.application;

import com.ecommerce.payment.domain.exception.IdempotencyKeyReusedException;
import com.ecommerce.payment.infrastructure.persistence.IdempotencyRecord;
import com.ecommerce.payment.infrastructure.persistence.IdempotencyRecordRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;

/**
 * {@code POST /api/v1/payments}: charges once per {@code Idempotency-Key} (FR-08).
 * The first request stores its response under the key; a retry gets the same response back.
 */
@Service
public class ProcessPaymentService {

    private static final int CREATED = 201;
    private static final int OK = 200;

    private final IdempotencyRecordRepository idempotencyKeys;
    private final ChargePaymentService chargePaymentService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ProcessPaymentService(IdempotencyRecordRepository idempotencyKeys, ChargePaymentService chargePaymentService,
                                 ObjectMapper objectMapper, Clock clock) {
        this.idempotencyKeys = idempotencyKeys;
        this.chargePaymentService = chargePaymentService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public ProcessPaymentResult process(String idempotencyKey, String orderId, BigDecimal amount) {
        String requestHash = RequestHash.of(orderId, amount);
        boolean claimed = idempotencyKeys.claim(idempotencyKey, requestHash, clock.instant());
        IdempotencyRecord record = idempotencyKeys.findById(idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("Idempotency key " + idempotencyKey + " vanished"));

        if (!claimed) {
            if (!record.matches(requestHash)) {
                throw new IdempotencyKeyReusedException(idempotencyKey);
            }
            return new ProcessPaymentResult(record.getResponseStatus(), fromJson(record.getResponseBody()), true);
        }

        ChargeResult result = chargePaymentService.charge(orderId, amount);
        PaymentView payment = PaymentView.of(result.payment());
        int status = result.created() ? CREATED : OK;
        record.storeResponse(status, toJson(payment));
        return new ProcessPaymentResult(status, payment, false);
    }

    private String toJson(PaymentView payment) {
        try {
            return objectMapper.writeValueAsString(payment);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot store idempotent response", e);
        }
    }

    private PaymentView fromJson(String body) {
        try {
            return objectMapper.readValue(body, PaymentView.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored idempotent response is unreadable", e);
        }
    }
}
