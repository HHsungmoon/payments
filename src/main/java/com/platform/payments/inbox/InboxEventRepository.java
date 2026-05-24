package com.platform.payments.inbox;

import org.springframework.data.jpa.repository.JpaRepository;

public interface InboxEventRepository extends JpaRepository<InboxEvent, Long> {

    // PG 콜백 중복 도착 검증
    boolean existsBySourceAndExternalEventId(String source, String externalEventId);
}
