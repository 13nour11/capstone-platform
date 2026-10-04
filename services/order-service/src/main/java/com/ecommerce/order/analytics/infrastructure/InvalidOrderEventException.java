package com.ecommerce.order.analytics.infrastructure;

/** An order event the projection can never apply; it is parked on the DLT without retries. */
public class InvalidOrderEventException extends RuntimeException {

    public InvalidOrderEventException(String message) {
        super(message);
    }

    public InvalidOrderEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
