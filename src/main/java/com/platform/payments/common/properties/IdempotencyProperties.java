package com.platform.payments.common.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

// 멱등성 정책
@ConfigurationProperties(prefix = "app.idempotency")
public record IdempotencyProperties(
        int ttlSeconds,                       // Redis idem 캐시 TTL
        int keyMinLength,
        int keyMaxLength,
        String serverFallbackPrefix           // 헤더 누락 시 자동 생성 키 prefix
) {
}
