package com.platform.payments.payment.strategy;

import com.platform.payments.payment.PaymentContext;
import com.platform.payments.payment.PaymentMethod;
import com.platform.payments.payment.outcome.AuthOutcome;
import com.platform.payments.payment.outcome.CaptureOutcome;
import com.platform.payments.payment.outcome.VoidOutcome;
import com.platform.payments.pg.AuthorizeRequest;
import com.platform.payments.pg.AuthorizeResult;
import com.platform.payments.pg.PaymentGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CardPaymentStrategy implements PaymentStrategy {

    private final PaymentGateway pg;

    @Override
    public PaymentMethod method() {
        return PaymentMethod.CARD;
    }

    @Override
    public AuthOutcome authorize(PaymentContext ctx) {
        AuthorizeResult result = pg.authorize(new AuthorizeRequest(
                PaymentStrategy.pgIdempotencyKey(ctx.bookingId(), PaymentMethod.CARD),
                PaymentMethod.CARD.name(),
                ctx.amount()
        ));
        return AuthOutcome.authorized(result.authId());
    }

    @Override
    public CaptureOutcome capture(String authReference, PaymentContext ctx) {
        pg.capture(
                authReference,
                PaymentStrategy.pgIdempotencyKey(ctx.bookingId(), PaymentMethod.CARD)
        );
        return CaptureOutcome.captured();
    }

    @Override
    public VoidOutcome voidAuth(String authReference, PaymentContext ctx) {
        pg.voidAuth(
                authReference,
                PaymentStrategy.pgIdempotencyKey(ctx.bookingId(), PaymentMethod.CARD)
        );
        return VoidOutcome.voided();
    }
}
