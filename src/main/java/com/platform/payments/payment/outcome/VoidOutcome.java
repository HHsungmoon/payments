package com.platform.payments.payment.outcome;

import com.platform.payments.payment.PaymentStatus;

public record VoidOutcome(
        PaymentStatus status          // VOIDED
) {
    public static VoidOutcome voided() {
        return new VoidOutcome(PaymentStatus.VOIDED);
    }
}
