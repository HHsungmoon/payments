package com.platform.payments.lock;

import com.platform.payments.common.properties.LockProperties;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class DistributedLockService {

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> safeUnlockScript;
    private final LockProperties lockProps;

    // ── 키 빌더 ─────────────────────────────────────────────

    public static String bookingLockKey(long customerId, long productId) {
        return "lock:booking:customer:" + customerId + ":prod:" + productId;
    }

    public static String reconcileLockKey(String name) {
        return "lock:reconcile:" + name;
    }

    // ── 락 lifecycle ────────────────────────────────────────

    // 락 획득 시도. 성공 시 lock value (UUID) 반환. 실패 시 Optional.empty.
    public Optional<String> tryAcquire(String key, Duration ttl) {
        String value = UUID.randomUUID().toString();
        Boolean acquired = redis.opsForValue().setIfAbsent(key, value, ttl);
        return Boolean.TRUE.equals(acquired) ? Optional.of(value) : Optional.empty();
    }

    // booking 락 (5초 TTL)
    public Optional<String> tryAcquireBookingLock(long customerId, long productId) {
        return tryAcquire(
                bookingLockKey(customerId, productId),
                Duration.ofSeconds(lockProps.bookingTtlSeconds())
        );
    }

    // reconcile 잡 락 (60초)
    public Optional<String> tryAcquireReconcileLock(String name) {
        return tryAcquire(
                reconcileLockKey(name),
                Duration.ofSeconds(lockProps.reconcileTtlSeconds())
        );
    }

    // safe_unlock: value 검증 후 DEL. 다른 owner의 락은 풀지 않음.
    public boolean release(String key, String value) {
        Long result = redis.execute(safeUnlockScript, List.of(key), value);
        boolean released = result != null && result == 1L;
        if (!released) {
            log.warn("LOCK_RELEASE_MISS key={} (TTL 만료 또는 다른 owner)", key);
        }
        return released;
    }
}
