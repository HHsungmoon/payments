package com.platform.payments.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

// Lua 스크립트 빈 4종 (v4 모델 D)
@Configuration
public class RedisConfig {

    @Bean
    public DefaultRedisScript<String> conditionalDecrOrWaitScript() {
        return loadStringScript("lua/conditional_decr_or_wait.lua");
    }

    @Bean
    public DefaultRedisScript<String> restoreStockAndPromoteScript() {
        return loadStringScript("lua/restore_stock_and_promote.lua");
    }

    @Bean
    public DefaultRedisScript<String> tryPromoteScript() {
        return loadStringScript("lua/try_promote.lua");
    }

    @Bean
    public DefaultRedisScript<Long> safeUnlockScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/safe_unlock.lua"));
        script.setResultType(Long.class);
        return script;
    }

    private static DefaultRedisScript<String> loadStringScript(String path) {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(String.class);
        return script;
    }
}
