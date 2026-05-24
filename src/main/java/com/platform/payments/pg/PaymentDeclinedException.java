package com.platform.payments.pg;

import lombok.Getter;
import org.springframework.http.HttpStatusCode;

// PG 4xx — 사용자 측 사유 (한도 초과, 카드 거절, 잔액 부족 등)
@Getter
public class PaymentDeclinedException extends RuntimeException {

    private final HttpStatusCode statusCode;

    public PaymentDeclinedException(HttpStatusCode statusCode, String message, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }
}
