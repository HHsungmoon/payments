# 05. 장애 대응 + 트랜잭션 경계 (v4)

> **단계 목적**: Redis Fail-Fast / 결제 실패 대응 / 시스템 장애. 대기열 시스템 도입에 따른 새 시나리오 통합.
>
> **v4 변경 요지**: 노드 사망 시나리오 6개 → 11개로 확장. 대기열 관련 좀비 청소 워커 확장 (slot 만료 + wait:token 만료). 비동기 결제 진행 중 사망 시나리오 신규.

---

## 1. 핵심 결정 요약

| ID | 결정 | 근거 |
|---:|---|---|
| **R1** | Redis 다운 시 Fail-Fast | ⏰ 시간 이슈 |
| **R2** | Outbox 워커 = `@Scheduled` + `SKIP LOCKED` | 멀티 노드 안전 |
| **R3** | 지수 백오프 (1s→5s→30s→1m→5m), 10회 후 DEAD_LETTER | 자원 보호 |
| **R4** | Reconciliation 워커 3종 (v4 확장): **좀비 청소** + **정합성 검증** + **대기열 만료 정리** | 책임 분리 |
| **R5 (v4)** | **좀비 식별 기준 v4**: ① `payment.AUTHORIZED + booking.PENDING + 5min` (결제 좀비) ② `booking.PENDING + slot_token + slot 키 없음` (slot 만료 좀비) ③ `booking.WAITING + enqueued_at < 1h + wait:token 없음` (대기 만료) | 다양한 좀비 패턴 |
| **R6** | 좀비 보상 = VOID + RELEASE + restore_stock_and_promote. 모두 멱등. | 대기자 자동 승격 트리거 |
| **R7** | POINT COMMIT 실패 시 booking PAID 강제 + outbox 재시도 | 사용자 경험 우선 |
| **R8** | Inbox 컨트롤러 인터페이스만 | 실 PG 통합 대비 |
| **R9** | Resilience4j 5종 + paymentExecutor 풀 | 1000 TPS 동기 + 비동기 분리 |
| **R10 (v4)** | **노드 사망 시점별 11시나리오** (v3의 6개 + v4 신규 5개) | 명세 "시스템 장애 안정 동작" |
| **R11 (v4)** | **wait:token 1h TTL + 별도 정리 워커**. polling 안 하면 booking EXPIRED. | 대기열 점유 방지 |

---

## 2. Redis Fail-Fast (v3와 동일)

Fail-Fast 정책. 7일 일정 제약상 (a) 채택. 실서비스 시 (c) 완전 폴백 + Sentinel/Cluster 권장.

`POST /booking`, `GET /booking/wait/{token}` 모두 Redis 의존 → Redis 다운 시 둘 다 503. `GET /checkout` (DB만)은 정상.

---

## 3. 노드 죽음 시점별 11시나리오 (v4)

### 3.1 동기 흐름 (즉시 SLOT) — v3 시나리오 (6개)

```
┌─ [0] 분산락 SETNX ──────────────────────────┐
└─────────────────────────────────────────────┘
      ↓ ── ① ── (락 잡고 죽음)
[1] Redis Lua DECR + hold 키 생성
      ↓ ── ② ── (DECR 후, DB tx1 전)
┌─ tx1 (booking INSERT + payment REQUESTED) ─┐
└─────────────────────────────────────────────┘
      ↓ ── ③ ── (tx1 후, POINT HOLD 전)
[3-1] POINT HOLD
      ↓ ── ④ ── (HOLD 후, PG AUTHORIZE 전)
[3-2] PG AUTHORIZE
      ↓
┌─ tx2 (payment AUTHORIZED) ──────────────────┐
└─────────────────────────────────────────────┘
      ↓ ── ⑤ ── (AUTHORIZED 후, CAPTURE 전)
[4-1] PG CAPTURE [4-2] POINT COMMIT
      ↓ ── ⑥ ── (CAPTURE 후, tx3 전) ⚠️
┌─ tx3 (booking PAID + DEL hold + outbox) ───┐
└─────────────────────────────────────────────┘
```

### 3.2 대기 진입 + 승격 + 비동기 결제 — v4 신규 (5개)

```
[0~1] (동기와 동일 진입)
[1] Lua → "WAITLIST:5" 분기
      ↓ ── ⑦ ── (대기 진입 Lua 후, tx1 전) v4 신규
┌─ tx1 (booking WAITING INSERT) ──────────────┐
└─────────────────────────────────────────────┘
      ↓ ── ⑧ ── (tx1 후, HSET wait:token 전) v4 신규
[2] HSET wait:token (status, payments, ...)
      ↓ ── ⑨ ── (HSET 후, 응답 전)

[승격 시점] (자리 비어 다른 흐름이 트리거)
[A] restore_stock_and_promote.lua → "PROMOTED"
      ↓ ── ⑩ ── (Lua 후, slot/hold SET 전) v4 신규
[B] SET slot:{} + SET hold:{}
[C] UPDATE booking(승격대상) PENDING
[D] HSET wait:token READY
      ↓ ── ⑪ ── (승격 도중 임의 시점) v4 신규

[비동기 결제 진행 중]
@Async paymentExecutor 흐름 = §3.1 ①~⑥ 과 동일
      ↓ ── (그 안의 어느 시점에서 사망)
```

### 3.3 시나리오별 상태와 복구

| # | 죽은 시점 | 상태 | 복구 |
|:-:|---|---|---|
| ① | 분산락 잡고 죽음 | 락 점유만 | TTL 5초 자동 해제 |
| ② | DECR 후, tx1 전 | Redis 차감, hold 활성, booking 없음 | hold TTL 180s 만료 자동 |
| ③ | tx1 후, POINT HOLD 전 | booking=PENDING, payment=REQUESTED | Reconciliation → 보상 + 승격 |
| ④ | POINT HOLD 후, PG AUTH 전 | booking=PENDING, payment(POINT)=AUTHORIZED | Reconciliation → POINT RELEASE + 승격 |
| ⑤ | AUTHORIZED 후, CAPTURE 전 | 둘 다 AUTHORIZED | Reconciliation → PG VOID + POINT RELEASE + 승격 |
| ⑥ | CAPTURE 후, tx3 전 ⚠️ | 실제 결제됨, DB 미반영 | PG 조회 → CAPTURED 확인 → booking PAID 강제 |
| **⑦** | **WAITLIST Lua 후, tx1 전** | **Redis ZADD/HSET 됨, booking 없음** | **wait:token TTL 1h 만료, ZREM. booking row 없으니 영향 작음.** |
| **⑧** | **tx1 후, HSET wait:token 전** | **booking=WAITING, Redis ZADD만, wait:token Hash 없음** | **wait:token 만료 정리 워커 → ZREM + booking EXPIRED** |
| **⑨** | **HSET 후, 응답 전** | **모든 자료 정상, 사용자만 5xx 받음** | **사용자 retry 시 같은 Idem-Key → idem 캐시 또는 같은 waitToken 응답** |
| **⑩** | **승격 Lua 후, slot/hold SET 전** | **DECR 다시 됨 (stock -1), waitToken은 ZPOPMIN됨, slot 키 없음** | **다음 polling이 wait:token에서 status 확인. status=WAITING이면 다시 ZADD? — 보수적으로 좀비 청소 워커가 처리** |
| **⑪** | **승격 도중 임의** | **부분 진행** | **Reconciliation: booking PENDING + slot_token 있는데 slot 키 없으면 보상 + 다음 승격** |
| **비동기 결제 도중** | §3.1과 동일 ①~⑥ | wait:token에 결과 미반영 | Reconciliation: payment 상태로 식별 + 보상 + wait:token FAILED 마크 |

### 3.4 시나리오 ⑥·⑩의 핵심 통찰 (v4)

**시나리오 ⑥** (설계 의도): PG에 CAPTURED됐는데 DB 미반영. Reconciliation이 `GET /pg/transactions/{key}` 조회 → CAPTURED 확인 → booking PAID 강제.

**실 구현**: `PaymentGateway.query()` + `MockPgClient.query()` + `mock-pg /pg/transactions/:key` 골격은 있지만 **ReconciliationWorker 가 호출하지 않음**. 좀비 워커는 `payment.status = AUTHORIZED` 만 보상 — CAPTURED 좀비는 영역 밖 (`docs/miss/error_03.md`). PG 상태 동기화 워커는 향후 과제.

**시나리오 ⑩**: 승격 도중 사망. 다음 폴링이 와도 처리 어려움 (status가 어디서 멈췄는지 추적 어려움). 가장 보수적 처리:
- 좀비 청소 워커가 `booking.PENDING + slot_token 있음 + Redis slot 키 없음` 발견
- 보상 (POINT RELEASE if any) + `restore_stock_and_promote`
- booking EXPIRED 처리
- 사용자 wait:token은 만료까지 status=WAITING으로 유지하다가 별도 정리

---

## 4. Reconciliation 워커 (v4)

### 4.1 좀비 청소 워커 (`zombie-cleanup-fixed-delay-ms`, 기본 1분) — 실 구현

```java
// src/main/java/.../reconciliation/ReconciliationWorker.java
@Scheduled(fixedDelayString = "${app.reconciliation.zombie-cleanup-fixed-delay-ms}")
public void cleanupZombies() {
    Optional<String> lock = lockService.tryAcquireReconcileLock("zombie");
    if (lock.isEmpty()) return;
    try {
        Instant threshold = Instant.now().minusSeconds(props.zombieAfterMinutes() * 60L);
        Set<Long> zombieBookingIds = new HashSet<>();
        paymentRepo.findByStatusAndAuthorizedAtBefore(AUTHORIZED, threshold)
                .forEach(p -> zombieBookingIds.add(p.getBookingId()));
        paymentRepo.findByStatusAndRequestedAtBefore(REQUESTED, threshold)
                .forEach(p -> zombieBookingIds.add(p.getBookingId()));
        for (Long bookingId : zombieBookingIds) handleZombie(bookingId);
    } finally {
        lockService.release(reconcileLockKey("zombie"), lock.get());
    }
}

private void handleZombie(Long bookingId) {
    Booking booking = bookingRepo.findById(bookingId).orElse(null);
    if (booking == null || booking.getStatus() != PENDING) return;
    Product product = productRepo.findById(booking.getProductId()).orElse(null);
    if (product == null) return;

    // AUTHORIZED payment 만 보상 (CAPTURED 좀비는 영역 밖 — error_03)
    List<AuthorizedPayment> authorized = paymentRepo.findByBookingIdOrderById(bookingId).stream()
            .filter(p -> p.getStatus() == AUTHORIZED && p.getPgAuthId() != null)
            .map(p -> new AuthorizedPayment(p.getMethod(), p.getPgAuthId(), p.getAmount()))
            .toList();
    if (!authorized.isEmpty()) orchestrator.compensate(authorized, bookingId, booking.getCustomerId());
        // compensate 안에서 voidAuth 실패 시 outbox COMPENSATION_VOID/POINT_RELEASE 적재

    try {
        PromotionResult promo = stockService.restoreAndPromote(
                booking.getProductId(), String.valueOf(bookingId), product.getStockTotal());
        if (promo.isPromoted()) promotionService.activate(promo.waitToken(), booking.getProductId());
    } catch (RuntimeException e) {
        // outbox COMPENSATION_STOCK_RESTORE 적재
    }
    persistence.finalizeFailed(bookingId, TIMEOUT);
}
```

> **좀비 워커는 `PaymentStatus.AUTHORIZED` 만 필터** — CAPTURED 좀비 (capture 부분 실패 후) 는 영역 밖. 운영자 수동 조치 필요 (`docs/miss/error_03.md`).

### 4.2 정합성 검증 워커 (`consistency-verify-fixed-delay-ms`, 기본 5분) — 실 구현

```java
@Scheduled(fixedDelayString = "${app.reconciliation.consistency-verify-fixed-delay-ms}")
public void verifyConsistency() {
    Optional<String> lock = lockService.tryAcquireReconcileLock("verify");
    if (lock.isEmpty()) return;
    try {
        for (Product p : productRepo.findAll()) {
            long paid    = bookingRepo.countByProductIdAndStatus(p.getId(), PAID);
            long pending = bookingRepo.countByProductIdAndStatus(p.getId(), PENDING);
            if (paid + pending > p.getStockTotal()) {
                log.error("CONSISTENCY_VIOLATION productId={} paid={} pending={} total={}",
                        p.getId(), paid, pending, p.getStockTotal());
                // 운영 시: 모니터링/알람 연동 (현재는 log only)
            }
        }
    } finally {
        lockService.release(reconcileLockKey("verify"), lock.get());
    }
}
```

> 알람/모니터링 연동은 향후 과제. 운영 시 로그 grep "CONSISTENCY_VIOLATION" 으로 발견.

### 4.3 대기열 만료 정리 워커 (`waitlist-cleanup-fixed-delay-ms`, 기본 5분) — 실 구현

```java
@Scheduled(fixedDelayString = "${app.reconciliation.waitlist-cleanup-fixed-delay-ms}")
public void cleanupExpiredWaitlist() {
    Optional<String> lock = lockService.tryAcquireReconcileLock("wait_cleanup");
    if (lock.isEmpty()) return;
    try {
        Instant threshold = Instant.now().minusSeconds(props.waitExpiryHours() * 3600L);
        List<Booking> stale = bookingRepo.findByStatusAndEnqueuedAtBefore(WAITING, threshold);
        for (Booking b : stale) {
            if (b.getWaitToken() != null) {
                redis.delete(waitTokenKey(b.getWaitToken()));
                redis.opsForZSet().remove(waitlistKey(b.getProductId()), b.getWaitToken());
            }
            persistence.markExpired(b.getId(), WAIT_TIMEOUT);
            outboxRepo.save(OutboxEvent.builder()...eventType(BOOKING_EXPIRED).build()); // Phase 1
        }
    } finally {
        lockService.release(reconcileLockKey("wait_cleanup"), lock.get());
    }
}
```

---

## 5. Outbox 워커 (R2, R3) — v3와 동일

`@Scheduled(fixedDelay=1s)` + `SELECT ... FOR UPDATE SKIP LOCKED` + 지수 백오프.

이벤트 타입 확장 (v4):
- `BOOKING_PAID`
- `BOOKING_FAILED`
- `BOOKING_EXPIRED` (v4 신규)
- `COMPENSATION_VOID`
- `COMPENSATION_POINT_RELEASE`
- `COMPENSATION_STOCK_RESTORE`
- `POINT_COMMIT_RETRY`
- `WAIT_PROMOTED` (v4 신규 — 분석용 로그)

---

## 6. Resilience4j 구체 설정값 (v4)

### 6.1 PG 게이트웨이 — v3와 동일

```yaml
resilience4j:
  # Timeout 은 @TimeLimiter 어노테이션 대신 RestClient 자체 timeout 사용
  #   - HttpClient.connectTimeout: 500ms
  #   - JdkClientHttpRequestFactory.readTimeout: 1s
  # (별도 thread pool 비용 회피 + IO timeout 으로 thread 자연 회수)
  circuitbreaker.instances.pgGateway:
    sliding-window-type: TIME_BASED
    sliding-window-size: 10
    minimum-number-of-calls: 20
    failure-rate-threshold: 50
    slow-call-rate-threshold: 80
    slow-call-duration-threshold: 800ms
    wait-duration-in-open-state: 30s
  bulkhead.instances.pgGateway:
    max-concurrent-calls: 100
    max-wait-duration: 100ms
```

`MockPgClient` 의 모든 4개 메서드에 `@CircuitBreaker(name="pgGateway") @Bulkhead(name="pgGateway")` 어노테이션 적용.

### 6.2 paymentExecutor (`app.payment-executor`)

```yaml
core-pool-size: 10
max-pool-size: 50
queue-capacity: 100
thread-name-prefix: pay-async-
keep-alive-seconds: 60
# RejectedExecutionHandler: CallerRunsPolicy (AsyncConfig.java)
```

### 6.3 Redis 장애 — 명시적 Fail-Fast 미구현

현재 동작: Lettuce 가 `RedisConnectionFailureException` throw → `GlobalExceptionHandler.unknown` 이 catch → **500 INTERNAL_ERROR**. `Retry-After` 헤더 자동 부착 없음. `redisHealth` Circuit Breaker (DECISIONS §9) 는 향후 과제.

---

## 7. POINT COMMIT 실패 처리 (R7) — 실 구현

설계 의도: "booking PAID 강제 + outbox(POINT_COMMIT_RETRY)" — 그러나 실 구현은:

- `BookingService.handleSlot` / `PromotionService.processPayment` 의 `capture` catch:
  1. POINT 가 섞여 있으면 `outbox(POINT_COMMIT_RETRY)` 적재 (Phase 1)
  2. `orchestrator.compensate(authorized)` 호출 — 이미 CAPTURED 된 MAIN 에 대한 voidAuth 는 mock-pg 409 반환 (error_03)
  3. `handlePaymentFailure` → booking **FAILED** (PAID 강제 X)

→ 본 정책은 v3 설계 의도 (PAID 강제) 보다 보수적 (FAILED 명시). R7 dispatcher 의 실제 재시도 로직 (Phase 2) 미구현.

---

## 8. Inbox 패턴 인터페이스 (R8) — v3와 동일

---

## 9. 5종 동기 방어 세트 — v3와 동일

| 방어 | 설정 |
|---|---|
| Timeout | PG 1s |
| Circuit Breaker | 50% / 10s / OPEN 30s |
| Bulkhead | PG 100 동시 |
| Tomcat threads.max | 400 |
| HikariCP pool | 50/노드 |

---

## 10. 정합성 방어선 종합 (v4)

| 자원 | 1차 | 2차 | 3차 |
|---|---|---|---|
| **재고** | Lua `conditional_decr_or_wait` | `booking.PAID + PENDING` ≤ `stock_total` | hold/slot TTL + Reconciliation |
| **대기열** | Lua 원자 ZADD/ZPOPMIN | `booking.wait_token UNIQUE` | wait 만료 정리 워커 (5분) |
| **선점 만료** | hold/slot TTL 180s | — | 좀비 청소 (1분) |
| **포인트** | `CustomerPoint.deduct()` + `@Version` | `point_transaction.reference_key UNIQUE` | (Pt1 보류 — 합산 검증 워커 없음) |
| **외부 멱등** | Redis `idem:{key}` + request_hash | `booking.idempotency_key UNIQUE` | — |
| **PG 호출 멱등** | 분산락 + Mock PG 캐시 | `payment.pg_idempotency_key UNIQUE` | Outbox COMPENSATION_* (Phase 1 적재) |
| **1인1상품** | application `existsByCustomerIdAndProductIdAndStatusIn` | `booking.active_key UNIQUE` (generated) | — |
| **승격 정합성** | Lua `restore_stock_and_promote` (OVERFLOW 가드) | — | 좀비 청소 (5분 임계) |
| **비동기 결제 상태** | wait:token Hash 업데이트 + outbox(BOOKING_PAID/FAILED) | — | 좀비 청소 |
| **노드 사망** | tx 경계 + TTL 자동 만료 | — | 11시나리오 좀비 청소 |
| **PG 장애** | RestClient timeout + Resilience4j CB/BH | — | Outbox COMPENSATION_VOID (Phase 1) |
| **Redis 장애** | Spring 기본 500 응답 (Lettuce auto-reconnect) | — | 명시적 Fail-Fast CB 향후 과제 |

---

## 11. 구현 시 인라인 결정 사항

| 항목 | 시점 |
|---|---|
| OpenAPI 스펙 | 컨트롤러 작성 시 |
| 시퀀스 다이어그램 (Mermaid) | README |
| 패키지 구조 (헥사고날) | 첫 패키지 만들 때 |
| application.yml 통합 | 첫 부팅 |
| docker-compose 5컨테이너 | 이미 작성됨 |
| Mock PG 미니 서버 | 이미 작성됨 |
| 부하 테스트 (k6) | 1000 TPS 검증 시 |

---

## 12. v4 변경 요약

- 노드 사망 시나리오 6개 → 11개
- 좀비 청소 워커 확장 (REQUESTED / AUTHORIZED / Slot 만료 세 종류)
- 대기열 만료 정리 워커 신규 (5분 주기)
- 정합성 검증 워커에 waitlist drift 검증 추가
- Outbox 이벤트 타입 신규 (BOOKING_EXPIRED, WAIT_PROMOTED)
- paymentExecutor (별도 thread pool) 추가
- failed_reason / status 분리: EXPIRED는 별도 status, SLOT_TIMEOUT·WAIT_TIMEOUT은 expired_reason
