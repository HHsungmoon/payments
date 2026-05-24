package com.platform.payments.payment;

// 외부 입력 — POST /booking body의 payments 배열 요소
public record PaymentRequest(
        PaymentMethod method,
        long amount
) {
}
