package com.platform.payments.pg;

public record AuthorizeResult(
        String authId,
        String status                 // "AUTHORIZED"
) {
}
