package com.platform.payments.inbox;

import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

// PG 비동기 콜백 수신 — 실 PG 통합 전 인터페이스 골격 (DECISIONS §10.2)
//   멱등: (source, external_event_id) UNIQUE 충돌 시 200 OK 즉시 반환
//   실제 처리 (booking 상태 동기화 등) 는 향후 InboxDispatcher 가 담당
@RestController
@RequiredArgsConstructor
@Slf4j
public class PgCallbackController {

    private final InboxEventRepository inboxRepo;

    @PostMapping("/pg/callback")
    @Transactional
    public ResponseEntity<Void> callback(
            @RequestHeader(value = "X-PG-Source", defaultValue = "unknown") String source,
            @RequestBody Map<String, Object> payload) {

        String eventId = String.valueOf(payload.getOrDefault("eventId", payload.get("authId")));
        if (eventId == null || "null".equals(eventId)) {
            return ResponseEntity.badRequest().build();
        }

        // 멱등 — 중복 도착이면 즉시 200
        if (inboxRepo.existsBySourceAndExternalEventId(source, eventId)) {
            log.info("INBOX_DUPLICATE source={} eventId={}", source, eventId);
            return ResponseEntity.ok().build();
        }

        inboxRepo.save(InboxEvent.builder()
                .source(source)
                .externalEventId(eventId)
                .payload(serialize(payload))
                .status(InboxEvent.Status.RECEIVED)
                .build());

        log.info("INBOX_RECEIVED source={} eventId={}", source, eventId);
        // 실제 booking 상태 동기화는 향후 별도 워커가 status=RECEIVED 폴링 후 처리
        return ResponseEntity.ok().build();
    }

    // payload Map 을 JSON 문자열로 — 향후 ObjectMapper bean 주입
    private static String serialize(Map<String, Object> payload) {
        return payload.toString();
    }
}
