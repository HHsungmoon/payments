package com.platform.payments.reconciliation;

import com.platform.payments.booking.Booking;
import com.platform.payments.booking.BookingPersistence;
import com.platform.payments.booking.BookingRepository;
import com.platform.payments.booking.BookingStatus;
import com.platform.payments.booking.FailureReason;
import com.platform.payments.common.properties.ReconciliationProperties;
import com.platform.payments.lock.DistributedLockService;
import com.platform.payments.payment.AuthorizedPayment;
import com.platform.payments.payment.Payment;
import com.platform.payments.payment.PaymentOrchestrator;
import com.platform.payments.payment.PaymentRepository;
import com.platform.payments.payment.PaymentStatus;
import com.platform.payments.product.Product;
import com.platform.payments.product.ProductRepository;
import com.platform.payments.promotion.PromotionService;
import com.platform.payments.stock.PromotionResult;
import com.platform.payments.stock.StockService;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 정합성 보정 워커 3종
//   ① 좀비 청소 (1분): payment 좀비 발견 → 보상 + 다음 대기자 승격
//   ② 정합성 검증 (5분): paid + pending ≤ stock_total
//   ③ 대기열 만료 (5분): WAITING 1h 경과 → EXPIRED
@Component
@RequiredArgsConstructor
@Slf4j
public class ReconciliationWorker {

    private final BookingRepository bookingRepo;
    private final PaymentRepository paymentRepo;
    private final ProductRepository productRepo;
    private final BookingPersistence persistence;
    private final PaymentOrchestrator orchestrator;
    private final PromotionService promotionService;
    private final StockService stockService;
    private final DistributedLockService lockService;
    private final StringRedisTemplate redis;
    private final ReconciliationProperties props;

    // ── ① 좀비 청소 ─────────────────────────────────────────
    @Scheduled(fixedDelayString = "${app.reconciliation.zombie-cleanup-fixed-delay-ms}")
    public void cleanupZombies() {
        Optional<String> lock = lockService.tryAcquireReconcileLock("zombie");
        if (lock.isEmpty()) return;
        try {
            Instant threshold = Instant.now().minusSeconds(props.zombieAfterMinutes() * 60L);

            // 좀비 식별: booking_id 중복 제거 위해 Set 사용
            Set<Long> zombieBookingIds = new HashSet<>();

            paymentRepo.findByStatusAndAuthorizedAtBefore(PaymentStatus.AUTHORIZED, threshold)
                    .forEach(p -> zombieBookingIds.add(p.getBookingId()));
            paymentRepo.findByStatusAndRequestedAtBefore(PaymentStatus.REQUESTED, threshold)
                    .forEach(p -> zombieBookingIds.add(p.getBookingId()));

            for (Long bookingId : zombieBookingIds) {
                handleZombie(bookingId);
            }
        } finally {
            lockService.release(DistributedLockService.reconcileLockKey("zombie"), lock.get());
        }
    }

    private void handleZombie(Long bookingId) {
        try {
            Booking booking = bookingRepo.findById(bookingId).orElse(null);
            if (booking == null || booking.getStatus() != BookingStatus.PENDING) return;

            List<Payment> payments = paymentRepo.findByBookingIdOrderById(bookingId);
            Product product = productRepo.findById(booking.getProductId()).orElse(null);
            if (product == null) return;

            // AUTHORIZED payment 보상
            List<AuthorizedPayment> authorized = payments.stream()
                    .filter(p -> p.getStatus() == PaymentStatus.AUTHORIZED && p.getPgAuthId() != null)
                    .map(p -> new AuthorizedPayment(p.getMethod(), p.getPgAuthId(), p.getAmount()))
                    .toList();
            if (!authorized.isEmpty()) {
                orchestrator.compensate(authorized, bookingId, booking.getCustomerId());
            }

            // 재고 복구 + 다음 대기자 승격
            PromotionResult promo = stockService.restoreAndPromote(
                    booking.getProductId(),
                    String.valueOf(bookingId),
                    product.getStockTotal()
            );
            if (promo.isPromoted()) {
                promotionService.activate(promo.waitToken(), booking.getProductId());
            }

            persistence.finalizeFailed(bookingId, FailureReason.TIMEOUT);
            log.info("ZOMBIE_CLEANED bookingId={}", bookingId);
        } catch (Exception e) {
            log.error("ZOMBIE_CLEANUP_FAILED bookingId={} cause={}",
                    bookingId, e.getClass().getSimpleName(), e);
        }
    }

    // ── ② 정합성 검증 ────────────────────────────────────────
    @Scheduled(fixedDelayString = "${app.reconciliation.consistency-verify-fixed-delay-ms}")
    public void verifyConsistency() {
        Optional<String> lock = lockService.tryAcquireReconcileLock("verify");
        if (lock.isEmpty()) return;
        try {
            for (Product p : productRepo.findAll()) {
                long paid = bookingRepo.countByProductIdAndStatus(p.getId(), BookingStatus.PAID);
                long pending = bookingRepo.countByProductIdAndStatus(p.getId(), BookingStatus.PENDING);

                if (paid + pending > p.getStockTotal()) {
                    log.error("CONSISTENCY_VIOLATION productId={} paid={} pending={} total={}",
                            p.getId(), paid, pending, p.getStockTotal());
                } else {
                    log.debug("CONSISTENCY_OK productId={} paid={} pending={} total={}",
                            p.getId(), paid, pending, p.getStockTotal());
                }
            }
        } finally {
            lockService.release(DistributedLockService.reconcileLockKey("verify"), lock.get());
        }
    }

    // ── ③ 대기열 만료 정리 ───────────────────────────────────
    @Scheduled(fixedDelayString = "${app.reconciliation.waitlist-cleanup-fixed-delay-ms}")
    public void cleanupExpiredWaitlist() {
        Optional<String> lock = lockService.tryAcquireReconcileLock("wait_cleanup");
        if (lock.isEmpty()) return;
        try {
            Instant threshold = Instant.now().minusSeconds(props.waitExpiryHours() * 3600L);
            List<Booking> stale = bookingRepo.findByStatusAndEnqueuedAtBefore(
                    BookingStatus.WAITING, threshold);

            for (Booking b : stale) {
                try {
                    if (b.getWaitToken() != null) {
                        redis.delete(StockService.waitTokenKey(b.getWaitToken()));
                        redis.opsForZSet().remove(
                                StockService.waitlistKey(b.getProductId()), b.getWaitToken());
                    }
                    persistence.markExpired(b.getId(), FailureReason.WAIT_TIMEOUT);
                    log.info("WAIT_EXPIRED bookingId={} waitToken={}", b.getId(), b.getWaitToken());
                } catch (Exception e) {
                    log.error("WAIT_CLEANUP_FAILED bookingId={} cause={}",
                            b.getId(), e.getClass().getSimpleName(), e);
                }
            }
        } finally {
            lockService.release(DistributedLockService.reconcileLockKey("wait_cleanup"), lock.get());
        }
    }
}
