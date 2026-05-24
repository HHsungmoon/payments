package com.platform.payments.common.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Outbox 워커 (SKIP LOCKED + 지수 백오프)
@ConfigurationProperties(prefix = "app.outbox")
public record OutboxProperties(
        long pollFixedDelayMs,
        int batchSize,
        int maxRetries                        // 초과 시 DEAD_LETTER
) {
}
