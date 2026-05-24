package com.platform.payments.pg;

public record VoidRequest(
        String authId,
        String idempotencyKey
) {
}
