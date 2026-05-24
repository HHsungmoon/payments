package com.platform.payments.pg;

public record CaptureResult(
        String txId,
        String status                 // "CAPTURED"
) {
}
