package com.platform.payments.pg;

// PG 응답 timeout / IOException / 5xx — 일시 장애
public class PgTimeoutException extends RuntimeException {

    public PgTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
