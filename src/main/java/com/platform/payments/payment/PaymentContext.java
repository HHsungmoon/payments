package com.platform.payments.payment;

public record PaymentContext(
        Long bookingId,
        Long customerId,
        long amount
) {
}
