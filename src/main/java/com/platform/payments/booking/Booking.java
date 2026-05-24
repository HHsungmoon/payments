package com.platform.payments.booking;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "booking",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_booking_idem", columnNames = "idempotency_key"),
                @UniqueConstraint(name = "uk_booking_wait_token", columnNames = "wait_token")
        },
        indexes = {
                @Index(name = "idx_booking_status_created", columnList = "status,created_at"),
                @Index(name = "idx_booking_customer_product", columnList = "customer_id,product_id")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BookingStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "failed_reason", length = 64)
    private FailureReason failedReason;

    @Column(name = "total_amount", nullable = false)
    private Long totalAmount;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;               // SHA-256 hex

    @Column(name = "wait_token", length = 64)
    private String waitToken;                 // null if 즉시 SLOT

    @Column(name = "slot_token", length = 64)
    private String slotToken;                 // null until READY

    @Column(name = "enqueued_at")
    private Instant enqueuedAt;

    @Column(name = "promoted_at")
    private Instant promotedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "failed_at")
    private Instant failedAt;

    @Column(name = "expired_at")
    private Instant expiredAt;

    // 활성 상태(WAITING/PENDING/PAID)일 때 (customer_id + product_id) 조합. 비활성 시 NULL.
    // generated column + UNIQUE 인덱스로 1인 1자리 정책 DB 차원 보장.
    // 실제 컬럼은 SchemaPatcher 가 첫 부팅 시 ALTER TABLE 로 추가 (Hibernate 미지원).
    @Column(name = "active_key", length = 64, insertable = false, updatable = false)
    private String activeKey;

    // ── 도메인 메서드 ──

    public void markPromoted(String slotToken, Instant at) {
        this.status = BookingStatus.PENDING;
        this.slotToken = slotToken;
        this.promotedAt = at;
    }

    public void markPaid(Instant at) {
        this.status = BookingStatus.PAID;
        this.paidAt = at;
    }

    public void markFailed(FailureReason reason, Instant at) {
        this.status = BookingStatus.FAILED;
        this.failedReason = reason;
        this.failedAt = at;
    }

    public void markExpired(FailureReason reason, Instant at) {
        this.status = BookingStatus.EXPIRED;
        this.failedReason = reason;
        this.expiredAt = at;
    }

    // 활성 상태 (WAITING/PENDING/PAID) — 1인1상품 검증 시 사용
    public boolean isActive() {
        return status == BookingStatus.WAITING
                || status == BookingStatus.PENDING
                || status == BookingStatus.PAID;
    }
}
