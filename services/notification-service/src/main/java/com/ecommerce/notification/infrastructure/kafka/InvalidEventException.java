package com.ecommerce.notification.infrastructure.kafka;

/** A message that can never be processed (poison message): it goes straight to the DLT, without retries. */
class InvalidEventException extends RuntimeException {

    InvalidEventException(String message) {
        super(message);
    }

    InvalidEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
