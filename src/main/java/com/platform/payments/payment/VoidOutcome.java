package com.platform.payments.payment;

public record VoidOutcome(
        PaymentStatus status          // VOIDED
) {
    public static VoidOutcome voided() {
        return new VoidOutcome(PaymentStatus.VOIDED);
    }
}
