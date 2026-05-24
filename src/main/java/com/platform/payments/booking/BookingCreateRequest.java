package com.platform.payments.booking;

import com.platform.payments.payment.PaymentMethod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;

public record BookingCreateRequest(
        @NotNull Long customerId,
        @NotNull Long productId,
        @NotEmpty @Valid List<PaymentItem> payments
) {
    public record PaymentItem(
            @NotNull PaymentMethod method,
            @Positive long amount
    ) {
    }
}
