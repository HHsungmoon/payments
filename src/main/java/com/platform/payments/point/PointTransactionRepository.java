package com.platform.payments.point;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PointTransactionRepository extends JpaRepository<PointTransaction, Long> {

    // 멱등 사전 체크
    boolean existsByReferenceKey(String referenceKey);

    // 정합성 검증 배치: Σ delta == customer_point.balance ?
    @Query("SELECT COALESCE(SUM(p.delta), 0) FROM PointTransaction p WHERE p.customerId = :customerId")
    long sumDeltaByCustomerId(@Param("customerId") Long customerId);
}
