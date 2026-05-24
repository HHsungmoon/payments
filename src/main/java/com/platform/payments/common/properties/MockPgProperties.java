package com.platform.payments.common.properties;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

// Mock PG HTTP 클라이언트
@ConfigurationProperties(prefix = "app.mock-pg")
public record MockPgProperties(
        String baseUrl,
        Duration connectTimeout,
        Duration readTimeout
) {
}
