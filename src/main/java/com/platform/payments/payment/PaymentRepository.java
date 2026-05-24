package com.platform.payments.payment;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    // 내부 멱등 (PG retry 시 같은 키 재사용)
    Optional<Payment> findByPgIdempotencyKey(String pgIdempotencyKey);

    // Orchestrator에서 booking별 payment 목록 조회
    List<Payment> findByBookingIdOrderById(Long bookingId);

    // 좀비 청소: REQUESTED가 requestedAt 이전 (시나리오 ①·②)
    List<Payment> findByStatusAndRequestedAtBefore(PaymentStatus status, Instant before);

    // 좀비 청소: AUTHORIZED가 authorizedAt 이전 (시나리오 ③·④·⑤)
    List<Payment> findByStatusAndAuthorizedAtBefore(PaymentStatus status, Instant before);
}
