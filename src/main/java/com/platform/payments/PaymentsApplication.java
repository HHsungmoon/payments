package com.platform.payments;

import com.platform.payments.common.properties.IdempotencyProperties;
import com.platform.payments.common.properties.LockProperties;
import com.platform.payments.common.properties.MockPgProperties;
import com.platform.payments.common.properties.OutboxProperties;
import com.platform.payments.common.properties.PaymentExecutorProperties;
import com.platform.payments.common.properties.ReconciliationProperties;
import com.platform.payments.common.properties.WaitlistProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties({
        WaitlistProperties.class,
        IdempotencyProperties.class,
        LockProperties.class,
        PaymentExecutorProperties.class,
        ReconciliationProperties.class,
        OutboxProperties.class,
        MockPgProperties.class
})
@EnableAsync           // paymentExecutor 비동기 결제 (v4)
@EnableScheduling      // Outbox / Reconciliation 워커
public class PaymentsApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentsApplication.class, args);
    }

}
