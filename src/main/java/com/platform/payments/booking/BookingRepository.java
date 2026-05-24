package com.platform.payments.booking;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    // 멱등 응답 재구성용
    Optional<Booking> findByIdempotencyKey(String idempotencyKey);

    // 대기 polling에서 사용
    Optional<Booking> findByWaitToken(String waitToken);

    // 1인1상품 검증 (active_key 대체)
    // statuses = [WAITING, PENDING, PAID]
    boolean existsByCustomerIdAndProductIdAndStatusIn(
            Long customerId, Long productId, Collection<BookingStatus> statuses);

    // 정합성 검증 워커: paid + pending ≤ stock_total
    long countByProductIdAndStatus(Long productId, BookingStatus status);

    // 좀비 청소: PENDING이 createdAt 이전
    List<Booking> findByStatusAndCreatedAtBefore(BookingStatus status, Instant before);

    // 대기열 만료: WAITING이 enqueuedAt 이전 (1h)
    List<Booking> findByStatusAndEnqueuedAtBefore(BookingStatus status, Instant before);
}
