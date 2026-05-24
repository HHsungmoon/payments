package com.platform.payments.payment;

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

@Entity
@Table(name = "payment",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_payment_pg_idem", columnNames = "pg_idempotency_key"),
                @UniqueConstraint(name = "uk_payment_booking_method", columnNames = {"booking_id", "method"})
        },
        indexes = {
                @Index(name = "idx_payment_status_authorized", columnList = "status,authorized_at")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "booking_id", nullable = false)
    private Long bookingId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PaymentMethod method;

    @Column(nullable = false)
    private Long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PaymentStatus status;

    @Column(name = "pg_auth_id", length = 128)
    private String pgAuthId;

    @Column(name = "pg_idempotency_key", nullable = false, length = 128)
    private String pgIdempotencyKey;          // "pay-{bookingId}-{method}"

    @CreationTimestamp
    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "authorized_at")
    private Instant authorizedAt;

    @Column(name = "captured_at")
    private Instant capturedAt;

    @Column(name = "failed_reason", length = 255)
    private String failedReason;

    // ── 도메인 메서드 ──

    public void markAuthorized(String pgAuthId, Instant at) {
        this.status = PaymentStatus.AUTHORIZED;
        this.pgAuthId = pgAuthId;
        this.authorizedAt = at;
    }

    public void markCaptured(Instant at) {
        this.status = PaymentStatus.CAPTURED;
        this.capturedAt = at;
    }

    public void markVoided() {
        this.status = PaymentStatus.VOIDED;
    }

    public void markFailed(String reason) {
        this.status = PaymentStatus.FAILED;
        this.failedReason = reason;
    }
}
