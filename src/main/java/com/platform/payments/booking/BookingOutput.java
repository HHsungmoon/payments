package com.platform.payments.booking;

import com.platform.payments.idempotency.CachedResponse;

// BookingService → Controller 결과 wrapper
public record BookingOutput(
        int httpStatus,
        String idemKey,
        Object body,                      // BookingResponse / WaitingResponse / ErrorResponse 또는 raw String (cached)
        Integer retryAfterSeconds         // optional
) {

    public static BookingOutput paid(String idemKey, BookingResponse body) {
        return new BookingOutput(200, idemKey, body, null);
    }

    public static BookingOutput waiting(String idemKey, WaitingResponse body) {
        return new BookingOutput(200, idemKey, body, null);
    }

    public static BookingOutput soldOut(String idemKey) {
        return new BookingOutput(409, idemKey,
                new com.platform.payments.common.ErrorResponse("SOLD_OUT",
                        "재고 매진 + 대기열 가득. 1분 후 재시도 권장."),
                60);
    }

    public static BookingOutput alreadyReserved(String idemKey) {
        return new BookingOutput(409, idemKey,
                new com.platform.payments.common.ErrorResponse("ALREADY_RESERVED",
                        "이미 진행 중이거나 완료된 예약이 있습니다."),
                null);
    }

    public static BookingOutput inProgress(String idemKey) {
        return new BookingOutput(409, idemKey,
                new com.platform.payments.common.ErrorResponse("IN_PROGRESS",
                        "이전 요청 처리 중입니다. 잠시 후 다시 시도하세요."),
                5);
    }

    public static BookingOutput failed(String idemKey, int httpStatus, String reason, String message) {
        return new BookingOutput(httpStatus, idemKey,
                new com.platform.payments.common.ErrorResponse(reason, message),
                null);
    }

    // 캐시 응답 재생
    public static BookingOutput fromCached(String idemKey, CachedResponse cached) {
        return new BookingOutput(cached.status(), idemKey, cached.body(), null);
    }

    // raw String body 인지 (캐시 재생) — Controller 분기용
    public boolean isRawBody() {
        return body instanceof String;
    }
}
