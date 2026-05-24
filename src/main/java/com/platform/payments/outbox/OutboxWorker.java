package com.platform.payments.outbox;

import com.platform.payments.common.properties.OutboxProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// 1초마다 outbox 폴링 + SKIP LOCKED + 지수 백오프
//   1s → 5s → 30s → 1m → 5m → 30m... 10회 초과 시 DEAD_LETTER
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxWorker {

    private final OutboxEventRepository outboxRepo;
    private final OutboxDispatcher dispatcher;
    private final OutboxProperties props;

    @Scheduled(fixedDelayString = "${app.outbox.poll-fixed-delay-ms}")
    @Transactional
    public void poll() {
        List<OutboxEvent> events = outboxRepo.findReady(props.batchSize());
        if (events.isEmpty()) return;

        Instant now = Instant.now();
        for (OutboxEvent event : events) {
            try {
                dispatcher.dispatch(event);
                event.markSent(now);
            } catch (Exception e) {
                event.scheduleRetry(now.plus(backoff(event.getRetryCount())));
                if (event.getRetryCount() >= props.maxRetries()) {
                    event.markDeadLetter();
                    log.error("OUTBOX_DEAD_LETTER eventId={} type={} cause={}",
                            event.getId(), event.getEventType(), e.getClass().getSimpleName());
                } else {
                    log.warn("OUTBOX_RETRY eventId={} type={} retryCount={} cause={}",
                            event.getId(), event.getEventType(), event.getRetryCount(),
                            e.getClass().getSimpleName());
                }
            }
        }
    }

    private static Duration backoff(int retryCount) {
        return switch (retryCount) {
            case 0 -> Duration.ofSeconds(1);
            case 1 -> Duration.ofSeconds(5);
            case 2 -> Duration.ofSeconds(30);
            case 3 -> Duration.ofMinutes(1);
            case 4 -> Duration.ofMinutes(5);
            default -> Duration.ofMinutes(30);
        };
    }
}
