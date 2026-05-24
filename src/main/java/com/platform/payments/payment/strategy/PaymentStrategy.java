package com.platform.payments.payment.strategy;

import com.platform.payments.payment.PaymentContext;
import com.platform.payments.payment.PaymentMethod;
import com.platform.payments.payment.outcome.AuthOutcome;
import com.platform.payments.payment.outcome.CaptureOutcome;
import com.platform.payments.payment.outcome.VoidOutcome;

// 결제 수단별 처리 — Auth / Capture / Void 3-call 통일
public interface PaymentStrategy {

    PaymentMethod method();

    // 한도/잔액 잡기 (실제 차감 X)
    // CARD/YPAY: PG AUTHORIZE
    // POINT:     HOLD (balance 즉시 차감 + HOLD 이력)
    AuthOutcome authorize(PaymentContext ctx);

    // 실제 차감 확정
    // CARD/YPAY: PG CAPTURE
    // POINT:     COMMIT (이력만 INSERT, balance 변동 X)
    CaptureOutcome capture(String authReference, PaymentContext ctx);

    // AUTHORIZE 되돌림
    // CARD/YPAY: PG VOID
    // POINT:     RELEASE (balance 복원 + RELEASE 이력)
    VoidOutcome voidAuth(String authReference, PaymentContext ctx);

    // ── deterministic 멱등 키 helper ────────────────────────

    static String pgIdempotencyKey(long bookingId, PaymentMethod method) {
        return "pay-" + bookingId + "-" + method.name();
    }
}
