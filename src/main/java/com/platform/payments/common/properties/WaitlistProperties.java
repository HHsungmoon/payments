package com.platform.payments.common.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

// 대기열 시스템 (v4)
@ConfigurationProperties(prefix = "app.waitlist")
public record WaitlistProperties(
        int maxSize,                          // 대기열 최대 인원
        int holdTtlSeconds,
        int slotTtlSeconds,
        int waitTokenTtlSeconds,
        int pollingIntervalMs,                // 클라이언트 권장 polling 주기
        int estimatedWaitSecondsPerSlot       // 사용자 안내용 추정치
) {
}
