package com.platform.payments.common;

// 4xx/5xx 응답 공통 포맷
public record ErrorResponse(
        String reason,                    // 머신 판독용 enum 코드
        String message                    // 사람 읽기용
) {
    public static ErrorResponse of(String reason, String message) {
        return new ErrorResponse(reason, message);
    }
}
