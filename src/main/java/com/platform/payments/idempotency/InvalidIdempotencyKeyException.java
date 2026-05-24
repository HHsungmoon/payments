package com.platform.payments.idempotency;

// Idempotency-Key 헤더 형식 위반 → 400
public class InvalidIdempotencyKeyException extends RuntimeException {

    public InvalidIdempotencyKeyException(String reason) {
        super(reason);
    }
}
