package com.ecommerce.payment.api;

import com.ecommerce.payment.domain.exception.IdempotencyKeyReusedException;
import com.ecommerce.payment.domain.exception.PaymentNotFoundException;
import com.ecommerce.payment.domain.exception.RefundNotAllowedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Every error leaves the service as an RFC 7807 problem with a stable {@code code}; no internals leak. */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String CODE = "code";

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    ProblemDetail handleKeyReused(IdempotencyKeyReusedException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.IDEMPOTENCY_KEY_REUSED, e.getMessage());
    }

    @ExceptionHandler(PaymentNotFoundException.class)
    ProblemDetail handleNotFound(PaymentNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, ErrorCode.PAYMENT_NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(RefundNotAllowedException.class)
    ProblemDetail handleRefundNotAllowed(RefundNotAllowedException e) {
        return problem(HttpStatus.CONFLICT, ErrorCode.REFUND_NOT_ALLOWED, e.getMessage());
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ProblemDetail handleConcurrentUpdate(ObjectOptimisticLockingFailureException e) {
        return problem(HttpStatus.CONFLICT, ErrorCode.CONCURRENT_UPDATE,
                "The payment was changed by another request; retry the call");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception e) {
        log.error("Unhandled error", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR, "Unexpected error");
    }

    /** Adds the stable code to the framework's own 400 problems (bean validation, missing header, bad JSON). */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem
                && statusCode.value() == HttpStatus.BAD_REQUEST.value()) {
            problem.setProperty(CODE, ErrorCode.VALIDATION_ERROR.name());
        }
        return response;
    }

    private static ProblemDetail problem(HttpStatus status, ErrorCode code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty(CODE, code.name());
        return problem;
    }
}
