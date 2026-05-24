package com.platform.payments.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// 이벤트 타입별 처리
//   BOOKING_PAID / BOOKING_FAILED / BOOKING_EXPIRED / WAIT_PROMOTED → 알림 (mock: 로그)
//   COMPENSATION_*  → 보상 호출 재시도 (현재 단순 로그, 향후 PG VOID/POINT RELEASE/restore_stock)
//   POINT_COMMIT_RETRY → POINT COMMIT 재시도 (R7 케이스, 향후)
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxDispatcher {

    public void dispatch(OutboxEvent event) {
        switch (event.getEventType()) {
            case BOOKING_PAID,
                 BOOKING_FAILED,
                 BOOKING_EXPIRED,
                 WAIT_PROMOTED -> log.info("OUTBOX_EVENT type={} aggregateId={} payload={}",
                    event.getEventType(), event.getAggregateId(), event.getPayload());

            case COMPENSATION_VOID,
                 COMPENSATION_POINT_RELEASE,
                 COMPENSATION_STOCK_RESTORE,
                 POINT_COMMIT_RETRY -> log.warn("OUTBOX_COMPENSATION_PENDING type={} aggregateId={} payload={}",
                    event.getEventType(), event.getAggregateId(), event.getPayload());
        }
    }
}
