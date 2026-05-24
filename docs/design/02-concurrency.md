# 02. 동시성 / 재고 정합성 / 공정성 (v4)

> **단계 목적**: 모델 D + 대기열 + 자동 진행의 동시성 흐름을 실행 가능한 수준으로.
>
> **v4 변경 요지**: `conditional_decr_or_wait` Lua가 3-way 분기. 공정성 정의에 *대기열 FIFO* 포함. 대기자 승격 시점의 race 처리. polling이 결제 트리거하는 비동기 흐름.

---

## 1. 핵심 결정 (v4)

| ID | 결정 | 근거 |
|---:|---|---|
| **C1** | **Lua `conditional_decr_or_wait`이 3-way 분기**: SLOT 점유 / WAITLIST 진입 / FULL 매진. 모두 원자. | 분기 사이 race 차단. |
| **C2** | **공정성 = (1) DECR 도달 순서 + (2) 대기열 FIFO (ZADD score=enqueued_at)**. | Redis 단일 스레드가 두 자료구조를 직렬화. 명시적 큐 없이 implicit queue. |
| **C3** | **분산락 lifetime = 요청 처리 전 단계** (SETNX EX 5). | 동일 customer·product 중복 진입 차단. |
| **C4** | **락 해제는 `safe_unlock.lua`** | TTL 만료 후 남의 락 푸는 사고 방지 |
| **C5** | **락 획득 실패 시 분기 4가지** (α/β/γ/γ') | 서버 thread 점유 최소화 |
| **C6** | **음수 차감 방지는 Lua 원자 묶음** | 카운터 항상 ≥ 0 |
| **C7** | **hold/slot 키 TTL 모두 180s (3분)로 통일**. slot 만료 = 자동 자리 해제 + 다음 승격 트리거. | 결제 시간 제한 명세 (3분) 충족 |
| **C8** | **PG 호출 Bulkhead 100 동시** | PG 막혀도 Checkout 정상 |
| **C9 (v4)** | **승격 시점 두 가지**: ① 결제 실패 동기 흐름에서 즉시 (`restore_stock_and_promote.lua`) ② slot 만료 시 좀비 청소 워커가. | 자리 사장 즉시 해소 |
| **C10 (v4)** | **polling 자체가 결제 트리거**. `try_promote.lua`로 status READY→PROCESSING 원자 전환 + `@Async`로 결제 시작. polling 응답은 즉시 (thread 안 막음). | 대기자 승격 즉시 결제 진행. polling thread 효율. |

---

## 2. 차감 + 대기 흐름 (v4 모델 D)

### 2.1 즉시 처리 가능 (재고 ≥ 1)

```
POST /booking
   ├─[0] 분산락 SETNX
   ├─[1] EVAL conditional_decr_or_wait.lua → "SLOT:9"
   │     (재고 -1, hold:p:b TTL 180s)
   ├─[2] DB INSERT booking PENDING + payment REQUESTED
   ├─[3] POINT HOLD → PG AUTHORIZE → PG CAPTURE → POINT COMMIT
   ├─[4] DB UPDATE booking PAID + DEL hold + outbox(BOOKING_PAID)
   └─락 해제, idem 캐시
   → 200 PAID
```

### 2.2 대기열 진입 (재고 0, 대기 < 30)

```
POST /booking
   ├─[0] 분산락 SETNX
   ├─[1] EVAL conditional_decr_or_wait.lua → "WAITLIST:5"
   │     (ZADD waitlist + HSET wait:token TTL 1h)
   ├─[2] DB INSERT booking WAITING (wait_token, enqueued_at)
   ├─[3] HSET wait:token:{wt} {status, bookingId, customerId, productId, enqueuedAt, ...}
   └─락 해제, idem 캐시
   → 200 WAITING + {waitToken, position: 5, pollingIntervalMs: 2000}
```

### 2.3 매진 (대기열 가득)

```
POST /booking
   ├─[0] 분산락 SETNX
   ├─[1] EVAL conditional_decr_or_wait.lua → "FULL"
   └─락 해제, idem 캐시 (Retry-After: 60 헤더)
   → 409 SOLD_OUT
```

### 2.4 대기 polling (자동 진행)

```
GET /booking/wait/{waitToken}
   ├─[1] EVAL try_promote.lua → "WAITING" | "READY" | "PROCESSING" | "PAID" | "FAILED"
   │
   ├─[A] WAITING:
   │     └ HGET wait:token:{wt} → position(ZRANK), enqueuedAt
   │     → 200 { status: "WAITING", position: 3 }
   │
   ├─[B] READY:
   │     └ Lua가 status를 PROCESSING으로 원자 전환
   │     └ @Async paymentExecutor.start(waitToken, slotToken, payments)
   │       (백그라운드: POINT HOLD → PG AUTHORIZE → ... 모델 C 흐름)
   │     → 200 { status: "PROCESSING" }
   │
   ├─[C] PROCESSING:
   │     → 200 { status: "PROCESSING" }
   │
   ├─[D] PAID:
   │     └ HGET wait:token:{wt} bookingId
   │     → 200 { status: "PAID", bookingId: 42 }
   │
   └─[E] FAILED:
         → 422 { reason, message }
```

### 2.5 대기자 승격 (자리 비었을 때)

**즉시 승격** (동기 흐름 실패 시):
```
[모델 C 흐름 중 결제 실패] (예: PG AUTHORIZE 실패)
   ├─보상: POINT RELEASE
   ├─EVAL restore_stock_and_promote.lua
   │   → INCR stock + DEL hold
   │   → ZPOPMIN waitlist:product:{pid} → waitToken
   │   → 다시 DECR stock + 다음 hold 키는 호출자가 SET
   │   → 반환: "PROMOTED:{waitToken}"
   ├─slotToken 발급, SET slot:{slotToken} TTL 180s
   ├─SET hold:product:{pid}:booking:{newBid} TTL 180s
   ├─UPDATE booking(승격대상) SET status=PENDING, slot_token, promoted_at
   ├─HSET wait:token:{wt} status=READY, slot_token, expiresAt
   └─이 사용자의 다음 polling이 READY 발견 → 비동기 결제 시작
```

**워커 승격** (좀비 발견 시):
```
좀비 청소 워커 (zombie-cleanup-fixed-delay-ms, 기본 1분):
  - payment.status = AUTHORIZED 가 authorized_at < NOW - 5분
  - payment.status = REQUESTED 가 requested_at < NOW - 5분
  → handleZombie: compensate → restoreAndPromote → finalizeFailed
  → 보상/복구 실패 시 outbox COMPENSATION_* 적재 (Phase 1)
```

---

## 3. Lua 스크립트 (v4)

### 3.1 `conditional_decr_or_wait.lua`

```lua
-- KEYS[1] = stock:product:{productId}
-- KEYS[2] = waitlist:product:{productId}
-- KEYS[3] = hold:product:{productId}:booking:{tmpUuid}
-- ARGV[1] = waitToken (tmpUuid)
-- ARGV[2] = hold TTL (180)
-- ARGV[3] = waitlist max (30)
-- ARGV[4] = enqueued_at ms
--
-- 반환:
--   "SLOT:{remaining}"
--   "WAITLIST:{position 1-based}"
--   "FULL"

local stock = tonumber(redis.call('GET', KEYS[1]))
if stock ~= nil and stock > 0 then
    local after = redis.call('DECR', KEYS[1])
    redis.call('SET', KEYS[3], ARGV[1], 'EX', tonumber(ARGV[2]))
    return 'SLOT:' .. tostring(after)
end

local wlSize = redis.call('ZCARD', KEYS[2])
if wlSize >= tonumber(ARGV[3]) then
    return 'FULL'
end

redis.call('ZADD', KEYS[2], tonumber(ARGV[4]), ARGV[1])
local position = redis.call('ZRANK', KEYS[2], ARGV[1])
return 'WAITLIST:' .. tostring(position + 1)
```

### 3.2 `restore_stock_and_promote.lua`

```lua
-- KEYS[1] = stock:product:{productId}
-- KEYS[2] = hold:product:{productId}:booking:{bid}  (현재 점유자 hold 키)
-- KEYS[3] = waitlist:product:{productId}
-- ARGV[1] = stock_total (가드)
--
-- 반환:
--   "RESTORED"               — 대기열 비어 있음, 재고만 복구
--   "PROMOTED:{waitToken}"   — 다음 대기자 발견, hold·slot 키는 호출자가 SET
--   "OVERFLOW"               — INCR 결과 stock_total 초과 (이상 상황) — DECR 롤백 + DEL hold

local after = redis.call('INCR', KEYS[1])
if tonumber(ARGV[1]) ~= nil and after > tonumber(ARGV[1]) then
    redis.call('DECR', KEYS[1])
    redis.call('DEL', KEYS[2])
    return 'OVERFLOW'
end
redis.call('DEL', KEYS[2])

local popped = redis.call('ZPOPMIN', KEYS[3], 1)
if #popped == 0 then
    return 'RESTORED'
end
local waitToken = popped[1]
-- 즉시 다시 DECR (대기자가 자리 차지)
redis.call('DECR', KEYS[1])
return 'PROMOTED:' .. waitToken
```

### 3.3 `try_promote.lua`

```lua
-- KEYS[1] = wait:token:{waitToken}
-- ARGV[1] = new status when READY ('PROCESSING')
--
-- 반환: 현재 상태 (READY 시 PROCESSING으로 전환됨)

local status = redis.call('HGET', KEYS[1], 'status')
if status == 'READY' then
    redis.call('HSET', KEYS[1], 'status', ARGV[1])
    return 'READY'
end
return status or 'NOT_FOUND'
```

### 3.4 `safe_unlock.lua` (v3와 동일)

---

## 4. 공정성 정의 (v4)

### 4.1 작전적 정의 — 2단 큐

> **공정성**:
> ① **재고 확보**: Lua DECR 도달 순서 (Redis 단일 스레드 직렬화)
> ② **대기열 진입 후**: ZADD score=enqueued_at FIFO. ZPOPMIN으로 가장 오래된 대기자 승격.

Redis 단일 스레드가 두 자료구조(stock counter + waitlist sorted set)를 모두 직렬화 처리 → 명시적 큐 없이 **이중 implicit queue**.

### 4.2 사용자 시나리오 — 공정성 검증

| 시나리오 | 처리 |
|---|---|
| 1000명 동시 진입 (재고 10) | 첫 10명 SLOT, 다음 30명 WAITLIST (FIFO), 나머지 960명 SOLD_OUT |
| 대기 5번 사용자, 결제 실패자 0명 | 5번 사용자는 계속 대기. 사용자 측 polling으로 position 표시. |
| 대기 5번 사용자, 결제 실패자 4명 | 4번 승격 → 5번이 1번 승격. polling으로 READY 받음. |
| 대기 진입 후 polling 안 함 | wait:token TTL 1h 만료. booking EXPIRED. ZREMRANGE로 정리. |
| 같은 customer 가 대기 중 재시도 | application `existsByCustomerIdAndProductIdAndStatusIn` 사전 체크 → 409 ALREADY_RESERVED ("이미 진행 중이거나 완료된 예약이 있습니다."). race 시 DB `uk_booking_active` 가 안전망 |

### 4.3 DECISIONS.md 박을 문장

> *공정성의 commit point는 두 단계로 정의됨: (1) Redis Lua DECR 도달 순서로 재고 확보, (2) ZADD 시각으로 대기열 FIFO. Redis 단일 스레드가 두 자료구조를 직렬화하여 명시적 큐(Kafka 등) 없이 implicit queue 역할 수행. 결제 실패자 자리는 ZPOPMIN으로 다음 대기자에게 즉시 양도되어 자원 사장 문제 해소.*

---

## 5. 분산락 lifecycle (v4)

### 5.1 락 키와 값

```
key   = lock:booking:customer:{customerId}:prod:{productId}
value = {requestUuid}
TTL   = 5초
```

### 5.2 락 보호 범위 (v4 확장)

```
[0] 분산락 SETNX
[1] EVAL conditional_decr_or_wait.lua   ← 락 안에서
[2] DB INSERT booking (PENDING or WAITING)
[3] HSET wait:token (WAITING 케이스)
   ──또는──
[3] 결제 흐름 시작 (PENDING 케이스, SUPPLEMENT 먼저)
   ...
[N] 락 해제 (safe_unlock.lua)
```

WAITING 케이스에선 락 해제 빨라짐 (~5ms). PENDING 케이스는 v3와 동일 (~174ms).

### 5.3 락 획득 실패 분기 (C5 — v4 갱신)

| 분기 | 조건 | 응답 |
|---|---|---|
| **α** | 같은 Idem-Key 처리 중 | 409 IN_PROGRESS + Retry-After: 5 |
| **β** | 다른 Idem-Key, 같은 customer+product 처리 중 | 409 IN_PROGRESS + Retry-After: 5 |
| **γ** | 같은 Idem-Key 완료 | Redis idem 캐시 재생 (200 PAID / 200 WAITING / 409 SOLD_OUT 그대로) |
| **γ'** | 다른 Idem-Key, 같은 customer+product 활성 (WAITING/PENDING/PAID) | 409 ALREADY_RESERVED |

---

## 6. 대기자 승격 정밀 흐름 (v4 신규)

### 6.1 승격 시나리오

```
시각 T+0     사용자 A가 SLOT 점유 (재고 -1=9, hold:p:42 TTL 180s)
            사용자 B~K가 SLOT 점유 (재고 0)
            사용자 L~N (30명)이 WAITLIST 진입 (position 1~30)

시각 T+150ms 사용자 A가 PG AUTHORIZE 실패 (카드 거절)
            ├─보상 동기 흐름 진입
            ├─POINT RELEASE (이미 HOLD됐다면)
            ├─EVAL restore_stock_and_promote.lua
            │   - INCR stock 0→1
            │   - DEL hold:p:42
            │   - ZPOPMIN waitlist:product:{pid} → "wt_L1234"
            │   - DECR stock 1→0 (다시 차감, 자리 양도)
            │   - 반환: "PROMOTED:wt_L1234"
            ├─slotToken = "slot_xxx" 발급
            ├─SET slot:slot_xxx = wt_L1234 EX 180s
            ├─SET hold:product:{pid}:booking:{newBid_L} EX 180s
            ├─DB UPDATE booking(L) SET status=PENDING, slot_token, promoted_at
            ├─HSET wait:token:wt_L1234 status=READY, slot_token, expiresAt
            └─사용자 A에게는 422 FAILED 응답

시각 T+150ms~ 사용자 L의 다음 polling (2~3초 후 또는 즉시)
            ├─GET /booking/wait/wt_L1234
            ├─EVAL try_promote.lua → "READY"
            ├─try_promote가 status를 PROCESSING으로 원자 전환
            ├─@Async paymentExecutor.start(wt_L1234, slotToken, payments)
            │   (백그라운드: POINT HOLD → PG AUTHORIZE → ...)
            └─polling 응답: 200 PROCESSING

시각 T+150ms+~200ms 결제 진행 (모델 C 흐름과 동일)

시각 T+400ms 사용자 L의 다음 polling
            └─try_promote.lua → "PAID"
            → 200 PAID + bookingId
```

### 6.2 동시 승격 race 처리

여러 결제 실패가 동시 발생 시 — 각각 `restore_stock_and_promote`를 Lua로 호출하므로 Redis 단일 스레드가 직렬화. ZPOPMIN이 한 번에 하나씩 다른 waitToken 반환. **race 없음**.

### 6.3 좀비 청소 워커 — payment 좀비 식별 (실 구현)

실제 코드는 `slot:*` SCAN 이 아닌 **`payment.status` 시간 임계 초과**로 좀비를 식별:

```java
// ReconciliationWorker.cleanupZombies (실제 구현)
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
    } finally { /* lock release */ }
}

// handleZombie 흐름:
//   1) AUTHORIZED payment 보상 (orchestrator.compensate)
//      → 실패 시 outbox(COMPENSATION_VOID / COMPENSATION_POINT_RELEASE) 적재
//   2) stockService.restoreAndPromote → PROMOTED 시 promotionService.activate
//      → 실패 시 outbox(COMPENSATION_STOCK_RESTORE) 적재
//   3) persistence.finalizeFailed(bookingId, TIMEOUT)
```

대기열 만료 정리는 별도 워커 (`cleanupExpiredWaitlist`) — `booking.enqueued_at` 1h 임계 초과 검사 + `outbox(BOOKING_EXPIRED)` 적재.

---

## 7. PG 호출 Bulkhead (C8)

v3와 동일. PG 호출 100 동시 제한.

**v4에서의 의미**: 즉시 처리 10명 + 대기 승격 시 1~2명 더 = 동시 PG 호출 최대 ~12명. Bulkhead 100은 압도적 여유.

---

## 8. 1000 TPS 동기 산수 (v4 재계산)

```
요청 처리시간:
  즉시 결제 흐름 (재고 확보 + 결제 성공): ~174ms      [10명]
  대기 진입 흐름 (WAITLIST 진입만):       ~10ms       [30명]
  매진 즉시 응답:                          ~5ms       [960명]
  polling 1회 (WAITING):                  ~3ms       [대기자, 2~3초마다]
  polling 1회 (PROCESSING):               ~3ms
  polling 1회 (PAID/FAILED):              ~3ms

Little's Law:
  필요 슬롯 = 10×0.174 + 30×0.010 + 960×0.005
            ≈ 1.74 + 0.3 + 4.8 ≈ 7 슬롯/sec (1000 TPS 기준)
  
  + polling 트래픽 (대기자 30명 × 2초 = ~15 req/sec): ~0.05 슬롯
  
  가용 슬롯 = 2 노드 × 400 thread = 800
  여유율   = 압도적 (100배+)

연결 풀:
  v3 산수 그대로 유효.
```

**결론**: 1000 TPS + 30명 polling 트래픽도 동기 처리에 압도적 여유.

---

## 9. 정합성 방어 종합 (v4)

| 자원 | 1차 (런타임) | 2차 (DB 제약) | 3차 (정합성 잡) |
|---|---|---|---|
| **재고** | Lua `conditional_decr_or_wait` | `booking.PAID` 집계 ≤ `stock_total` | hold/slot TTL + Reconciliation |
| **대기열** | Lua 원자 ZADD/ZPOPMIN/ZRANK | `booking.wait_token UNIQUE` | wait:token TTL 1h + 정리 워커 |
| **선점 만료** | hold/slot 키 TTL 180s | — | 좀비 청소 (1분) + 즉시 승격 |
| **포인트** | `CustomerPoint.deduct()` application 검증 + `@Version` 낙관락 | `point_transaction.reference_key UNIQUE` | (합산 검증 워커는 향후 — Pt1 보류) |
| **외부 멱등** | Redis `idem:{key}` + request_hash SHA-256 | `booking.idempotency_key UNIQUE` | — |
| **PG 호출 멱등** | 분산락 + Mock PG 캐시 | `payment.pg_idempotency_key UNIQUE` | Outbox COMPENSATION_* (Phase 1) |
| **1인1상품** | application `existsByCustomerIdAndProductIdAndStatusIn` | `booking.active_key UNIQUE` (generated, WAITING/PENDING/PAID) | — |
| **승격 정합성** | Lua `restore_stock_and_promote` 원자 (OVERFLOW 가드 포함) | — | 좀비 청소 워커 (5분 임계) |

---

## 10. 다음 단계로 넘기는 미결

| → 단계 | 미결 항목 |
|---|---|
| **3 (멱등성)** | 대기 진입도 멱등 (같은 Idem-Key 재시도 시 같은 waitToken 반환). polling은 멱등 자동. |
| **4 (결제 모듈)** | 비동기 결제 진행 (@Async / 별도 thread pool). 결제 결과 → wait:token Hash 업데이트. |
| **5 (장애대응)** | (1) 대기자 승격 도중 노드 사망. (2) slot 만료 좀비 처리. (3) wait:token 1h 만료 정리. (4) 모든 노드 사망 시나리오 v4 매트릭스. |
