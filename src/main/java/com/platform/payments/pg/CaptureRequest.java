package com.platform.payments.pg;

public record CaptureRequest(
        String authId,
        String idempotencyKey
) {
}
