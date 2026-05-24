package com.platform.payments.payment;

import com.platform.payments.pg.AuthorizeRequest;
import com.platform.payments.pg.AuthorizeResult;
import com.platform.payments.pg.PaymentGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class YpayPaymentStrategy implements PaymentStrategy {

    private final PaymentGateway pg;

    @Override
    public PaymentMethod method() {
        return PaymentMethod.YPAY;
    }

    @Override
    public AuthOutcome authorize(PaymentContext ctx) {
        AuthorizeResult result = pg.authorize(new AuthorizeRequest(
                PaymentStrategy.pgIdempotencyKey(ctx.bookingId(), PaymentMethod.YPAY),
                PaymentMethod.YPAY.name(),
                ctx.amount()
        ));
        return AuthOutcome.authorized(result.authId());
    }

    @Override
    public CaptureOutcome capture(String authReference, PaymentContext ctx) {
        pg.capture(
                authReference,
                PaymentStrategy.pgIdempotencyKey(ctx.bookingId(), PaymentMethod.YPAY)
        );
        return CaptureOutcome.captured();
    }

    @Override
    public VoidOutcome voidAuth(String authReference, PaymentContext ctx) {
        pg.voidAuth(
                authReference,
                PaymentStrategy.pgIdempotencyKey(ctx.bookingId(), PaymentMethod.YPAY)
        );
        return VoidOutcome.voided();
    }
}
