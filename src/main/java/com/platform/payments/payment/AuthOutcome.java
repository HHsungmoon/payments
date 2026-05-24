package com.platform.payments.payment;

// Strategy.authorize() 결과
public record AuthOutcome(
        String authReference,         // PG: pg_auth_id, POINT: HOLD reference_key
        PaymentStatus status          // AUTHORIZED
) {
    public static AuthOutcome authorized(String ref) {
        return new AuthOutcome(ref, PaymentStatus.AUTHORIZED);
    }
}
