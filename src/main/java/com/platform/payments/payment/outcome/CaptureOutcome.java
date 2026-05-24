package com.platform.payments.payment.outcome;

import com.platform.payments.payment.PaymentStatus;

public record CaptureOutcome(
        PaymentStatus status          // CAPTURED
) {
    public static CaptureOutcome captured() {
        return new CaptureOutcome(PaymentStatus.CAPTURED);
    }
}
