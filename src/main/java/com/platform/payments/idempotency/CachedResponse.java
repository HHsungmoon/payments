package com.platform.payments.idempotency;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

// Redis idem:{key} 에 직렬화되어 저장
public record CachedResponse(
        int status,
        String body,
        @JsonProperty("request_hash") String requestHash,
        @JsonProperty("cached_at") Instant cachedAt
) {
}
