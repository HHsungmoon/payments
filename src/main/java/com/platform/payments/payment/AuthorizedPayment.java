package com.platform.payments.payment;

// Orchestrator가 authorize 단계 결과로 들고 다니는 짝
public record AuthorizedPayment(
        PaymentMethod method,
        String authReference,
        long amount
) {
}
