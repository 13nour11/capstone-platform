package com.ecommerce.product.api;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.ecommerce.product.domain.CategoryNotFoundException;
import com.ecommerce.product.domain.ProductNotFoundException;

/** One error contract (RFC 7807 + stable {@code code}); never leaks stack traces or SQL. */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String VALIDATION_ERROR = "VALIDATION_ERROR";

    @ExceptionHandler(ProductNotFoundException.class)
    ResponseEntity<Object> productNotFound(ProductNotFoundException ex, WebRequest request) {
        return problem(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(CategoryNotFoundException.class)
    ResponseEntity<Object> categoryNotFound(CategoryNotFoundException ex, WebRequest request) {
        return validationError(request, List.of(fieldError("categoryId", ex.getMessage())));
    }

    @ExceptionHandler(SortParameter.InvalidSortException.class)
    ResponseEntity<Object> invalidSort(SortParameter.InvalidSortException ex, WebRequest request) {
        return validationError(request, List.of(fieldError("sort", ex.getMessage())));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<Object> concurrentUpdate(OptimisticLockingFailureException ex, WebRequest request) {
        return problem(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION",
                "The product was changed by another request; reload and retry", request);
    }

    /** {@code @PreAuthorize} denials surface here; anonymous callers never reach the controller. */
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Object> accessDenied(AccessDeniedException ex, WebRequest request) {
        return problem(HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission to access this resource",
                request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception ex, WebRequest request) {
        log.error("Unhandled error on {}", path(request), ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected error", request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> fieldError(error.getField(), error.getDefaultMessage()))
                .toList();
        return validationError(request, errors);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                            HttpHeaders headers,
                                                                            HttpStatusCode status,
                                                                            WebRequest request) {
        List<Map<String, String>> errors = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> fieldError(result.getMethodParameter().getParameterName(),
                                error.getDefaultMessage())))
                .toList();
        return validationError(request, errors);
    }

    /** Adds the stable code to framework errors (malformed JSON, wrong method, unknown path, ...). */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            HttpStatus status = HttpStatus.resolve(statusCode.value());
            String code = status == HttpStatus.BAD_REQUEST ? VALIDATION_ERROR
                    : status != null ? status.name() : "ERROR";
            decorate(problem, code, request);
        }
        return response;
    }

    private static ResponseEntity<Object> problem(HttpStatus status, String code, String detail, WebRequest request) {
        return ResponseEntity.status(status).body(problemDetail(status, code, detail, request));
    }

    private static ResponseEntity<Object> validationError(WebRequest request, List<Map<String, String>> errors) {
        ProblemDetail problem = problemDetail(HttpStatus.BAD_REQUEST, VALIDATION_ERROR, "Request validation failed",
                request);
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    private static ProblemDetail problemDetail(HttpStatus status, String code, String detail, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        decorate(problem, code, request);
        return problem;
    }

    private static void decorate(ProblemDetail problem, String code, WebRequest request) {
        problem.setInstance(URI.create(path(request)));
        problem.setProperty("code", code);
        problem.setProperty("timestamp", Instant.now().toString());
    }

    private static Map<String, String> fieldError(String field, String message) {
        return Map.of("field", field, "message", Objects.requireNonNullElse(message, "is invalid"));
    }

    private static String path(WebRequest request) {
        return request instanceof ServletWebRequest servlet ? servlet.getRequest().getRequestURI() : "";
    }
}
