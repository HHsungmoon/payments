package com.platform.payments.point;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PointTransactionRepository extends JpaRepository<PointTransaction, Long> {

    // 멱등 사전 체크
    boolean existsByReferenceKey(String referenceKey);
}
