package com.platform.payments.payment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// PaymentStrategy 들을 카테고리 순서로 조립
//   authorize: SUPPLEMENT 먼저 (POINT HOLD ~5ms) → MAIN 나중 (PG AUTHORIZE ~100ms)
//   capture:   MAIN 먼저 (PG CAPTURE 실제 차감) → SUPPLEMENT (POINT COMMIT 이력)
//   compensate: 위 역순
@Component
@Slf4j
public class PaymentOrchestrator {

    private final Map<PaymentMethod, PaymentStrategy> strategies;

    public PaymentOrchestrator(List<PaymentStrategy> strategyList) {
        this.strategies = strategyList.stream()
                .collect(Collectors.toUnmodifiableMap(
                        PaymentStrategy::method,
                        Function.identity()));
    }

    // 부분 실패 시 자동 역순 보상 + 예외 전파
    public List<AuthorizedPayment> authorize(
            List<PaymentRequest> requests, Long bookingId, Long customerId) {

        List<PaymentRequest> ordered = orderSupplementFirst(requests);
        List<AuthorizedPayment> authorized = new ArrayList<>();

        for (PaymentRequest req : ordered) {
            PaymentContext ctx = new PaymentContext(bookingId, customerId, req.amount());
            try {
                AuthOutcome outcome = strategies.get(req.method()).authorize(ctx);
                authorized.add(new AuthorizedPayment(
                        req.method(), outcome.authReference(), req.amount()));
            } catch (RuntimeException e) {
                log.warn("AUTHORIZE_FAILED method={} bookingId={} cause={}",
                        req.method(), bookingId, e.getClass().getSimpleName());
                compensate(authorized, bookingId, customerId);
                throw e;
            }
        }
        return authorized;
    }

    // MAIN 먼저 capture (실제 차감 무거움) → SUPPLEMENT (이력)
    // 실패 시 호출자가 처리 (R7 특수 케이스 등)
    public void capture(
            List<AuthorizedPayment> authorized, Long bookingId, Long customerId) {

        List<AuthorizedPayment> ordered = sortMainFirst(authorized);
        for (AuthorizedPayment ap : ordered) {
            PaymentContext ctx = new PaymentContext(bookingId, customerId, ap.amount());
            strategies.get(ap.method()).capture(ap.authReference(), ctx);
        }
    }

    // 역순 보상 — 실패한 보상은 로그 + 호출자가 outbox 적재 필요
    public void compensate(
            List<AuthorizedPayment> authorized, Long bookingId, Long customerId) {

        for (int i = authorized.size() - 1; i >= 0; i--) {
            AuthorizedPayment ap = authorized.get(i);
            PaymentContext ctx = new PaymentContext(bookingId, customerId, ap.amount());
            try {
                strategies.get(ap.method()).voidAuth(ap.authReference(), ctx);
            } catch (RuntimeException e) {
                log.error("COMPENSATION_FAILED method={} authRef={} bookingId={} cause={}",
                        ap.method(), ap.authReference(), bookingId, e.getClass().getSimpleName(), e);
                // 호출자가 outbox(COMPENSATION_VOID 또는 COMPENSATION_POINT_RELEASE) 적재
            }
        }
    }

    // ── ordering helpers ───────────────────────────────────

    private static List<PaymentRequest> orderSupplementFirst(List<PaymentRequest> requests) {
        return requests.stream()
                .sorted(Comparator.comparingInt(r -> r.method().isSupplement() ? 0 : 1))
                .toList();
    }

    private static List<AuthorizedPayment> sortMainFirst(List<AuthorizedPayment> authorized) {
        return authorized.stream()
                .sorted(Comparator.comparingInt(a -> a.method().isMain() ? 0 : 1))
                .toList();
    }
}
