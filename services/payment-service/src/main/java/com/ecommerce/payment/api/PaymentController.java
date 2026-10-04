package com.ecommerce.payment.api;

import com.ecommerce.payment.api.dto.PaymentResponse;
import com.ecommerce.payment.api.dto.ProcessPaymentRequest;
import com.ecommerce.payment.application.ProcessPaymentResult;
import com.ecommerce.payment.application.ProcessPaymentService;
import com.ecommerce.payment.application.RefundPaymentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    public static final String IDEMPOTENT_REPLAYED = "Idempotent-Replayed";

    private final ProcessPaymentService processPaymentService;
    private final RefundPaymentService refundPaymentService;

    public PaymentController(ProcessPaymentService processPaymentService, RefundPaymentService refundPaymentService) {
        this.processPaymentService = processPaymentService;
        this.refundPaymentService = refundPaymentService;
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> processPayment(
            @RequestHeader(IDEMPOTENCY_KEY) @NotBlank @Size(max = 100) String idempotencyKey,
            @Valid @RequestBody ProcessPaymentRequest request) {
        ProcessPaymentResult result =
                processPaymentService.process(idempotencyKey, request.orderId(), request.amount());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(result.httpStatus());
        if (result.replayed()) {
            response.header(IDEMPOTENT_REPLAYED, "true");
        }
        return response.body(PaymentResponse.from(result.payment()));
    }

    @PostMapping("/{paymentId}/refund")
    public PaymentResponse refund(@PathVariable UUID paymentId) {
        return PaymentResponse.from(refundPaymentService.refund(paymentId));
    }
}
