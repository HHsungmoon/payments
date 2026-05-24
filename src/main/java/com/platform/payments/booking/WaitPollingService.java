package com.platform.payments.booking;

import com.platform.payments.common.properties.WaitlistProperties;
import com.platform.payments.promotion.PromotionService;
import com.platform.payments.stock.StockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

// GET /booking/wait/{token} 처리
//   try_promote.lua 가 READY → PROCESSING 원자 전환
//   READY 발견 시 비동기 결제 트리거 (PromotionService.processPayment)
@Service
@RequiredArgsConstructor
@Slf4j
public class WaitPollingService {

    private final StockService stockService;
    private final PromotionService promotionService;
    private final StringRedisTemplate redis;
    private final WaitlistProperties waitlistProps;

    public WaitStatusResponse poll(String waitToken) {
        String status = stockService.tryPromote(waitToken);

        return switch (status) {
            case "WAITING" -> buildWaiting(waitToken);
            case "READY" -> {
                // try_promote 가 이미 PROCESSING 으로 전환했음 — 결제 비동기 시작
                promotionService.processPayment(waitToken);
                yield WaitStatusResponse.processing(waitlistProps.pollingIntervalMs());
            }
            case "PROCESSING" -> WaitStatusResponse.processing(waitlistProps.pollingIntervalMs());
            case "PAID" -> buildPaid(waitToken);
            case "FAILED" -> buildFailed(waitToken);
            case "NOT_FOUND" -> throw new WaitTokenNotFoundException(waitToken);
            default -> {
                log.warn("WAIT_POLL_UNEXPECTED status={} waitToken={}", status, waitToken);
                throw new WaitTokenNotFoundException(waitToken);
            }
        };
    }

    private WaitStatusResponse buildWaiting(String waitToken) {
        long productId = readLongField(waitToken, "productId");
        Long rank = stockService.getWaitlistRank(productId, waitToken);
        int position = rank != null ? rank.intValue() + 1 : 0;
        return WaitStatusResponse.waiting(position, waitlistProps.pollingIntervalMs());
    }

    private WaitStatusResponse buildPaid(String waitToken) {
        long bookingId = readLongField(waitToken, "bookingId");
        return WaitStatusResponse.paid(bookingId);
    }

    private WaitStatusResponse buildFailed(String waitToken) {
        Object v = redis.opsForHash().get(StockService.waitTokenKey(waitToken), "reason");
        String reason = v != null ? v.toString() : "UNKNOWN";
        return WaitStatusResponse.failed(reason);
    }

    private long readLongField(String waitToken, String field) {
        Object v = redis.opsForHash().get(StockService.waitTokenKey(waitToken), field);
        if (v == null) {
            throw new WaitTokenNotFoundException(waitToken);
        }
        return Long.parseLong(v.toString());
    }
}
