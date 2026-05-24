package com.platform.payments.common.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

// 비동기 결제 ThreadPool (대기자 승격 후 백그라운드 결제)
@ConfigurationProperties(prefix = "app.payment-executor")
public record PaymentExecutorProperties(
        int corePoolSize,
        int maxPoolSize,
        int queueCapacity,
        String threadNamePrefix,
        int keepAliveSeconds
) {
}
