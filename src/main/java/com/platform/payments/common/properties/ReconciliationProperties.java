package com.platform.payments.common.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

// 정합성 보정 워커 3종
@ConfigurationProperties(prefix = "app.reconciliation")
public record ReconciliationProperties(
        long zombieCleanupFixedDelayMs,
        long consistencyVerifyFixedDelayMs,
        long waitlistCleanupFixedDelayMs,
        int zombieAfterMinutes,               // 좀비 식별 기준
        int waitExpiryHours                   // WAITING 만료 기준
) {
}
