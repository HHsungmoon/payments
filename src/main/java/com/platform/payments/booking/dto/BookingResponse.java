package com.platform.payments.booking.dto;

import com.platform.payments.payment.PaymentMethod;
import com.platform.payments.payment.PaymentStatus;
import java.time.Instant;
import java.util.List;

// 200 PAID 응답
public record BookingResponse(
        String status,                    // "PAID"
        Long bookingId,
        Long totalAmount,
        Instant paidAt,
        List<PaymentDetail> payments
) {
    public record PaymentDetail(
            PaymentMethod method,
            long amount,
            PaymentStatus status,
            String pgAuthId
    ) {
    }
}
