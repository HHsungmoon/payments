package com.platform.payments.booking;

// 동일 user+product 의 활성(WAITING/PENDING/PAID) booking 이 이미 존재
//   1차: BookingService.doBook 의 existsBy 사전 체크 → 즉시 alreadyReserved
//   2차: race 발생 시 DB uk_booking_active UNIQUE 충돌 → BookingPersistence 가 변환
public class AlreadyReservedException extends RuntimeException {
    public AlreadyReservedException(String message) {
        super(message);
    }
}
