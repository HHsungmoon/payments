package com.platform.payments.payment;

// Payment 미시상태
public enum PaymentStatus {
    REQUESTED,
    AUTHORIZED,
    CAPTURED,
    VOIDED,
    FAILED
}
