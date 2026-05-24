package com.platform.payments.payment;

public record CaptureOutcome(
        PaymentStatus status          // CAPTURED
) {
    public static CaptureOutcome captured() {
        return new CaptureOutcome(PaymentStatus.CAPTURED);
    }
}
