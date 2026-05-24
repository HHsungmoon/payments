package com.platform.payments.async;

import com.platform.payments.common.properties.PaymentExecutorProperties;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

// 대기자 승격 후 결제를 polling thread 점유 없이 백그라운드 진행
@Configuration
public class AsyncConfig {

    @Bean("paymentExecutor")
    public Executor paymentExecutor(PaymentExecutorProperties props) {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(props.corePoolSize());
        exec.setMaxPoolSize(props.maxPoolSize());
        exec.setQueueCapacity(props.queueCapacity());
        exec.setKeepAliveSeconds(props.keepAliveSeconds());
        exec.setThreadNamePrefix(props.threadNamePrefix());
        // 큐 가득 시 호출자가 직접 실행 (Tomcat thread가 polling 막힘 — 단 보호 동작)
        exec.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        exec.initialize();
        return exec;
    }
}
