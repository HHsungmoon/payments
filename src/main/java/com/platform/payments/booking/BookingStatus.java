package com.platform.payments.booking;

// Booking 거시상태 (v4)
public enum BookingStatus {
    WAITING,    // 대기열 진입
    PENDING,    // slot 잡고 결제 진행 중
    PAID,
    FAILED,
    EXPIRED     // 대기/slot 만료
}
