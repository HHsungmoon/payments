package com.platform.payments.booking;

// GET /booking/wait/{token} polling 응답 — 상태별 다형
public record WaitStatusResponse(
        String status,                    // WAITING / PROCESSING / PAID / FAILED
        Integer position,                 // WAITING 일 때
        Integer pollingIntervalMs,        // WAITING / PROCESSING 일 때
        Long bookingId,                   // PAID 일 때
        String reason                     // FAILED 일 때
) {
    public static WaitStatusResponse waiting(int position, int intervalMs) {
        return new WaitStatusResponse("WAITING", position, intervalMs, null, null);
    }
    public static WaitStatusResponse processing(int intervalMs) {
        return new WaitStatusResponse("PROCESSING", null, intervalMs, null, null);
    }
    public static WaitStatusResponse paid(Long bookingId) {
        return new WaitStatusResponse("PAID", null, null, bookingId, null);
    }
    public static WaitStatusResponse failed(String reason) {
        return new WaitStatusResponse("FAILED", null, null, null, reason);
    }
}
