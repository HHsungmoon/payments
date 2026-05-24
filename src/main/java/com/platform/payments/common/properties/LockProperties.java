package com.platform.payments.common.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Redis 분산락 TTL
@ConfigurationProperties(prefix = "app.lock")
public record LockProperties(
        int bookingTtlSeconds,
        int outboxTtlSeconds,
        int reconcileTtlSeconds
) {
}
