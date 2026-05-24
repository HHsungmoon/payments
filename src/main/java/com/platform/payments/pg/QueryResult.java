package com.platform.payments.pg;

// 시나리오 ⑥ 복구용 — booking PENDING 인데 실제 PG 상태 조회
public record QueryResult(
        String status,                // AUTHORIZED / CAPTURED / VOIDED / NOT_FOUND
        String authId
) {
}
