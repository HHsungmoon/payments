package com.platform.payments.payment;

// 결제 수단 조합 위반 (CARD+YPAY 등) → 422 INVALID_COMBINATION
public class InvalidPaymentCombinationException extends RuntimeException {

    public InvalidPaymentCombinationException(String message) {
        super(message);
    }
}
