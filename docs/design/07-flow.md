# 11. 사용자 요청 플로우 — 1000 TPS 시나리오 (v4)

> **단계 목적**: v4 모델 D(선점 + 대기열 + 자동 진행)에서 1000 TPS 스파이크 상황의 사용자 요청 처리 흐름.
>
> 기준: 재고 10 / 대기열 30 / slot 3분 / 1000명 동시 진입 / 모델 D + Redis implicit queue.

---

## 0. 기본 가정

```
상품:       재고 10, 가격 50,000원
대기열:     30명 한도 (waitlist:product:{pid})
slot:       3분 TTL (180s)
사용자:     1000명 (서로 다른 customer_id)
시점:       00:00:00.000 ~ 00:00:01.000 (1초 동안 분산 도착)
폴링:       대기자가 2~3초마다 GET /booking/wait/{waitToken}
```

**처리 시간**:
- 즉시 SLOT 성공 (PAID): ~174ms
- 대기 진입 (WAITING): ~10ms
- 매진 즉시 응답 (SOLD_OUT): ~5ms
- polling 1회: ~3ms
- 비동기 결제 (승격 후 폴링이 트리거): ~174ms (백그라운드)

---

## 1. 1초 시간선 (v4)

```
T(ms)
   0  ── 1000명 도착 시작
        │
   1~10 ── 요청 #1~#10  ─→ Lua "SLOT:9~0"     ─→ booking 진행 (모델 C 흐름)
        │                                       (각 ~174ms 후 PAID 응답)
        │
  11~40 ── 요청 #11~#40 ─→ Lua "WAITLIST:1~30" ─→ 200 WAITING + waitToken
        │                                       (각 ~10ms)
        │
  41~50 ── 요청 #41~#50 ─→ Lua "FULL"          ─→ 409 SOLD_OUT
        │                                       (각 ~5ms)
        │  ...
1000~1010 ── 요청 #51~#1000 도착하면서 차례로 "FULL" 응답
        │
  ~174 ── 요청 #1 결제 완료 → 200 OK PAID
        │
[대기 polling]
   ~2~3초 후 ── 대기자 #11~#40이 GET /booking/wait/{wt} polling 시작
        │       → 모두 WAITING + position 응답
        │
[가능한 승격]
  요청 #1~#10 중 일부 결제 실패 (예: 카드 거절) 시:
    → restore_stock_and_promote.lua → 대기 1번 승격
    → wait:token READY
    → 그 사용자의 다음 polling이 READY 발견 → 비동기 결제 시작
    → polling 즉시 PROCESSING 응답
    → 다음 polling에서 PAID 또는 FAILED
```

**핵심**:
- 첫 10명만 PG 호출 (~10건)
- 11~40명은 대기 진입 (PG 호출 0)
- 41~1000명은 매진 (PG 호출 0)
- 결제 실패 시 대기자가 즉시 양도받음 → **자원 사장 없음**

---

## 2. 사용자 입장 시나리오 매트릭스 (v4)

각 사용자 요청이 맞이하는 결말:

| # | 시나리오 | 응답 | 소요 | 1000명 중 |
|:-:|---|---|---|:-:|
| **S1** | 즉시 결제 성공 | 200 PAID | ~174ms | 10명 (~+승격 0~ 명) |
| **S2** | 대기 진입 | 200 WAITING + waitToken | ~10ms | 30명 |
| **S3** | 매진 (대기열 가득) | 409 SOLD_OUT + Retry-After | ~5ms | 960명 |
| S4 | 1인1상품 위반 | 409 ALREADY_RESERVED | ~15ms | (해당 시) |
| S5 | 더블 클릭 | 409 IN_PROGRESS | ~5ms | (해당 시) |
| S6 | 멱등 재시도 | 캐시 응답 재생 | ~3ms | (해당 시) |
| S7 | Key + 다른 body | 422 IDEMPOTENCY_KEY_REUSED | ~5ms | (해당 시) |
| S8 | 포인트 잔액 부족 | 422 INSUFFICIENT_POINT | ~15ms | (해당 시) |
| S9 | 카드 거절 | 422 PAYMENT_DECLINED | ~150ms | (해당 시) |
| S10 | PG 장애 (CB OPEN / Bulkhead) | 503 PG_UNAVAILABLE | ~10ms | (해당 시) |
| **S11** **(v4)** | **대기 중 polling — WAITING** | **200 WAITING + position** | ~3ms | 30명, 2~3초마다 |
| **S12** **(v4)** | **승격되어 PROCESSING** | **200 PROCESSING** (결제 비동기 시작됨) | ~3ms | (승격된 사람) |
| **S13** **(v4)** | **승격 후 결제 PAID** | **200 PAID + bookingId** | ~3ms | (승격된 사람) |
| **S14** **(v4)** | **승격 후 결제 FAILED** | **422 + reason** | ~3ms | (드물게) |
| **S15** **(v4)** | **대기 1h 만료 (polling 안 함)** | **410 EXPIRED** | ~3ms | (해당 시) |

---

## 3. 시나리오별 상세 흐름 (v4 핵심만)

### S2 — 대기 진입 (v4 신규)

```
T+0ms    POST /booking { customerId, productId, payments: [POINT 5000, CARD 45000] }

T+1ms    App  ┌─ Redis GET idem:{key} → 없음
              ├─ 분산락 SETNX ✅
              ├─ EVAL conditional_decr_or_wait.lua
              │   - GET stock = 0
              │   - ZCARD waitlist = 5 (현재)
              │   - ZADD waitlist:product:10 {now} {tmpWaitToken}
              │   - ZRANK = 5 → position = 6
              │   - 반환: "WAITLIST:6"
              │
T+5ms         ├─ DB tx1 INSERT booking (status=WAITING, wait_token, enqueued_at)
              ├─ HSET wait:token:{wt} {status:WAITING, bookingId,
              │                        customerId, productId, enqueuedAt}
              │   EX 3600 (1h)
              │
T+8ms         ├─ Redis idem:{key} = {200 WAITING + body} EX 86400
              ├─ 락 해제

T+10ms   사용자에게 응답
          200 OK
          {
            "status": "WAITING",
            "waitToken": "wt_abc123",
            "position": 6,
            "pollingIntervalMs": 2000,
            "estimatedWaitSeconds": 60     ← 예상치
          }
```

### S11 — 대기 중 polling (WAITING)

```
T+2000ms (사용자가 2초 뒤 polling)
         GET /booking/wait/wt_abc123

T+2001ms App  ┌─ EVAL try_promote.lua
              │   - HGET wait:token:wt_abc123 status → "WAITING"
              │   - 반환: "WAITING"
              │
T+2003ms      ├─ ZRANK waitlist:product:10 wt_abc123 → 5
              │   → position = 6 (1-based)
              │
              └─ 응답

T+2003ms 사용자에게 응답
          200 OK
          {
            "status": "WAITING",
            "position": 6,
            "pollingIntervalMs": 2000
          }
```

### S12·S13 — 승격되어 결제 진행 (v4 신규)

```
[승격 시점] T+150ms
         (사용자 A의 결제 실패로 대기 1번이 승격됨)
         
         App  ┌─ EVAL restore_stock_and_promote.lua
              │   - INCR stock 0→1, DEL hold:p:42
              │   - ZPOPMIN waitlist → "wt_xyz"
              │   - DECR stock 1→0 (대기자가 자리 차지)
              │   - 반환: "PROMOTED:wt_xyz"
              ├─ slotToken = "slot_999" 발급
              ├─ SET slot:slot_999 = wt_xyz EX 180s
              ├─ SET hold:product:10:booking:{newBookingId} EX 180s
              ├─ UPDATE booking(승격대상) SET status=PENDING, slot_token, promoted_at
              ├─ HSET wait:token:wt_xyz status=READY, slot_token, expiresAt
              │   (TTL 1h 그대로)

[승격 사용자의 다음 polling] T+150ms~T+~ms (사용자 2초 폴링 주기 안)
         GET /booking/wait/wt_xyz

         App  ┌─ stockService.tryPromote(wt_xyz) → "READY"
              │   (try_promote.lua: HGET status=READY → HSET status=PROCESSING 원자)
              ├─ WaitPollingService 가 promotionService.processPayment(wt_xyz) 호출
              │     ← @Async("paymentExecutor") 어노테이션이 paymentExecutor 스레드로 위임
              │     (백그라운드: authorize → capture → finalizePaid 흐름)
              │
              └─ 즉시 응답 (Tomcat thread 회수)

         사용자에게 응답
          200 OK { "status": "PROCESSING" }

[비동기 결제 진행] T+150ms ~ T+324ms (백그라운드)
         POINT HOLD → PG AUTHORIZE → PG CAPTURE → POINT COMMIT
         booking UPDATE status=PAID, paid_at
         DEL hold + outbox(BOOKING_PAID)
         HSET wait:token:wt_xyz status=PAID + bookingId={newId}

[다음 polling] T+~2000ms
         GET /booking/wait/wt_xyz

         App  ┌─ EVAL try_promote.lua → "PAID"
              ├─ HGET wait:token:wt_xyz bookingId
              │
              └─ 응답

         사용자에게 응답
          200 OK
          {
            "status": "PAID",
            "bookingId": 99,
            "paidAt": "..."
          }
```

### S15 — 대기 만료 (v4 신규)

```
사용자가 대기 진입 후 polling 안 함 (브라우저 닫음 등)

T+1h   wait:token:{wt} TTL 만료 (Redis 자동)
       대기열 만료 정리 워커 (5분 주기)
       ├─ booking WAITING + enqueued_at < 1h 발견
       ├─ ZREM waitlist:product:{pid} {waitToken}
       └─ UPDATE booking SET status=EXPIRED, expired_at, expired_reason=WAIT_TIMEOUT

사용자가 1h 후 polling 시도 시:
   GET /booking/wait/{waitToken}
   → wait:token Hash 없음
   → 410 Gone { "reason": "EXPIRED" }
```

---

## 4. 1000 TPS 자원 사용 합계 (v4)

```
1초간:

Redis ops:
  - SETNX (lock):              1000 ops
  - EVAL conditional_decr_or_wait: 1000 ops
  - SET idem:                  1000 ops (성공 응답)
  - HSET wait:token:           30 ops (대기 진입자만)
  - safe_unlock:               1000 ops
  - polling EVAL try_promote:  ~15 ops/sec (30명 × 2초 주기)
  ─────────────────────────────────────
  총: ~4,045 ops/sec      ← Redis 100k ops/sec의 4%

DB:
  - booking INSERT (PENDING): 10
  - booking INSERT (WAITING): 30
  - payment INSERT:           20 (성공자만)
  - UPDATE booking 등:        ~40 ops
  ─────────────────────────────────────
  총: ~100~150 ops/sec    ← MySQL 여유

PG (Mock):
  - AUTHORIZE: 10회 + 승격자 (소수, ~2~5)
  - CAPTURE:   동일
  - VOID:      ~0~소수 (실패자만)
  ─────────────────────────────────────
  총: ~25~40회/sec        ← 모델 A 1990회 대비 98%+ 절감

Tomcat thread:
  - 평균 동시 점유: 50~100
  - 가용 800 (2노드 × 400)
  ─────────────────────────────────────
  여유율: 8~16배

paymentExecutor (v4 비동기 풀):
  - 평균 동시 점유: ~5
  - 가용 50 (max pool)
  ─────────────────────────────────────
  여유율: 10배+
```

---

## 5. 시나리오별 식별·디버깅 가이드 (v4)

### 로그 패턴 (v4 확장)

실제 코드에서 출력되는 핵심 로그 메시지 (`grep -r "log.info\|log.warn"`):

```
[INFO]  BOOKING_PAID bookingId=42 customerId=1 productId=10            (BookingService)
[INFO]  BOOKING_WAITLIST customerId=12 productId=10 waitToken=wt_abc position=3
[WARN]  BOOKING_FAILED bookingId=99 reason=PG_TIMEOUT cause=...        (booking/promotion 공통)
[INFO]  WAIT_PROMOTED waitToken=wt_xyz bookingId=99 slotToken=slot_999 (PromotionService.activate)
[INFO]  ASYNC_PAYMENT_COMPLETE waitToken=wt_xyz bookingId=99 status=PAID
[WARN]  ASYNC_PAYMENT_FAILED waitToken=wt_xyz bookingId=99 reason=...
[INFO]  ZOMBIE_CLEANED bookingId=42                                    (ReconciliationWorker)
[INFO]  WAIT_EXPIRED bookingId=43 waitToken=wt_abc
[ERROR] CONSISTENCY_VIOLATION productId=10 paid=11 pending=0 total=10
[WARN]  COMPENSATION_FAILED method=CARD authRef=auth_... bookingId=42  (orchestrator)
[INFO]  OUTBOX_EVENT type=BOOKING_PAID aggregateId=42 payload=...      (OutboxDispatcher)
[WARN]  OUTBOX_COMPENSATION_PENDING type=COMPENSATION_VOID ...         (Phase 2 미구현)
```

### 메트릭

```
booking_total{result="paid"}                10/sec
booking_total{result="waiting"}              30/sec
booking_total{result="sold_out_full"}        960/sec
wait_promotion_total                         α/sec
wait_polling_total                           ~15/sec
async_payment_active                         gauge
```

### Redis 검증

```bash
redis-cli GET stock:product:10                              # = "0" (재고 다 잡힘)
redis-cli ZCARD waitlist:product:10                         # = 30 (대기열 가득)
redis-cli ZRANGE waitlist:product:10 0 -1 WITHSCORES        # 대기 순서·진입 시각
redis-cli HGETALL wait:token:wt_abc                         # 대기자 상태
redis-cli KEYS "slot:*"                                     # 활성 slot 수
redis-cli KEYS "hold:product:10:*"                          # 활성 hold 수
```

### SQL 검증

```sql
-- 상태 분포
SELECT status, COUNT(*) FROM booking WHERE product_id=10 GROUP BY status;
-- 예상: PAID 10, WAITING 30 (또는 그 미만, 승격 진행 중)

-- 좀비 후보
SELECT * FROM booking
WHERE status='PENDING' AND created_at < NOW() - INTERVAL 5 MINUTE;

-- 대기 만료 후보
SELECT * FROM booking
WHERE status='WAITING' AND enqueued_at < NOW() - INTERVAL 1 HOUR;

-- 정합성
SELECT 
  p.stock_total,
  (SELECT COUNT(*) FROM booking WHERE product_id=p.id AND status='PAID')    AS paid,
  (SELECT COUNT(*) FROM booking WHERE product_id=p.id AND status='PENDING') AS pending,
  (SELECT COUNT(*) FROM booking WHERE product_id=p.id AND status='WAITING') AS waiting
FROM product p WHERE id=10;
-- paid + pending ≤ stock_total 이어야 정상
-- waiting ≤ 30
```

---

## 6. "대기"가 일어나는 6지점 (v4)

| 형태 | 위치 | 우리 시스템 적용 |
|---|---|---|
| 1. Redis Lua 직렬화 | 단일 스레드 처리 | implicit queue, 체감 없음 |
| 2. Tomcat accept queue | 동시 요청 > thread max 시 | 1000 TPS 안에선 발생 X |
| 3. PG Bulkhead | 동시 PG 호출 > 100 시 | PG 호출 ~12건이라 발생 X |
| 4. Retry-After (409 IN_PROGRESS) | 락 충돌 시 | 사용자 측 명시적 대기 |
| **5. 대기열 (waitlist)** | **재고 0 + 대기열 < 30** | **30명 명시적 대기, polling** |
| **6. paymentExecutor 큐** | **비동기 결제 대기** | **승격된 결제가 thread 대기 중** |

---

## 7. 모델 C 대비 차이 (v4)

| 항목 | 모델 C | 모델 D (v4) ⭐ |
|---|---|---|
| 1000명 처리 | 10 PAID + 990 SOLD_OUT | **10 PAID + 30 WAITING + 960 SOLD_OUT** |
| 결제 실패자 자리 | **사장됨** ⚠️ | **대기 1번에게 양도** ✅ |
| 대기 UX | 없음 | "N번째, 약 60초 후 차례" |
| API | 2개 | 3개 (`GET wait` 추가) |
| Redis 키 | 5종 | **8종** (waitlist, wait:token, slot 추가) |
| Lua 스크립트 | 3종 | **4종** (`restore_stock_and_promote` 추가) |
| Booking 상태 | 3 | **5** (WAITING, EXPIRED 추가) |
| 노드 사망 시나리오 | 6 | **11** |
| 비동기 처리 | 없음 | **paymentExecutor (승격 후 결제)** |

---

## 8. 부하 테스트 예상 결과 (v4)

실제 부하 스크립트는 `load/` 에 통합 — vegeta JSON Lines targets + 검증.

```bash
brew install vegeta
bash load/run.sh                        # 기본: 1000 TPS × 1s × 1000 req
RATE=500 DURATION=2s bash load/run.sh   # 커스텀

예상 (`load/verify.sh` 검증 기준):
  Requests       [total, rate]      1000, 1000.00
  Status Codes   [200(PAID): 10, 200(WAITING): 30, 409(SOLD_OUT): ~960]
  → 검증: PAID=10, WAITING=30, stock=0, waitlist=30, payment.CAPTURED=10
```

---

## 9. 사용자 체감 요약 — 1000명 입장 (v4)

```
┌─────────────────────────────────────────────────────────────┐
│  10명 (즉시 결제 성공자)                                      │
│  - 클릭 → "예약 완료" 페이지 (~200ms 체감)                    │
│  - 자연스러운 UX                                              │
├─────────────────────────────────────────────────────────────┤
│  30명 (대기열 진입자)                                          │
│  - 클릭 → "대기 N번째, 약 60초 후 차례" 표시 (~10ms 체감)      │
│  - 자동 polling으로 진행 상황 확인                              │
│  - 자리 나면 자동으로 결제 → "결제 완료" 페이지                  │
│  - "혹시 자리 날까?" 기대감 있는 UX                              │
├─────────────────────────────────────────────────────────────┤
│  960명 (매진 즉시 안내)                                       │
│  - 클릭 → "매진. 1분 후 자리 가능성 있으니 재시도 권장"          │
│    (~5ms 체감, Retry-After: 60)                              │
│  - 카드 한도 안 잡힘, 환불 경험 없음                            │
├─────────────────────────────────────────────────────────────┤
│  ~소수명 (재시도·중복 시도자)                                  │
│  - 더블 클릭 → 409 IN_PROGRESS                                │
│  - 같은 키 재시도 → 캐시 응답                                   │
│  - 다른 키 같은 상품 → 409 ALREADY_RESERVED                   │
├─────────────────────────────────────────────────────────────┤
│  ~0~소수명 (결제 실패)                                        │
│  - 카드 거절 → 422 PAYMENT_DECLINED                           │
│  - 즉시 대기 1번에게 양도 (자원 손실 없음) ⚡                    │
└─────────────────────────────────────────────────────────────┘
```

---

## 10. 정리

본 시스템 v4 모델 D는 1000 TPS에서:
- 10명 즉시 결제 성공
- 30명 명시적 대기 (자동 진행, polling으로 결과 통지)
- 960명 즉시 매진 응답 (PG·POINT 호출 0회)
- **결제 실패자 자리는 대기 1번에게 즉시 양도 — 자원 사장 없음**
- PG 호출량 ~25~40회 (모델 A 1990회 대비 98%+ 절감)

세부 처리 흐름은 `01-domain.md`, `02-concurrency.md`, `04-payment.md`, `05-resilience.md` 참조.
