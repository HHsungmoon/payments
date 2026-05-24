package com.platform.payments.payment.strategy;

import com.platform.payments.payment.PaymentContext;
import com.platform.payments.payment.PaymentMethod;
import com.platform.payments.payment.outcome.AuthOutcome;
import com.platform.payments.payment.outcome.CaptureOutcome;
import com.platform.payments.payment.outcome.VoidOutcome;
import com.platform.payments.point.CustomerPoint;
import com.platform.payments.point.CustomerPointRepository;
import com.platform.payments.point.PointTransaction;
import com.platform.payments.point.PointTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class PointPaymentStrategy implements PaymentStrategy {

    private final CustomerPointRepository customerPointRepo;
    private final PointTransactionRepository pointTxRepo;

    @Override
    public PaymentMethod method() {
        return PaymentMethod.POINT;
    }

    // HOLD: balance 즉시 차감 + reference_key="HOLD:BOOKING:{bid}"
    @Override
    @Transactional
    public AuthOutcome authorize(PaymentContext ctx) {
        String refKey = holdKey(ctx.bookingId());

        // 멱등: 이미 HOLD 됐으면 그대로 반환
        if (pointTxRepo.existsByReferenceKey(refKey)) {
            return AuthOutcome.authorized(refKey);
        }

        CustomerPoint cp = customerPointRepo.findById(ctx.customerId())
                .orElseThrow(() -> new IllegalStateException(
                        "customer point not found: " + ctx.customerId()));
        cp.deduct(ctx.amount());   // InsufficientPointException 가능

        pointTxRepo.save(PointTransaction.builder()
                .customerId(ctx.customerId())
                .bookingId(ctx.bookingId())
                .delta(-ctx.amount())
                .reason(PointTransaction.Reason.BOOKING_HOLD)
                .referenceKey(refKey)
                .build());

        return AuthOutcome.authorized(refKey);
    }

    // COMMIT: 이력만 INSERT (balance 변동 X)
    @Override
    @Transactional
    public CaptureOutcome capture(String authReference, PaymentContext ctx) {
        String refKey = commitKey(ctx.bookingId());

        if (pointTxRepo.existsByReferenceKey(refKey)) {
            return CaptureOutcome.captured();
        }

        pointTxRepo.save(PointTransaction.builder()
                .customerId(ctx.customerId())
                .bookingId(ctx.bookingId())
                .delta(0L)
                .reason(PointTransaction.Reason.BOOKING_COMMIT)
                .referenceKey(refKey)
                .build());

        return CaptureOutcome.captured();
    }

    // RELEASE: balance 복원 + RELEASE 이력
    @Override
    @Transactional
    public VoidOutcome voidAuth(String authReference, PaymentContext ctx) {
        String refKey = releaseKey(ctx.bookingId());

        if (pointTxRepo.existsByReferenceKey(refKey)) {
            return VoidOutcome.voided();
        }

        CustomerPoint cp = customerPointRepo.findById(ctx.customerId())
                .orElseThrow(() -> new IllegalStateException(
                        "customer point not found: " + ctx.customerId()));
        cp.restore(ctx.amount());

        pointTxRepo.save(PointTransaction.builder()
                .customerId(ctx.customerId())
                .bookingId(ctx.bookingId())
                .delta(ctx.amount())
                .reason(PointTransaction.Reason.BOOKING_RELEASE)
                .referenceKey(refKey)
                .build());

        return VoidOutcome.voided();
    }

    // ── reference key helpers ──────────────────────────────

    private static String holdKey(long bookingId)    { return "HOLD:BOOKING:" + bookingId; }
    private static String commitKey(long bookingId)  { return "COMMIT:BOOKING:" + bookingId; }
    private static String releaseKey(long bookingId) { return "RELEASE:BOOKING:" + bookingId; }
}
