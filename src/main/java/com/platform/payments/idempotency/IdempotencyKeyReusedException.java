package com.platform.payments.idempotency;

import lombok.Getter;

// 같은 Idempotency-Key + 다른 body → 422 IDEMPOTENCY_KEY_REUSED
@Getter
public class IdempotencyKeyReusedException extends RuntimeException {

    private final String key;
    private final String expectedHash;
    private final String receivedHash;

    public IdempotencyKeyReusedException(String key, String expectedHash, String receivedHash) {
        super("idempotency key reused with different body: key=%s expected=%s received=%s"
                .formatted(key, expectedHash, receivedHash));
        this.key = key;
        this.expectedHash = expectedHash;
        this.receivedHash = receivedHash;
    }
}
