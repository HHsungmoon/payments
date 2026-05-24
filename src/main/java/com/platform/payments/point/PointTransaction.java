package com.platform.payments.point;

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

// 이력 + 멱등 (reference_key UNIQUE)
@Entity
@Table(name = "point_transaction",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_point_tx_ref", columnNames = "reference_key")
        },
        indexes = {
                @Index(name = "idx_point_tx_customer_created", columnList = "customer_id,created_at")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class PointTransaction {

    public enum Reason {
        BOOKING_HOLD,
        BOOKING_COMMIT,
        BOOKING_RELEASE,
        BOOKING_REFUND,
        ADMIN_ADJUST
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "booking_id")
    private Long bookingId;

    @Column(nullable = false)
    private Long delta;                       // +적립, -차감

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 64)
    private Reason reason;

    @Column(name = "reference_key", nullable = false, length = 128)
    private String referenceKey;              // 예: HOLD:BOOKING:42

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
