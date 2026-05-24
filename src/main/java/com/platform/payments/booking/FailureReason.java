package com.platform.payments.booking;

// booking.failed_reason / expired_reason (v4)
public enum FailureReason {

    // 사용자/입력
    INSUFFICIENT_POINT,
    INVALID_COMBINATION,

    // 외부 시스템
    PAYMENT_DECLINED,
    LIMIT_EXCEEDED,
    PG_TIMEOUT,
    PG_UNAVAILABLE,

    // 시스템
    CAPTURE_FAILED,
    SYSTEM_ERROR,

    // 사용자 명시적
    USER_CANCELED,

    // 시간 (EXPIRED 상태)
    TIMEOUT,
    SLOT_TIMEOUT,
    WAIT_TIMEOUT
}
