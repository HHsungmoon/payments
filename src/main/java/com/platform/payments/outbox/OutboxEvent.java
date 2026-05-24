package com.platform.payments.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "outbox_event",
        indexes = {
                @Index(name = "idx_outbox_status_next", columnList = "status,next_attempt_at")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class OutboxEvent {

    public enum Status {
        PENDING, SENT, FAILED, DEAD_LETTER
    }

    public enum EventType {
        BOOKING_PAID,
        BOOKING_FAILED,
        BOOKING_EXPIRED,
        COMPENSATION_VOID,
        COMPENSATION_POINT_RELEASE,
        COMPENSATION_STOCK_RESTORE,
        POINT_COMMIT_RETRY,
        WAIT_PROMOTED
    }

    public enum AggregateType {
        BOOKING, PAYMENT
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "aggregate_type", nullable = false, length = 32)
    private AggregateType aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private Long aggregateId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 64)
    private EventType eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "json")
    private String payload;                   // JSON 문자열

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private Status status = Status.PENDING;

    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private Integer retryCount = 0;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    // ── 도메인 메서드 ──

    public void markSent(Instant at) {
        this.status = Status.SENT;
        this.sentAt = at;
    }

    public void scheduleRetry(Instant nextAttemptAt) {
        this.retryCount++;
        this.nextAttemptAt = nextAttemptAt;
    }

    public void markDeadLetter() {
        this.status = Status.DEAD_LETTER;
    }
}
