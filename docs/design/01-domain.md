# 01. 도메인 모델 & ERD (v4 통합본)

> **단계 목적**: 엔티티·관계 확정, 상태머신, DDL, Redis 키 스키마, Mock PG 인터페이스, **대기열 시스템**.
>
> **v4 변경 요지**: 모델 C → **모델 D(선점 + 대기열 + 자동 진행)** 전환. 결제 실패자의 자리 사장 문제를 대기열 30명으로 해소. POST /booking 응답에 `WAITING` 추가, GET /booking/wait/{token} polling API 신규. Booking 상태 3개 → 5개 확장. Redis 키 4종 신규(waitlist, wait:token, slot, hold TTL 통일).

---

## 1. 핵심 결정 요약 (v4)

| ID | 결정 | 근거 |
|---:|---|---|
| **D1** | **재고 흐름**: Redis Lua `conditional_decr_or_wait`가 (1) 재고 ≥ 1 → slot 점유 (2) 재고=0 + 대기열<30 → 대기 진입 (3) 대기 가득 → 매진. DB `booking.PAID` 집계가 정합성 검증 기준. | 모델 D 채택. 결제 실패자 자리 사장 문제 해소 (대기 1번 승격). PG 호출량은 여전히 ~10~30회 수준. |
| **D2** | `product.stock_total` 컬럼 한 줄, 별도 Inventory 테이블 없음 | YAGNI |
| **D3** | Booking 1 : N Payment | 복합결제 시 수단별 row |
| **D4** | UserPoint(잔액) + PointTransaction(이력) 분리 | 결제 실패 보상 시 역산 |
| **D5** | **Booking 상태 5개 (v4 확장)**: `WAITING / PENDING / PAID / FAILED / EXPIRED` + `failed_reason` | 대기열 도입으로 WAITING·EXPIRED 신규. PENDING은 slot 잡고 결제 진행 중 (사용자에겐 PROCESSING으로 표시). |
| **D6** | 멱등성 = 외부 키 1개(`Idempotency-Key`), 저장 2곳(`booking.idempotency_key` UNIQUE + Redis `idem:{key}`). 대기 진입도 멱등. | 같은 키 재시도 시 wait/booking row 중복 생성 안 함. |
| **D7** | **분산락 사용처**: ① 동일유저·상품 진입(PG·대기 중복 방어), ② Outbox 워커, ③ Reconciliation 잡, ④ 대기자 승격 트리거. **비사용처**: 재고 차감 자체 (Redis Lua 원자). | 락 보호 범위 확장 |
| **D8** | **동기 호출 + 5종 방어 세트** (Timeout 1s, CB, Bulkhead 100, Tomcat 400, HikariCP 50). | 1000 TPS 산수상 동기 처리 가능. |
| **D9** | 결제수단 **카테고리화** MAIN/SUPPLEMENT | 신규 결제수단 추가 시 enum + Strategy 1개 |
| **D10** | **PG = Authorize / Capture / Void 3-call** | 가벼운 보상 |
| **D11** | **Outbox 필수** + **Inbox 인터페이스만** | 영구 일관성 |
| **D12** | 1인 1상품: `booking.active_key` generated column UNIQUE — **WAITING / PENDING / PAID 모두 활성** | 대기 중에도 같은 customer·product 재시도 차단 |
| **D13** | **Lua 스크립트 4종 (v4 확장)**: `conditional_decr_or_wait`(재고/대기/매진 분기), `restore_stock_and_promote`(자리 복구 + 대기 1번 승격), `try_promote`(대기자 → READY 전환), `safe_unlock`. | 다단계 명령 원자 묶음 |
| **D14** | Docker 분산환경 (Nginx + App×2 + MySQL + Redis + Mock PG) | 명세 충족 |
| **D15 (v4)** | **대기열 시스템**: 재고 10 + 대기 30 + slot 3분 TTL. 자동 진행 (polling이 결제 트리거, 비동기 처리). | 결제 실패자 자리 사장 문제 해소. 인터파크식 UX. |
| **D16 (v4)** | **자동 진행 흐름**: polling 응답이 결제 결과 (`WAITING` → `PROCESSING` → `PAID`/`FAILED`). 결제는 `@Async` 또는 별도 워커가 백그라운드 진행. | polling이 thread를 막지 않음. 사용자 명시적 confirm 단계 생략 — UX 단순. |

---

## 2. 인프라 구성

```
                        ┌──────────────┐
                        │    Nginx     │
                        │    :80       │
                        └──────┬───────┘
                  ┌────────────┴────────────┐
            ┌─────▼─────┐              ┌────▼──────┐
            │   App1    │              │   App2    │
            │  :8080    │              │  :8080    │
            └─────┬─────┘              └─────┬─────┘
                  └────────────┬─────────────┘
            ┌──────────────────┼──────────────────┐
       ┌────▼────┐        ┌────▼────┐        ┌────▼────┐
       │  MySQL  │        │  Redis  │        │ Mock PG │
       │  :3306  │        │  :6379  │        │  :9090  │
       │  (SoT)  │        │ implicit│        │ Auth/   │
       │         │        │  queue  │        │ Capture/│
       │         │        │ +waitlist│       │  Void/  │
       │         │        │ +locks  │        │  Query  │
       │         │        │ +holds  │        │         │
       │         │        │ +slots  │        │         │
       │         │        │ +idem   │        │         │
       └─────────┘        └─────────┘        └─────────┘
```

---

## 3. 도메인 ERD

```
┌──────────────┐                    ┌────────────────────┐
│   customer   │                    │   product          │
│ id, name     │                    │ id, name, price    │
└──────┬───────┘                    │ check_in/out_at    │
       │ 1                          │ stock_total        │
       │                            │ open_at            │
       │ 1                          └─────────┬──────────┘
       ▼                                      │ 1
┌──────────────────────┐                      │ N
│  customer_point      │                      ▼
│ customer_id (PK,FK)  │        ┌────────────────────────────────┐
│ balance (≥0)         │        │  booking                       │
│ version (낙관락)     │        │ id, customer_id, product_id    │
└────────┬─────────────┘        │ status: WAITING / PENDING /    │
         │ 1                    │         PAID / FAILED /        │
         │ N                    │         EXPIRED                │
         ▼                      │ failed_reason                  │
┌──────────────────────┐        │ total_amount                   │
│ point_transaction    │◄───────│ idempotency_key (UQ)           │
│ id, customer_id      │        │ request_hash (SHA-256)         │
│ booking_id           │        │ active_key (UQ, generated:     │
│ delta (+/-)          │        │   WAITING + PENDING + PAID)    │
│ reason               │        │ wait_token (NULL if direct)    │
│ reference_key (UQ)   │        │ slot_token (NULL until READY)  │
└──────────────────────┘        │ enqueued_at / promoted_at      │
                                │ created/paid/failed/expired_at │
                                └──────┬─────────────────────────┘
                                       │ 1
                                       │ N
                                       ▼
                                ┌────────────────────────────┐
                                │  payment                   │
                                │ id, booking_id             │
                                │ method (CARD/YPAY/POINT)   │
                                │ amount                     │
                                │ status: REQUESTED/         │
                                │   AUTHORIZED/CAPTURED/     │
                                │   VOIDED/FAILED            │
                                │ pg_auth_id                 │
                                │ pg_idempotency_key (UQ)    │
                                │ UNIQUE(booking_id, method) │
                                └────────────────────────────┘

┌────────────────────────────────┐    ┌────────────────────────────────┐
│  outbox_event                  │    │  inbox_event (실 PG 콜백용)     │
│  aggregate_type/id             │    │  source, external_event_id (UQ)│
│  event_type, payload           │    │  payload, status               │
│  status, retry_count           │    │  received/processed_at         │
│  next_attempt_at               │    └────────────────────────────────┘
└────────────────────────────────┘
```

---

## 4. 상태머신 (v4)

### 4.1 Booking 상태머신

```
                  POST /booking 진입
                        │
                  Redis Lua 분기:
                        │
                ┌───────┼────────┐
                ▼       ▼        ▼
            (재고≥1)  (대기<30)  (대기=30)
              slot     wait      → 409
              잡음     진입      SOLD_OUT
                │       │
                ▼       ▼
            ┌────────┐ ┌─────────┐
            │PENDING │ │ WAITING │  (booking row, wait_token)
            └───┬────┘ └────┬────┘
       모든 paym│            │ 차례 됨 (워커 또는 polling 트리거)
       CAPTURED │            │ → slot_token 발급, promoted_at
                │            ▼
                │      ┌─────────┐
                │      │ PENDING │ (slot 잡힘, 결제 진행)
                │      │ (비동기)│
                │      └────┬────┘
                ▼           ▼
            ┌────────┐ ┌──────────┐  ┌──────────┐
            │  PAID  │ │  FAILED  │  │ EXPIRED  │
            └────────┘ └──────────┘  └──────────┘
            paid_at    failed_at     expired_at
                       failed_reason enum (12종, FailureReason.java) {
                         // 입력/검증
                         INSUFFICIENT_POINT,
                         INVALID_COMBINATION,
                         // 외부 시스템
                         PAYMENT_DECLINED,
                         LIMIT_EXCEEDED,
                         PG_TIMEOUT,
                         PG_UNAVAILABLE,
                         // 시스템
                         CAPTURE_FAILED,
                         SYSTEM_ERROR,
                         // 사용자 명시
                         USER_CANCELED,
                         // 시간 (EXPIRED 상태에 매핑)
                         TIMEOUT,           // 좀비 5분 임계 초과
                         SLOT_TIMEOUT,      // slot 3분 만료 (자동 진행이라 드묾)
                         WAIT_TIMEOUT       // 대기 1h 만료, polling X
                       }
                       // 매진(409 SOLD_OUT)·이미예약(409 ALREADY_RESERVED) 은
                       // booking row 자체를 안 만들므로 enum 에 없음
```

### 4.2 Payment 상태머신

기존 v3와 동일: `REQUESTED → AUTHORIZED → CAPTURED / VOIDED / FAILED`

---

## 5. 핵심 흐름 시퀀스 (v4 모델 D)

### 5.1 즉시 결제 (재고 확보)

```
Client          App                    Redis                  DB             MockPG
  │              │                       │                     │                │
  │ POST /booking
  │─────────────►│                       │                     │                │
  │              │ 분산락 SETNX           │                     │                │
  │              │ ──────────────────────►│                    │                │
  │              │                       │                     │                │
  │              │ EVAL conditional_decr_or_wait.lua            │                │
  │              │   → SLOT (재고 9, hold 키 TTL 180s)          │                │
  │              │ ──────────────────────►│                    │                │
  │              │                                              │                │
  │              │ INSERT booking PENDING + payment REQUESTED   │                │
  │              │ ───────────────────────────────────►│        │                │
  │              │                                              │                │
  │              │ POINT HOLD → PG AUTHORIZE                    │                │
  │              │ ─────────────────────────────────────────────►                │
  │              │ PG CAPTURE + POINT COMMIT                    │                │
  │              │ ─────────────────────────────────────────────►                │
  │              │                                              │                │
  │              │ UPDATE booking PAID + DEL hold + outbox       │                │
  │              │                                                              │
  │◄── 200 OK ─────│  { status: "PAID", bookingId, ... }                         │
```

### 5.2 대기열 진입

```
  │ POST /booking
  │─────────────►│                       │                     │                │
  │              │ 분산락 SETNX ✅       │                     │                │
  │              │                       │                     │                │
  │              │ EVAL conditional_decr_or_wait.lua            │                │
  │              │   → WAITLISTED (재고 0, position=5)           │                │
  │              │ ──────────────────────►│                    │                │
  │              │  (ZADD waitlist + HSET wait:token)           │                │
  │              │                                              │                │
  │              │ INSERT booking WAITING (wait_token, enqueued_at)              │
  │              │ ───────────────────────────────────►│                         │
  │              │                                                              │
  │◄── 200 ────────│  { status:"WAITING", waitToken, position:5,                  │
  │                    pollingIntervalMs:2000, estimatedWaitSeconds:10 }          │
```

### 5.3 대기 polling

```
  │ GET /booking/wait/{waitToken}
  │─────────────►│                       │                     │                │
  │              │ EVAL try_promote.lua  — HGET wait:token:{wt} status            │
  │              │   READY → status='PROCESSING' 원자 전환 후 'READY' 반환         │
  │              │   그 외  → 현재 status 그대로 반환                              │
  │              │                                                              │
  │              │ WaitPollingService 의 응답 빌드 (status 별 분기):             │
  │              │   WAITING    → ZRANK waitlist 로 position 계산 + 응답         │
  │              │   READY      → PromotionService.processPayment(@Async) 시작   │
  │              │                + "PROCESSING" 응답 (polling thread 즉시 회수)  │
  │              │   PROCESSING → "처리 중" 응답                                 │
  │              │   PAID       → HGET wait:token bookingId → 최종 응답          │
  │              │   FAILED     → HGET wait:token reason → 422 응답              │
  │              │   NOT_FOUND  → 410 EXPIRED (wait_token TTL 1h 만료)            │
  │              │                                                              │
  │◄── 200 ──────│   { status: "WAITING", position: 3 }         │
  │              │     ... (2~3초마다 반복) ...                  │                │
  │              │   { status: "PROCESSING" }                   │                │
  │              │   { status: "PAID", bookingId: 42 }          │                │
```

### 5.4 대기자 승격 트리거

```
[자리 비는 이유 — 우선순위 순]
  1. 즉시 감지 (main path): BookingService.handlePaymentFailure /
     PromotionService.handleAsyncFailure 가 결제 실패 catch 직후
     stockService.restoreAndPromote 호출 → promo.isPromoted() 시 activate
  2. 좀비 청소 워커 (안전망, 5분 임계): main path가 어떤 이유로 실패한 경우
  3. slot 3분 만료 (자동 진행이라 거의 0): 좀비 청소가 동일 흐름 처리

[승격 흐름 — PromotionService.activate]
  1. EVAL restore_stock_and_promote.lua (StockService.restoreAndPromote 호출)
     - INCR stock + DEL hold
     - ZPOPMIN waitlist:product:{pid} → waitToken
     - DECR stock (다음 자리 차감)
     → "PROMOTED:{waitToken}" 반환
  2. PromotionService.activate(waitToken, productId):
     - booking.markPromoted(slot_token, now)  ← status=PENDING 전환
     - SET slot:{slotToken} (180s TTL) + SET hold:{...} (180s TTL)
     - HSET wait:token status=READY + slotToken + expiresAt
     - outbox(WAIT_PROMOTED) 적재
  3. 다음 polling 의 try_promote.lua 가 READY 발견 → PROCESSING 전환
     → WaitPollingService 가 PromotionService.processPayment 비동기 호출
```

---

## 6. DDL 초안 (v4)

> MySQL 8.4 기준.

> 본 절은 설계 명세. 실제 schema 단일 소스는 [`src/main/resources/schema.sql`](../../src/main/resources/schema.sql) 참조. `ddl-auto: validate` 로 entity ↔ schema 정합 자동 검증.

```sql
-- BOOKING (v4 확장)
CREATE TABLE booking (
  id                BIGINT       PRIMARY KEY AUTO_INCREMENT,
  customer_id       BIGINT       NOT NULL,        -- MySQL reserved word 회피로 user → customer
  product_id        BIGINT       NOT NULL,
  status            ENUM('WAITING','PENDING','PAID','FAILED','EXPIRED') NOT NULL,
  failed_reason     ENUM(... 12종 ...)  NULL,     -- FailureReason.java
  total_amount      BIGINT       NOT NULL,
  idempotency_key   VARCHAR(128) NOT NULL,
  request_hash      VARCHAR(64)  NOT NULL,        -- SHA-256 hex
  -- v4 신규: 대기열 관련
  wait_token        VARCHAR(64)  NULL,            -- 대기 진입 시 발급
  slot_token        VARCHAR(64)  NULL,            -- slot 잡았을 때 발급
  enqueued_at       DATETIME(6)  NULL,
  promoted_at       DATETIME(6)  NULL,
  -- 1인 1상품 제약: WAITING / PENDING / PAID 모두 활성
  active_key        VARCHAR(64)  GENERATED ALWAYS AS (
                       CASE WHEN status IN ('WAITING','PENDING','PAID')
                            THEN CONCAT(customer_id, '-', product_id) END
                     ) STORED,
  created_at        DATETIME(6)  NOT NULL,
  updated_at        DATETIME(6)  NOT NULL,
  paid_at           DATETIME(6)  NULL,
  failed_at         DATETIME(6)  NULL,
  expired_at        DATETIME(6)  NULL,
  UNIQUE KEY uk_booking_idem       (idempotency_key),
  UNIQUE KEY uk_booking_wait_token (wait_token),
  UNIQUE KEY uk_booking_active     (active_key),
  KEY        idx_booking_status_created     (status, created_at),
  KEY        idx_booking_customer_product   (customer_id, product_id)
  -- 외래키 미설정: 운영 성능 + 정합성은 application + 워커로 보장
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- PAYMENT: schema.sql 참조 (uk_payment_pg_idem + uk_payment_booking_method)

-- OUTBOX_EVENT (v4 이벤트 타입 8종)
--   BOOKING_PAID, BOOKING_FAILED, BOOKING_EXPIRED, WAIT_PROMOTED  (알림)
--   COMPENSATION_VOID, COMPENSATION_POINT_RELEASE, COMPENSATION_STOCK_RESTORE,
--   POINT_COMMIT_RETRY                                            (보상 재시도)
-- 자세한 정의 + Phase 1 적재 상태는 docs/miss/error_04.md 참조.

-- INBOX_EVENT: schema.sql 참조 (uk_inbox_source_event)
```

---

## 7. Redis 키 스키마 (v4)

| Key | Type | TTL | 용도 |
|---|---|---|---|
| `stock:product:{pid}` | INT | 영구 | implicit queue: Lua `conditional_decr_or_wait` |
| `hold:product:{pid}:booking:{bid}` | STRING | **180s (3분)** | 선점 보호 — slot 잡힌 booking의 자동 만료 보호 |
| **`waitlist:product:{pid}`** | **Sorted Set** | 영구 | **score=enqueued_at(ms), member=waitToken**. ZADD/ZRANK/ZPOPMIN |
| **`wait:token:{waitToken}`** | **Hash** | **1h** | **{status, bookingId, customerId, productId, enqueuedAt, slotToken, expiresAt, reason}**. polling 대상 |
| **`slot:{slotToken}`** | STRING (waitToken) | **180s (3분)** | **자리 점유 표시 — 만료 시 자동 해제 + 워커가 다음 승격** |
| `idem:{idempotency-key}` | STRING (JSON) | 24h | 응답 재생 캐시 |
| `lock:booking:customer:{cid}:prod:{pid}` | SETNX | 5s | PG·대기 진입 중복 방어 |
| `lock:reconcile:{taskName}` | SETNX | 60s | Reconciliation 단일 실행 (`zombie` / `verify` / `wait_cleanup`) |

> Outbox 워커는 **MySQL `SELECT ... FOR UPDATE SKIP LOCKED`** 로 멀티 노드 경합 처리 — 분산락 불필요 (DECISIONS §10.1).

### 7.1 Lua 스크립트 (v4)

**`conditional_decr_or_wait.lua`** — 3-way 분기 원자 처리:

```lua
-- KEYS[1] = stock:product:{productId}
-- KEYS[2] = waitlist:product:{productId}
-- KEYS[3] = hold:product:{productId}:booking:{tmpUuid or bookingId}
-- ARGV[1] = waitToken (or tmpUuid)
-- ARGV[2] = hold TTL seconds (180)
-- ARGV[3] = waitlist max size (30)
-- ARGV[4] = enqueued_at ms (timestamp)
--
-- 반환:
--   "SLOT:{remaining}"       — 재고 확보, slot 잡음
--   "WAITLIST:{position}"    — 대기 진입, position은 1-based
--   "FULL"                   — 매진 + 대기열 가득

local stock = tonumber(redis.call('GET', KEYS[1]))
if stock ~= nil and stock > 0 then
    -- 재고 확보 분기
    local after = redis.call('DECR', KEYS[1])
    redis.call('SET', KEYS[3], ARGV[1], 'EX', tonumber(ARGV[2]))
    return 'SLOT:' .. tostring(after)
end

-- 대기열 검사
local wlSize = redis.call('ZCARD', KEYS[2])
if wlSize >= tonumber(ARGV[3]) then
    return 'FULL'
end

-- 대기 진입
redis.call('ZADD', KEYS[2], tonumber(ARGV[4]), ARGV[1])
local position = redis.call('ZRANK', KEYS[2], ARGV[1])
return 'WAITLIST:' .. tostring(position + 1)   -- 1-based
```

**`restore_stock_and_promote.lua`** — 보상 + 대기 1번 승격:

```lua
-- KEYS[1] = stock:product:{productId}
-- KEYS[2] = hold:product:{productId}:booking:{holdHolder}  -- waitToken 또는 booking_id
-- KEYS[3] = waitlist:product:{productId}
-- ARGV[1] = stock_total (OVERFLOW 가드)
--
-- 반환:
--   "RESTORED"               — 재고 복구만 (대기열 비어있음)
--   "PROMOTED:{waitToken}"   — 재고 복구 + 대기 1번 승격됨
--   "OVERFLOW"               — stock_total 초과 (이상 상황) — DECR 롤백 + DEL hold

local after = redis.call('INCR', KEYS[1])
if ARGV[1] ~= nil and after > tonumber(ARGV[1]) then
    redis.call('DECR', KEYS[1])
    return 'OVERFLOW'
end
redis.call('DEL', KEYS[2])

-- 대기열 확인
local popped = redis.call('ZPOPMIN', KEYS[3], 1)
if #popped == 0 then
    return 'RESTORED'
end
local waitToken = popped[1]
-- 다시 DECR해서 그 자리를 대기자에게 할당
redis.call('DECR', KEYS[1])
-- 새 hold 키는 호출자가 SET (slot_token 발급 시점에)
return 'PROMOTED:' .. waitToken
```

**`try_promote.lua`** — polling 시 READY 발견 시 PROCESSING으로 원자 전환:

```lua
-- KEYS[1] = wait:token:{waitToken}
-- ARGV[1] = new status (PROCESSING)
--
-- 반환:
--   현재 상태 ("WAITING", "READY"→이때 PROCESSING으로 전환, "PROCESSING", "PAID", "FAILED")

local status = redis.call('HGET', KEYS[1], 'status')
if status == 'READY' then
    redis.call('HSET', KEYS[1], 'status', ARGV[1])
    return 'READY'   -- 호출자가 결제 비동기 시작
end
return status or 'NOT_FOUND'
```

**`safe_unlock.lua`** — v3와 동일.

---

## 8. Mock PG 인터페이스

v3와 동일: `/pg/authorize`, `/pg/capture`, `/pg/void`, `/pg/transactions/{key}`.

---

## 9. 정합성 방어선 종합 (v4)

| 자원 | 1차 (런타임) | 2차 (DB 제약) | 3차 (정합성 잡) |
|---|---|---|---|
| **재고** | Lua `conditional_decr_or_wait` (implicit queue + 3-way 분기) | `booking.PAID` 집계 ≤ `stock_total` | hold/slot TTL + Reconciliation |
| **대기열 정합성** | Lua 원자 ZADD/ZPOPMIN | `booking.wait_token UNIQUE` | wait:token TTL 1h, 만료 정리 워커 |
| **선점 만료** | hold 키 TTL 180s + slot 키 TTL 180s | — | 좀비 청소 워커 (1분) + 즉시 승격 |
| **포인트** | `CustomerPoint.deduct()` application 검증 + `@Version` 낙관락 | `point_transaction.reference_key UNIQUE` | (합산 검증 워커는 향후 과제 — Pt1 보류) |
| **외부 멱등** | Redis `idem:{key}` + request_hash SHA-256 | `booking.idempotency_key UNIQUE` | — |
| **PG 호출 멱등** | 분산락 + Mock PG 캐시 | `payment.pg_idempotency_key UNIQUE` | Outbox COMPENSATION_* (Phase 1 적재) |
| **1인1상품** | application `existsByCustomerIdAndProductIdAndStatusIn` 사전 체크 | `booking.active_key UNIQUE` (generated, WAITING/PENDING/PAID 활성) | — |

---

## 10. v4에서 새로 다뤄야 할 시나리오

| 시나리오 | 처리 |
|---|---|
| 대기 진입 후 사용자가 polling 안 함 | wait:token TTL 1h 만료 → cleanupExpiredWaitlist 워커가 booking EXPIRED + outbox(BOOKING_EXPIRED) |
| 대기자 승격 후 결제 비동기 처리 도중 노드 사망 | Reconciliation.cleanupZombies: payment.status (AUTHORIZED/REQUESTED) 로 진행 단계 식별, compensate + restoreAndPromote + finalizeFailed |
| 같은 customer·product 가 대기 중 재시도 | application existsBy 사전 체크 → 409 ALREADY_RESERVED ("이미 진행 중이거나 완료된 예약이 있습니다."). 기존 wait_token 은 별도 GET /booking/{id} 또는 polling 으로 조회 |
| 대기 중 wait_token 탈취 시 polling | wait_token 자체가 16자 UUID (2^64) — 비밀번호 역할. **현재 customerId 검증 미구현** (인증 평가 범위 외, 명세 §5). 운영 시 토큰 + customerId 매칭 검증 권장 |
| 대기열 정합성 (ZADD 후 노드 사망) | Lua 원자 ZADD/ZRANK 라 partial 상태 없음. wait_token 만 발급 + booking row 없는 경우는 cleanupExpiredWaitlist 가 TTL 만료 후 정리 |

---

## 11. 변경 이력

| 회차 | 주요 변경 |
|---|---|
| v1 | Redis SoT, Booking 5-state, 모델 B |
| v2 | 모델 A, Booking 3-state, idempotency_key 테이블 제거, Auth/Capture/Void, Outbox |
| v3 | 모델 C — DECR을 PG 호출 *전*으로, hold 키, implicit queue 명명. PG 호출량 99% 감소 |
| **v4 (현재)** | **모델 D — 대기열 시스템(30명) + 자동 진행. Booking 상태 5개로 확장(WAITING/PENDING/PAID/FAILED/EXPIRED). 새 Redis 키 4종(waitlist/wait:token/slot/hold-180s), Lua 신규 3종(conditional_decr_or_wait / restore_stock_and_promote / try_promote, safe_unlock 은 v3 부터) — 총 4종. 결제 실패자 자리 사장 문제 해소. polling 자체가 결제 트리거.** |
