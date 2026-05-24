package com.platform.payments.point;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UpdateTimestamp;

// 잔액 + 낙관락 (version)
@Entity
@Table(name = "customer_point")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class CustomerPoint {

    @Id
    @Column(name = "customer_id")
    private Long customerId;

    @Column(nullable = false)
    private Long balance;

    @Version
    @Column(nullable = false)
    private Long version;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // 차감 (HOLD)
    public void deduct(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("amount must be non-negative: " + amount);
        }
        if (balance < amount) {
            throw new InsufficientPointException(customerId, balance, amount);
        }
        this.balance -= amount;
    }

    // 복원 (RELEASE)
    public void restore(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("amount must be non-negative: " + amount);
        }
        this.balance += amount;
    }
}
