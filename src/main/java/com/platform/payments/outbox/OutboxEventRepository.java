package com.platform.payments.outbox;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    // SKIP LOCKED 폴링 (MySQL 8+ 지원) — 멀티 노드 워커 안전
    @Query(value = """
            SELECT * FROM outbox_event
            WHERE status = 'PENDING' AND next_attempt_at <= NOW(3)
            ORDER BY id
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> findReady(@Param("batchSize") int batchSize);
}
