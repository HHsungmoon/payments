package com.platform.payments.pg;

// Circuit Breaker OPEN / Bulkhead full — 시스템 보호 reject
public class PgUnavailableException extends RuntimeException {

    public PgUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
