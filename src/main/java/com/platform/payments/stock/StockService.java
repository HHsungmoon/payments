package com.platform.payments.stock;

import com.platform.payments.common.properties.WaitlistProperties;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class StockService {

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<String> conditionalDecrOrWaitScript;
    private final DefaultRedisScript<String> restoreStockAndPromoteScript;
    private final DefaultRedisScript<String> tryPromoteScript;
    private final WaitlistProperties waitlistProps;

    // ── 키 빌더 ─────────────────────────────────────────────

    public static String stockKey(long productId) {
        return "stock:product:" + productId;
    }

    public static String waitlistKey(long productId) {
        return "waitlist:product:" + productId;
    }

    public static String holdKey(long productId, String waitOrBookingId) {
        return "hold:product:" + productId + ":booking:" + waitOrBookingId;
    }

    public static String waitTokenKey(String waitToken) {
        return "wait:token:" + waitToken;
    }

    public static String slotKey(String slotToken) {
        return "slot:" + slotToken;
    }

    // ── Lua wrapper ──────────────────────────────────────────

    // 3-way 분기: SLOT / WAITLIST / FULL
    public StockReserveResult tryReserveOrWait(long productId, String waitToken) {
        List<String> keys = List.of(
                stockKey(productId),
                waitlistKey(productId),
                holdKey(productId, waitToken)
        );
        String raw = redis.execute(
                conditionalDecrOrWaitScript,
                keys,
                waitToken,
                String.valueOf(waitlistProps.holdTtlSeconds()),
                String.valueOf(waitlistProps.maxSize()),
                String.valueOf(Instant.now().toEpochMilli())
        );
        return parseReserveResult(raw);
    }

    // 보상 + 다음 승격 트리거
    public PromotionResult restoreAndPromote(long productId, String holdHolder, long stockTotal) {
        List<String> keys = List.of(
                stockKey(productId),
                holdKey(productId, holdHolder),
                waitlistKey(productId)
        );
        String raw = redis.execute(
                restoreStockAndPromoteScript,
                keys,
                String.valueOf(stockTotal)
        );
        return parsePromotionResult(raw);
    }

    // polling 시 READY → PROCESSING 원자 전환
    // 반환: 현재 상태 raw string (WAITING/READY/PROCESSING/PAID/FAILED/NOT_FOUND)
    public String tryPromote(String waitToken) {
        List<String> keys = List.of(waitTokenKey(waitToken));
        return redis.execute(
                tryPromoteScript,
                keys,
                "PROCESSING"
        );
    }

    // ── 단순 조회 ────────────────────────────────────────────

    public Integer getStock(long productId) {
        String value = redis.opsForValue().get(stockKey(productId));
        return value == null ? null : Integer.valueOf(value);
    }

    public long getWaitlistSize(long productId) {
        Long size = redis.opsForZSet().zCard(waitlistKey(productId));
        return size == null ? 0L : size;
    }

    public Long getWaitlistRank(long productId, String waitToken) {
        return redis.opsForZSet().rank(waitlistKey(productId), waitToken);
    }

    public boolean holdExists(long productId, String holdHolder) {
        Boolean exists = redis.hasKey(holdKey(productId, holdHolder));
        return Boolean.TRUE.equals(exists);
    }

    public void setStock(long productId, int value) {
        redis.opsForValue().set(stockKey(productId), String.valueOf(value));
    }

    // ── 결과 파싱 ────────────────────────────────────────────

    private static StockReserveResult parseReserveResult(String raw) {
        if (raw == null) {
            throw new IllegalStateException("Lua returned null");
        }
        if ("FULL".equals(raw)) {
            return StockReserveResult.full();
        }
        if (raw.startsWith("SLOT:")) {
            int remaining = Integer.parseInt(raw.substring(5));
            return StockReserveResult.slot(remaining);
        }
        if (raw.startsWith("WAITLIST:")) {
            int position = Integer.parseInt(raw.substring(9));
            return StockReserveResult.waitlist(position);
        }
        throw new IllegalStateException("unexpected Lua return: " + raw);
    }

    private static PromotionResult parsePromotionResult(String raw) {
        if (raw == null) {
            throw new IllegalStateException("Lua returned null");
        }
        if ("RESTORED".equals(raw)) {
            return PromotionResult.restored();
        }
        if ("OVERFLOW".equals(raw)) {
            return PromotionResult.overflow();
        }
        if (raw.startsWith("PROMOTED:")) {
            return PromotionResult.promoted(raw.substring(9));
        }
        throw new IllegalStateException("unexpected Lua return: " + raw);
    }
}
