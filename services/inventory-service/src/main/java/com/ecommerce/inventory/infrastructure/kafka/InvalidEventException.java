package com.ecommerce.inventory.infrastructure.kafka;

/**
 * A record that can never be processed (unreadable JSON). The error handler sends it straight to
 * the DLT instead of retrying it.
 */
public class InvalidEventException extends RuntimeException {
    public InvalidEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
