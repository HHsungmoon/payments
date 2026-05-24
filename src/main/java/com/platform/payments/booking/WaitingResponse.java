package com.platform.payments.booking;

// 200 WAITING 응답
public record WaitingResponse(
        String status,                    // "WAITING"
        String waitToken,
        int position,
        int pollingIntervalMs,
        int estimatedWaitSeconds
) {
}
