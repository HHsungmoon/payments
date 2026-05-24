package com.platform.payments.pg;

// PG 어댑터 — Auth/Capture/Void/Query 4-call 패턴
public interface PaymentGateway {

    AuthorizeResult authorize(AuthorizeRequest req);

    CaptureResult capture(String authId, String idempotencyKey);

    VoidResult voidAuth(String authId, String idempotencyKey);

    QueryResult query(String idempotencyKey);
}
