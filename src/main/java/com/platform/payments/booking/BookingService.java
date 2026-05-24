package com.platform.payments.booking;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.platform.payments.common.ErrorResponse;
import com.platform.payments.common.properties.WaitlistProperties;
import com.platform.payments.idempotency.CachedResponse;
import com.platform.payments.idempotency.IdempotencyService;
import com.platform.payments.lock.DistributedLockService;
import com.platform.payments.outbox.OutboxEvent;
import com.platform.payments.outbox.OutboxEventRepository;
import com.platform.payments.booking.dto.BookingCreateRequest;
import com.platform.payments.booking.dto.BookingOutput;
import com.platform.payments.booking.dto.BookingResponse;
import com.platform.payments.booking.wait.WaitingResponse;
import com.platform.payments.payment.AuthorizedPayment;
import com.platform.payments.payment.Payment;
import com.platform.payments.payment.PaymentMethod;
import com.platform.payments.payment.PaymentOrchestrator;
import com.platform.payments.payment.PaymentRepository;
import com.platform.payments.payment.PaymentRequest;
import com.platform.payments.payment.validation.InvalidPaymentCombinationException;
import com.platform.payments.payment.validation.PaymentCombinationValidator;
import com.platform.payments.product.Product;
import com.platform.payments.product.ProductRepository;
import com.platform.payments.promotion.PromotionService;
import com.platform.payments.stock.PromotionResult;
import com.platform.payments.stock.StockReserveResult;
import com.platform.payments.stock.StockService;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class BookingService {

    private final IdempotencyService idempotencyService;
    private final DistributedLockService lockService;
    private final StockService stockService;
    private final PaymentCombinationValidator validator;
    private final PaymentOrchestrator orchestrator;
    private final BookingPersistence persistence;
    private final BookingRepository bookingRepo;
    private final PaymentRepository paymentRepo;
    private final ProductRepository productRepo;
    private final OutboxEventRepository outboxRepo;
    private final StringRedisTemplate redis;
    private final WaitlistProperties waitlistProps;
    private final PromotionService promotionService;

    // 캐시 body 직렬화 — Spring 응답 직렬화와 매칭 위해 record 선언 순서 유지 (sort X)
    private final JsonMapper bodyMapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .build();

    public BookingOutput book(BookingCreateRequest req, String idemHeader) {
        String idemKey = idempotencyService.resolveOrFallback(idemHeader);
        String requestHash = idempotencyService.hashCanonical(req);

        // 1. idem 캐시 lookup
        Optional<CachedResponse> cached = idempotencyService.lookup(idemKey, requestHash);
        if (cached.isPresent()) {
            return BookingOutput.fromCached(idemKey, cached.get());
        }

        // 2. 분산락 (동일 customer + product 진입 차단)
        String lockKey = DistributedLockService.bookingLockKey(req.customerId(), req.productId());
        Optional<String> lockValue = lockService.tryAcquireBookingLock(req.customerId(), req.productId());
        if (lockValue.isEmpty()) {
            // race-safe: 락 실패 후 캐시 재확인
            cached = idempotencyService.lookup(idemKey, requestHash);
            if (cached.isPresent()) {
                return BookingOutput.fromCached(idemKey, cached.get());
            }
            return BookingOutput.inProgress(idemKey);
        }

        try {
            return doBook(req, idemKey, requestHash);
        } catch (AlreadyReservedException e) {
            // existsBy 사전 체크와 락 사이의 race 안전망 — DB uk_booking_active 가 잡음
            BookingOutput out = BookingOutput.alreadyReserved(idemKey);
            cacheIfApplicable(idemKey, requestHash, out);
            return out;
        } finally {
            lockService.release(lockKey, lockValue.get());
        }
    }

    private BookingOutput doBook(BookingCreateRequest req, String idemKey, String requestHash) {
        // 3. 1인1상품 검증
        boolean activeExists = bookingRepo.existsByCustomerIdAndProductIdAndStatusIn(
                req.customerId(), req.productId(),
                List.of(BookingStatus.WAITING, BookingStatus.PENDING, BookingStatus.PAID));
        if (activeExists) {
            BookingOutput out = BookingOutput.alreadyReserved(idemKey);
            cacheIfApplicable(idemKey, requestHash, out);
            return out;
        }

        // 4. product 존재 + 결제 조합 검증
        Product product = productRepo.findById(req.productId())
                .orElseThrow(() -> new IllegalArgumentException("product not found: " + req.productId()));
        long totalAmount = req.payments().stream().mapToLong(BookingCreateRequest.PaymentItem::amount).sum();
        try {
            validator.validate(toPaymentRequests(req.payments()), totalAmount);
        } catch (InvalidPaymentCombinationException e) {
            return BookingOutput.failed(idemKey, 422, "INVALID_COMBINATION", e.getMessage());
        }

        // 5. Redis Lua — 3-way 분기
        String waitToken = BookingPersistence.newWaitToken();
        StockReserveResult reserve = stockService.tryReserveOrWait(req.productId(), waitToken);

        if (reserve.isFull()) {
            BookingOutput out = BookingOutput.soldOut(idemKey);
            cacheIfApplicable(idemKey, requestHash, out);
            return out;
        }

        if (reserve.isWaitlist()) {
            return handleWaitlist(req, idemKey, requestHash, totalAmount, waitToken, reserve.position());
        }

        // SLOT 분기
        return handleSlot(req, idemKey, requestHash, totalAmount, waitToken, product);
    }

    // ── WAITING 진입 ─────────────────────────────────────────

    private BookingOutput handleWaitlist(BookingCreateRequest req, String idemKey, String requestHash,
                                          long totalAmount, String waitToken, int position) {
        Booking booking = persistence.insertWaitingWithPayments(req, idemKey, requestHash, totalAmount, waitToken);

        // wait:token Hash — 메타 + status (결제 정보는 booking/payment row에)
        Map<String, String> hashFields = new HashMap<>();
        hashFields.put("status", "WAITING");
        hashFields.put("bookingId", String.valueOf(booking.getId()));
        hashFields.put("customerId", String.valueOf(req.customerId()));
        hashFields.put("productId", String.valueOf(req.productId()));
        hashFields.put("enqueuedAt", String.valueOf(booking.getEnqueuedAt().toEpochMilli()));
        String waitKey = StockService.waitTokenKey(waitToken);
        redis.opsForHash().putAll(waitKey, hashFields);
        redis.expire(waitKey, Duration.ofSeconds(waitlistProps.waitTokenTtlSeconds()));

        WaitingResponse body = new WaitingResponse(
                "WAITING",
                waitToken,
                position,
                waitlistProps.pollingIntervalMs(),
                position * waitlistProps.estimatedWaitSecondsPerSlot()
        );
        BookingOutput out = BookingOutput.waiting(idemKey, body);
        cacheIfApplicable(idemKey, requestHash, out);
        log.info("BOOKING_WAITLIST customerId={} productId={} waitToken={} position={}",
                req.customerId(), req.productId(), waitToken, position);
        return out;
    }

    // ── SLOT 진입 (즉시 결제) ────────────────────────────────

    private BookingOutput handleSlot(BookingCreateRequest req, String idemKey, String requestHash,
                                      long totalAmount, String holdHolder, Product product) {
        // tx1
        Booking booking = persistence.insertPending(req, idemKey, requestHash, totalAmount);

        // authorize (orchestrator 자동 보상 포함)
        List<AuthorizedPayment> authorized;
        try {
            authorized = orchestrator.authorize(
                    toPaymentRequests(req.payments()),
                    booking.getId(), req.customerId());
        } catch (RuntimeException e) {
            return handlePaymentFailure(booking, e, holdHolder, product, idemKey, requestHash, List.of());
        }

        // tx2
        persistence.markPaymentsAuthorized(booking.getId(), authorized);

        // capture
        try {
            orchestrator.capture(authorized, booking.getId(), req.customerId());
        } catch (RuntimeException e) {
            // POINT 가 섞여있으면 COMMIT 실패 가능성 — outbox 로 재시도 가시성
            if (authorized.stream().anyMatch(ap -> ap.method() == PaymentMethod.POINT)) {
                outboxRepo.save(OutboxEvent.builder()
                        .aggregateType(OutboxEvent.AggregateType.PAYMENT)
                        .aggregateId(booking.getId())
                        .eventType(OutboxEvent.EventType.POINT_COMMIT_RETRY)
                        .payload("{\"bookingId\":" + booking.getId()
                                + ",\"cause\":\"" + e.getClass().getSimpleName() + "\"}")
                        .nextAttemptAt(Instant.now())
                        .build());
            }
            // 보상
            orchestrator.compensate(authorized, booking.getId(), req.customerId());
            return handlePaymentFailure(booking, e, holdHolder, product, idemKey, requestHash, authorized);
        }

        // tx3
        persistence.finalizePaid(booking.getId());

        // hold 키 DEL
        redis.delete(StockService.holdKey(req.productId(), holdHolder));

        // outbox(BOOKING_PAID)
        outboxRepo.save(OutboxEvent.builder()
                .aggregateType(OutboxEvent.AggregateType.BOOKING)
                .aggregateId(booking.getId())
                .eventType(OutboxEvent.EventType.BOOKING_PAID)
                .payload("{\"bookingId\":" + booking.getId() + "}")
                .nextAttemptAt(Instant.now())
                .build());

        // 응답 빌드
        List<Payment> payments = paymentRepo.findByBookingIdOrderById(booking.getId());
        BookingResponse body = new BookingResponse(
                "PAID",
                booking.getId(),
                totalAmount,
                Instant.now(),
                payments.stream()
                        .map(p -> new BookingResponse.PaymentDetail(
                                p.getMethod(), p.getAmount(), p.getStatus(), p.getPgAuthId()))
                        .toList()
        );
        BookingOutput out = BookingOutput.paid(idemKey, body);
        cacheIfApplicable(idemKey, requestHash, out);
        log.info("BOOKING_PAID bookingId={} customerId={} productId={}",
                booking.getId(), req.customerId(), req.productId());
        return out;
    }

    // ── 결제 실패 처리 — 재고 복구 + 대기자 승격 ─────────────

    private BookingOutput handlePaymentFailure(Booking booking, RuntimeException e,
                                                String holdHolder, Product product,
                                                String idemKey, String requestHash,
                                                List<AuthorizedPayment> authorized) {
        FailureReason reason = FailureReasonMapper.fromException(e);
        int httpStatus = FailureReasonMapper.toHttpStatus(reason);
        log.warn("BOOKING_FAILED bookingId={} reason={} cause={}",
                booking.getId(), reason, e.getClass().getSimpleName());

        // booking FAILED + payment 정리
        persistence.finalizeFailed(booking.getId(), reason);

        // 재고 복구 + 다음 대기자 승격
        try {
            PromotionResult promo = stockService.restoreAndPromote(
                    booking.getProductId(), holdHolder, product.getStockTotal());
            if (promo.isPromoted()) {
                // 다음 대기자를 READY 상태로 (slot/hold 키 SET + booking PENDING 전환)
                // 결제는 사용자 polling이 try_promote 통과 시 비동기 진행
                promotionService.activate(promo.waitToken(), booking.getProductId());
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

        // outbox(BOOKING_FAILED)
        outboxRepo.save(OutboxEvent.builder()
                .aggregateType(OutboxEvent.AggregateType.BOOKING)
                .aggregateId(booking.getId())
                .eventType(OutboxEvent.EventType.BOOKING_FAILED)
                .payload("{\"bookingId\":" + booking.getId() + ",\"reason\":\"" + reason + "\"}")
                .nextAttemptAt(Instant.now())
                .build());

        BookingOutput out = BookingOutput.failed(idemKey, httpStatus, reason.name(), e.getMessage());
        // 422 / 503은 cache 안 함 (M5 정책)
        return out;
    }

    // ── helpers ──────────────────────────────────────────────

    private static List<PaymentRequest> toPaymentRequests(List<BookingCreateRequest.PaymentItem> items) {
        return items.stream()
                .map(i -> new PaymentRequest(i.method(), i.amount()))
                .toList();
    }

    private void cacheIfApplicable(String idemKey, String requestHash, BookingOutput out) {
        // 캐시 정책 (3-idempotency.md §5.2):
        //   200 / 409 SOLD_OUT / 409 ALREADY_RESERVED → 캐시
        //   409 IN_PROGRESS / 422 / 5xx → 캐시 X
        int s = out.httpStatus();
        if (s != 200 && s != 409) return;
        if (s == 409 && out.body() instanceof ErrorResponse er && "IN_PROGRESS".equals(er.reason())) return;
        try {
            String json = bodyMapper.writeValueAsString(out.body());
            idempotencyService.store(idemKey, requestHash, s, json);
        } catch (JsonProcessingException e) {
            log.error("IDEM_CACHE_STORE_FAILED key={}", idemKey, e);
        }
    }
}
