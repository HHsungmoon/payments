package com.platform.payments.booking.checkout;

import java.time.LocalDateTime;

// GET /checkout 응답
public record CheckoutResponse(
        Long productId,
        String productName,
        Long price,
        LocalDateTime checkInAt,
        LocalDateTime checkOutAt,
        Integer stockTotal,
        Integer remainingStock,           // Redis 실시간
        Long customerId,
        Long pointBalance
) {
}
