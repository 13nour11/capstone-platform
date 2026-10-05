package com.ecommerce.inventory.infrastructure.kafka;

/** A record that can never be processed; it goes straight to the dead-letter topic without retries. */
public class InvalidEventException extends RuntimeException {

    public InvalidEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
