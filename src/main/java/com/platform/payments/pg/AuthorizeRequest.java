package com.platform.payments.pg;

public record AuthorizeRequest(
        String idempotencyKey,
        String method,
        long amount
) {
}
