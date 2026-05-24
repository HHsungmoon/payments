package com.platform.payments.promotion;

import com.platform.payments.booking.Booking;
import com.platform.payments.booking.BookingPersistence;
import com.platform.payments.booking.BookingRepository;
import com.platform.payments.booking.FailureReason;
import com.platform.payments.booking.FailureReasonMapper;
import com.platform.payments.common.properties.WaitlistProperties;
import com.platform.payments.outbox.OutboxEvent;
import com.platform.payments.outbox.OutboxEventRepository;
import com.platform.payments.payment.AuthorizedPayment;
import com.platform.payments.payment.Payment;
import com.platform.payments.payment.PaymentMethod;
import com.platform.payments.payment.PaymentOrchestrator;
import com.platform.payments.payment.PaymentRepository;
import com.platform.payments.payment.PaymentRequest;
import com.platform.payments.product.Product;
import com.platform.payments.product.ProductRepository;
import com.platform.payments.stock.PromotionResult;
import com.platform.payments.stock.StockService;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 대기자 승격 — 동기 activate (slot 발급) + 비동기 processPayment (결제)
@Service
@RequiredArgsConstructor
@Slf4j
public class PromotionService {

    private final BookingRepository bookingRepo;
    private final PaymentRepository paymentRepo;
    private final ProductRepository productRepo;
    private final BookingPersistence persistence;
    private final PaymentOrchestrator orchestrator;
    private final StockService stockService;
    private final StringRedisTemplate redis;
    private final OutboxEventRepository outboxRepo;
    private final WaitlistProperties waitlistProps;

    // 동기 — 승격 직후 slot/hold 키 SET + booking PENDING 전환 + wait:token READY
    @Transactional
    public void activate(String waitToken, long productId) {
        Booking booking = bookingRepo.findByWaitToken(waitToken)
                .orElseThrow(() -> new IllegalStateException("booking not found for wait token: " + waitToken));

        String slotToken = BookingPersistence.newSlotToken();
        Instant now = Instant.now();
        booking.markPromoted(slotToken, now);

        // slot 키 (3분 TTL)
        redis.opsForValue().set(
                StockService.slotKey(slotToken),
                waitToken,
                Duration.ofSeconds(waitlistProps.slotTtlSeconds())
        );
        // hold 키 (동일 TTL) — booking_id 기반
        redis.opsForValue().set(
                StockService.holdKey(productId, String.valueOf(booking.getId())),
                String.valueOf(booking.getId()),
                Duration.ofSeconds(waitlistProps.holdTtlSeconds())
        );

        // wait:token Hash 상태 = READY + slot_token
        String waitKey = StockService.waitTokenKey(waitToken);
        Map<String, String> updates = new HashMap<>();
        updates.put("status", "READY");
        updates.put("slotToken", slotToken);
        updates.put("expiresAt", String.valueOf(now.plusSeconds(waitlistProps.slotTtlSeconds()).toEpochMilli()));
        redis.opsForHash().putAll(waitKey, updates);
        redis.expire(waitKey, Duration.ofSeconds(waitlistProps.waitTokenTtlSeconds()));

        outboxRepo.save(OutboxEvent.builder()
                .aggregateType(OutboxEvent.AggregateType.BOOKING)
                .aggregateId(booking.getId())
                .eventType(OutboxEvent.EventType.WAIT_PROMOTED)
                .payload("{\"bookingId\":" + booking.getId()
                        + ",\"waitToken\":\"" + waitToken
                        + "\",\"slotToken\":\"" + slotToken + "\"}")
                .nextAttemptAt(Instant.now())
                .build());

        log.info("WAIT_PROMOTED waitToken={} bookingId={} slotToken={}",
                waitToken, booking.getId(), slotToken);
    }

    // 비동기 — WaitPollingService가 READY 발견 시 호출 (try_promote가 PROCESSING으로 전환됨)
    // 외부 호출 (PG, Redis Lua) 포함 — tx 안 묶음
    @Async("paymentExecutor")
    public void processPayment(String waitToken) {
        Booking booking = bookingRepo.findByWaitToken(waitToken).orElse(null);
        if (booking == null) {
            log.error("ASYNC_PAYMENT_BOOKING_NOT_FOUND waitToken={}", waitToken);
            return;
        }
        Long bookingId = booking.getId();
        Long customerId = booking.getCustomerId();
        Long productId = booking.getProductId();

        List<Payment> payments = paymentRepo.findByBookingIdOrderById(bookingId);
        List<PaymentRequest> requests = payments.stream()
                .map(p -> new PaymentRequest(p.getMethod(), p.getAmount()))
                .toList();

        // authorize
        List<AuthorizedPayment> authorized;
        try {
            authorized = orchestrator.authorize(requests, bookingId, customerId);
        } catch (RuntimeException e) {
            handleAsyncFailure(booking, e, waitToken);
            return;
        }
        persistence.markPaymentsAuthorized(bookingId, authorized);

        // capture
        try {
            orchestrator.capture(authorized, bookingId, customerId);
        } catch (RuntimeException e) {
            // POINT 가 섞여있으면 COMMIT 실패 가능성 — outbox 로 재시도 가시성
            if (authorized.stream().anyMatch(ap -> ap.method() == PaymentMethod.POINT)) {
                outboxRepo.save(OutboxEvent.builder()
                        .aggregateType(OutboxEvent.AggregateType.PAYMENT)
                        .aggregateId(bookingId)
                        .eventType(OutboxEvent.EventType.POINT_COMMIT_RETRY)
                        .payload("{\"bookingId\":" + bookingId
                                + ",\"cause\":\"" + e.getClass().getSimpleName() + "\"}")
                        .nextAttemptAt(Instant.now())
                        .build());
            }
            orchestrator.compensate(authorized, bookingId, customerId);
            handleAsyncFailure(booking, e, waitToken);
            return;
        }
        persistence.finalizePaid(bookingId);

        // hold 키 DEL
        redis.delete(StockService.holdKey(productId, String.valueOf(bookingId)));
        // slot 키 DEL (있다면)
        String slotToken = booking.getSlotToken();
        if (slotToken != null) {
            redis.delete(StockService.slotKey(slotToken));
        }

        // wait:token Hash 상태 = PAID
        String waitKey = StockService.waitTokenKey(waitToken);
        Map<String, String> updates = new HashMap<>();
        updates.put("status", "PAID");
        updates.put("bookingId", String.valueOf(bookingId));
        redis.opsForHash().putAll(waitKey, updates);

        outboxRepo.save(OutboxEvent.builder()
                .aggregateType(OutboxEvent.AggregateType.BOOKING)
                .aggregateId(bookingId)
                .eventType(OutboxEvent.EventType.BOOKING_PAID)
                .payload("{\"bookingId\":" + bookingId + "}")
                .nextAttemptAt(Instant.now())
                .build());

        log.info("ASYNC_PAYMENT_COMPLETE waitToken={} bookingId={} status=PAID", waitToken, bookingId);
    }

    private void handleAsyncFailure(Booking booking, RuntimeException e, String waitToken) {
        FailureReason reason = FailureReasonMapper.fromException(e);
        log.warn("ASYNC_PAYMENT_FAILED waitToken={} bookingId={} reason={}",
                waitToken, booking.getId(), reason);

        persistence.finalizeFailed(booking.getId(), reason);

        // 재고 복구 + 다음 대기자 승격 (chain — 단 비동기 결제는 새 polling을 기다림)
        Product product = productRepo.findById(booking.getProductId()).orElse(null);
        if (product != null) {
            try {
                PromotionResult promo = stockService.restoreAndPromote(
                        booking.getProductId(),
                        String.valueOf(booking.getId()),
                        product.getStockTotal()
                );
                if (promo.isPromoted()) {
                    activate(promo.waitToken(), booking.getProductId());
                }
            } catch (RuntimeException re) {
                log.error("STOCK_RESTORE_FAILED bookingId={} cause={}",
                        booking.getId(), re.getClass().getSimpleName(), re);
                outboxRepo.save(OutboxEvent.builder()
                        .aggregateType(OutboxEvent.AggregateType.BOOKING)
                        .aggregateId(booking.getId())
                        .eventType(OutboxEvent.EventType.COMPENSATION_STOCK_RESTORE)
                        .payload("{\"bookingId\":" + booking.getId()
                                + ",\"productId\":" + booking.getProductId()
                                + ",\"cause\":\"" + re.getClass().getSimpleName() + "\"}")
                        .nextAttemptAt(Instant.now())
                        .build());
            }
        }

        // wait:token Hash 상태 = FAILED
        String waitKey = StockService.waitTokenKey(waitToken);
        Map<String, String> updates = new HashMap<>();
        updates.put("status", "FAILED");
        updates.put("reason", reason.name());
        redis.opsForHash().putAll(waitKey, updates);

        outboxRepo.save(OutboxEvent.builder()
                .aggregateType(OutboxEvent.AggregateType.BOOKING)
                .aggregateId(booking.getId())
                .eventType(OutboxEvent.EventType.BOOKING_FAILED)
                .payload("{\"bookingId\":" + booking.getId() + ",\"reason\":\"" + reason + "\"}")
                .nextAttemptAt(Instant.now())
                .build());
    }
}
