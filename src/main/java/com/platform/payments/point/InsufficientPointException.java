package com.platform.payments.point;

import lombok.Getter;

// 도메인 예외 — Service 계층에서 FailureReason.INSUFFICIENT_POINT 로 매핑
@Getter
public class InsufficientPointException extends RuntimeException {

    private final Long customerId;
    private final long currentBalance;
    private final long requested;

    public InsufficientPointException(Long customerId, long currentBalance, long requested) {
        super("insufficient point: customerId=%d balance=%d requested=%d".formatted(customerId, currentBalance, requested));
        this.customerId = customerId;
        this.currentBalance = currentBalance;
        this.requested = requested;
    }

}
