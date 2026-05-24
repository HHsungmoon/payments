# 04. 결제 모듈 확장성 (v4)

> **단계 목적**: PaymentStrategy + Orchestrator + Validator. 대기열 도입에 따른 비동기 결제 진행.
>
> **v4 변경 요지**: 결제 진행 경로가 **동기**(즉시 SLOT) + **비동기**(대기 후 승격) 두 가지로 갈라짐. PaymentStrategy / Orchestrator 자체는 그대로 재사용. 비동기는 `@Async` + `paymentExecutor`로 백그라운드 실행하고 결과는 `wait:token` Hash에 업데이트.

---

## 1. 핵심 결정 요약

| ID | 결정 | 근거 |
|---:|---|---|
| **P1** | PaymentMethod 2-카테고리 (MAIN/SUPPLEMENT) | 명세 충족 |
| **P2** | 호출 순서: SUPPLEMENT 먼저 → MAIN 나중 | 빠른 실패 우선 |
| **P3** | PaymentStrategy 인터페이스 | OCP |
| **P4** | PaymentOrchestrator가 흐름 조립 | 추상화 |
| **P5** | payment row 사전 INSERT | 멱등 키 미리 확보 |
| **P6** | DB tx와 외부 호출 분리 | 1000 TPS 흡수 |
| **P7** | POINT 가상 매핑 (HOLD/COMMIT/RELEASE) | 인터페이스 통일 |
| **P8** | `failed_reason` enum 정리 | 명세 "한도 초과 등" |
| **P9 (v4)** | **결제 진행이 두 경로**: ① 동기 (즉시 SLOT) ② 비동기 (대기 승격) | 대기열 자동 진행 위해 |
| **P10 (v4)** | **`@Async paymentExecutor`** 사용, 별도 ThreadPoolTaskExecutor (core 20 / max 50). polling thread를 막지 않음. | polling 효율, cascading 차단 |
| **P11 (v4)** | **비동기 결제 결과는 wait:token Hash에 업데이트** (status, bookingId, failed_reason). 다음 polling이 읽어감. | polling이 완료 신호 |

---

## 2. PaymentMethod 카테고리 모델 (v3와 동일)

```java
public enum PaymentMethod {
    CARD  (Category.MAIN),
    YPAY  (Category.MAIN),
    POINT (Category.SUPPLEMENT);

    public enum Category { MAIN, SUPPLEMENT }
}
```

규칙: MAIN ≤ 1 · SUPPLEMENT ≤ 1 · 최소 1개 · Σ amount = total_amount · method 중복 X.

---

## 3. 클래스 구조 (v4)

```
┌────────────────────────────────────────────────────────────┐
│  BookingService                                            │
│  - book(req, idemHeader)                                   │
│      lock = lockService.tryAcquireBookingLock(customerId, productId);
│      try {
│        doBook(req, ...):
│          - existsByCustomerIdAndProductIdAndStatusIn  ← 1인1자리
│          - validator.validate(combination)
│          - stockService.tryReserveOrWait
│            SLOT     → handleSlot     (동기 결제)
│            WAITLIST → handleWaitlist (대기 진입)
│            FULL     → soldOut
│      } finally { lockService.release(...); }
└────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌────────────────────────────────────────────────────────────┐
│  BookingPersistence  (DB tx 단위 분리 — self-invocation 회피)│
│  - @Transactional insertWaitingWithPayments / insertPending │
│  - @Transactional markPaymentsAuthorized / finalizePaid     │
│  - @Transactional finalizeFailed / markExpired              │
└────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────┐
│  PaymentCombinationValidator                                │
└────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────┐
│  PaymentOrchestrator                                       │
│  - Map<PaymentMethod, PaymentStrategy>                     │
│  - authorize / capture / compensate                        │
│  - compensate 실패 시 outbox COMPENSATION_VOID/POINT_RELEASE│
└────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────┐
│  PaymentStrategy (strategy/) — Card / Ypay / Point      │
│  return outcome/ records: AuthOutcome / CaptureOutcome / │
│                            VoidOutcome                    │
└──────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────┐
│  PromotionService                                          │
│  - @Transactional activate(waitToken, productId)            │
│      slot/hold 키 SET + booking PENDING 전환 +              │
│      wait:token READY + outbox(WAIT_PROMOTED)               │
│  - @Async("paymentExecutor") processPayment(waitToken)     │
│      authorize → capture → finalizePaid                    │
│      실패 시 handleAsyncFailure (compensate + restore + outbox)│
└────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────┐
│  WaitPollingService                                        │
│  - poll(waitToken)                                          │
│      stockService.tryPromote(waitToken) → status            │
│      READY  → promotionService.processPayment(waitToken)    │
│               즉시 PROCESSING 응답 (polling thread 회수)     │
│      WAITING/PROCESSING/PAID/FAILED/NOT_FOUND → 분기 응답   │
└────────────────────────────────────────────────────────────┘
```

---

## 4. 호출 순서 (v4)

### 4.1 동기 흐름 (즉시 SLOT, 모델 C와 동일)

```
[0] BookingService 진입 + 분산락 SETNX
[1] EVAL conditional_decr_or_wait.lua → "SLOT:9"
[2] DB tx1: INSERT booking PENDING + payment(POINT, CARD) REQUESTED
[3] PaymentOrchestrator.authorize() — SUPPLEMENT 먼저
    ├ POINT HOLD → AUTHORIZED
    └ PG AUTHORIZE → AUTHORIZED
[4] PaymentOrchestrator.capture()
    ├ PG CAPTURE → CAPTURED
    └ POINT COMMIT → CAPTURED
[5] DB tx3: booking PAID + DEL hold + outbox(BOOKING_PAID)
[6] 락 해제 + idem 캐시

→ 200 OK PAID
```

### 4.2 비동기 흐름 (대기 → 승격 → 결제, v4 신규)

```
[승격 시점] (워커 또는 동기 흐름 실패 시)
   EVAL restore_stock_and_promote.lua → "PROMOTED:wt_abc"
   slot_token 발급, SET slot:slot_xxx EX 180s
   SET hold:product:{pid}:booking:{newBid} EX 180s
   UPDATE booking(승격대상) status=PENDING + slot_token + promoted_at
   HSET wait:token:wt_abc status=READY + slot_token + expiresAt
   
[다음 polling] GET /booking/wait/wt_abc
   stockService.tryPromote(waitToken) → "READY"
   try_promote.lua 가 status 를 PROCESSING 으로 원자 전환
   WaitPollingService 가 promotionService.processPayment(waitToken) 호출
     ← @Async("paymentExecutor") 어노테이션이 paymentExecutor 스레드로 위임
     → 동기 흐름과 같은 authorize → capture → finalizePaid
     → wait:token Hash update: status=PAID + bookingId
       또는 handleAsyncFailure: status=FAILED + reason + outbox(BOOKING_FAILED)
   polling 즉시 응답: 200 PROCESSING (Tomcat thread 회수)

[다음 polling] GET /booking/wait/wt_abc
   EVAL try_promote.lua → "PAID" (또는 "FAILED")
   HGET wait:token:wt_abc bookingId
   → 200 PAID + bookingId
```

---

## 5. 보상 매트릭스 (v4 — 동기/비동기 통합)

각 단계 실패 시 보상 + **대기자 승격까지**:

| 실패 지점 | 보상 동작 | 비고 |
|---|---|---|
| [1] DECR 실패 (FULL) | 없음 | — |
| [3-1] POINT HOLD 실패 (SUPPLEMENT 먼저) | authorized=[] (compensate no-op) + `restore_stock_and_promote` | 대기 1번 승격 |
| [3-2] CARD/YPAY AUTHORIZE 실패 (MAIN) | orchestrator.compensate (POINT RELEASE) + `restore_stock_and_promote` | 대기 1번 승격. compensate 실패 시 outbox COMPENSATION_POINT_RELEASE |
| [4-1] MAIN CAPTURE 실패 | orchestrator.compensate (POINT RELEASE + voidAuth) + `restore_stock_and_promote` → booking FAILED + outbox(BOOKING_FAILED) + outbox(POINT_COMMIT_RETRY)\* | 대기 1번 승격 |
| [4-2] POINT COMMIT 실패 (SUPPLEMENT 나중) | 동일 (모든 capture catch 동일 흐름) | error_03 — 이미 CAPTURED 된 MAIN voidAuth 시 mock-pg 409, outbox COMPENSATION_VOID 적재 |

\* POINT 가 섞여 있을 때만 적재 (POINT 가 없는 결제는 무관)

**핵심**: 모든 보상 흐름이 `restore_stock_and_promote.lua` 한 줄로 끝남. Lua가 대기열 비어 있으면 "RESTORED" 반환, 있으면 "PROMOTED:{waitToken}" 반환. 호출자가 분기 처리.

---

## 6. 트랜잭션 경계 (v4)

### 6.1 동기 흐름 (즉시 SLOT) — v3와 동일

```
[0] 분산락 SETNX (Redis, ~1ms)
[1] Redis Lua DECR + hold (~1ms)

┌─ DB tx1 (booking + payment INSERT, ~5ms) ───┐
│  INSERT booking PENDING                       │
│  INSERT payment(POINT, REQUESTED)             │
│  INSERT payment(CARD, REQUESTED)              │
└───────────────────────────────────────────────┘
   ↓ (tx 밖)
[POINT HOLD] [PG AUTHORIZE]
   ↓
┌─ DB tx2 (payment AUTHORIZED, ~3ms) ──────────┐
└───────────────────────────────────────────────┘
   ↓
[PG CAPTURE] [POINT COMMIT]
   ↓
┌─ DB tx3 (booking PAID + outbox, ~5ms) ───────┐
└───────────────────────────────────────────────┘
[5] Redis DEL hold + 락 해제 + idem 캐시 (~2ms)
```

### 6.2 대기 진입 흐름 (WAITING) — v4 신규

```
[0] 분산락 SETNX
[1] Redis Lua → "WAITLIST:5"

┌─ DB tx1 (booking WAITING INSERT, ~5ms) ──────┐
│  INSERT booking (status=WAITING,             │
│                  wait_token, enqueued_at)    │
└───────────────────────────────────────────────┘
   ↓
[2] Redis HSET wait:token:{wt} (~1ms)
[3] 락 해제 + idem 캐시 (~2ms)

→ 200 WAITING + waitToken

총 ~10ms (DB tx 1번만, 결제 흐름 없음)
```

### 6.3 비동기 결제 (승격 후) — v4 신규

```
[승격 시점, 호출자가 워커 또는 동기 흐름 실패자]
┌─ Redis + DB tx 1 (~5ms) ─────────────────────┐
│  Redis: restore_stock_and_promote → PROMOTED │
│  Redis: SET slot, hold                       │
│  Redis: HSET wait:token READY                │
│  DB UPDATE booking(승격대상) PENDING          │
└───────────────────────────────────────────────┘

[다음 polling: try_promote → READY]
  Redis HSET wait:token status=PROCESSING (Lua 원자)
  @Async paymentExecutor.submit(() -> {
     processSync(booking, payments)  ← 위 §6.1 동기 흐름
     Redis HSET wait:token PAID|FAILED + bookingId|failed_reason
  })
  polling 즉시 응답
```

### 6.4 노드 죽음 시점 v4 함의 — 05단계에서 정밀 처리

- tx1 (WAITING) 후 죽음 → booking WAITING + wait:token 활성. polling 정상 동작.
- 비동기 결제 진행 중 노드 죽음 → 5단계 시나리오 (Reconciliation으로 복구)
- 승격 도중 노드 죽음 → 5단계 시나리오

---

## 7. PaymentStrategy 시그니처 (실 구현)

```java
// src/main/java/.../payment/strategy/PaymentStrategy.java
public interface PaymentStrategy {
    PaymentMethod method();
    AuthOutcome   authorize(PaymentContext ctx);                       // outcome/
    CaptureOutcome capture(String authReference, PaymentContext ctx);
    VoidOutcome    voidAuth(String authReference, PaymentContext ctx);

    static String pgIdempotencyKey(long bookingId, PaymentMethod method) {
        return "pay-" + bookingId + "-" + method.name();
    }
}
```

> `AuthorizeResult` / `CaptureResult` / `VoidResult` 는 PG 측 RestClient DTO (`pg/` 패키지). Strategy 의 반환은 도메인 `outcome/` records.

---

## 8. 비동기 paymentExecutor 설정 (v4 신규)

```java
// src/main/java/.../async/AsyncConfig.java
@Configuration
public class AsyncConfig {
    @Bean("paymentExecutor")
    public Executor paymentExecutor(PaymentExecutorProperties props) {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(props.corePoolSize());       // application.yml: 10
        exec.setMaxPoolSize(props.maxPoolSize());         // 50
        exec.setQueueCapacity(props.queueCapacity());     // 100
        exec.setKeepAliveSeconds(props.keepAliveSeconds());
        exec.setThreadNamePrefix(props.threadNamePrefix());  // pay-async-
        exec.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        exec.initialize();
        return exec;
    }
}
```

```java
// src/main/java/.../promotion/PromotionService.java
@Async("paymentExecutor")
public void processPayment(String waitToken) {
    // bookingRepo.findByWaitToken → orchestrator.authorize → markPaymentsAuthorized
    // → orchestrator.capture → finalizePaid → wait:token Hash status=PAID + outbox(BOOKING_PAID)
    // 실패 시 handleAsyncFailure (compensate + restoreAndPromote + outbox)
}
```

**왜 별도 풀?**:
- Tomcat thread (요청 처리)와 paymentExecutor (백그라운드 결제) 분리
- polling 응답이 결제 끝까지 안 막힘 → Tomcat thread 효율
- 결제 thread 고갈 시에도 polling은 정상

---

## 9. 신규 결제수단 추가 시나리오 (v3와 동일)

`enum PaymentMethod`에 한 줄 + `XpayPaymentStrategy` 구현체 1개. Booking·Validator·Orchestrator·DDL 수정 0줄.

---

## 10. failed_reason 분류 (v4)

```java
public enum FailureReason {
    // 사용자/입력
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
    
    // 사용자 명시적
    USER_CANCELED,
    
    // 시간
    TIMEOUT,                  // 일반 좀비 타임아웃
    SLOT_TIMEOUT (v4),        // slot 3분 만료 — 자동 진행이라 사실상 없음
    WAIT_TIMEOUT (v4)         // 대기 1h polling 없음
}
```

`status=EXPIRED`로 분리되는 사유: `SLOT_TIMEOUT`, `WAIT_TIMEOUT`.
`status=FAILED`로 가는 사유: 나머지.

---

## 11. ERD 영향

`booking` 테이블에 v4 컬럼 4개 추가 (1단계에 이미 반영):
- `wait_token VARCHAR(64) NULL UNIQUE`
- `slot_token VARCHAR(64) NULL`
- `enqueued_at DATETIME(3) NULL`
- `promoted_at DATETIME(3) NULL`
- `expired_at DATETIME(3) NULL`

`status` enum 확장: WAITING / PENDING / PAID / FAILED / EXPIRED.

---

## 12. 정합성 방어선 종합 (v4)

| 자원 | 1차 | 2차 | 3차 |
|---|---|---|---|
| **외부 멱등** | Redis idem:{key} + request_hash | booking.idempotency_key UNIQUE | — |
| **대기 진입 멱등** | idem 캐시 (WAITING 응답) | booking.wait_token UNIQUE | — |
| **PG 호출 멱등** | 분산락 + Mock PG 캐시 | payment.pg_idempotency_key UNIQUE | Outbox COMPENSATION_* (Phase 1) |
| **포인트 멱등** | `existsByReferenceKey` 사전 조회 | point_transaction.reference_key UNIQUE | (Pt1 보류 — 합산 검증 워커 없음) |
| **재고** | Lua conditional_decr_or_wait + restore_stock_and_promote (OVERFLOW 가드) | booking.PAID + PENDING ≤ stock_total | hold/slot TTL + Reconciliation (verifyConsistency 5분) |
| **승격 정합성** | Lua restore_stock_and_promote 원자 | — | 좀비 청소 워커 (5분 임계) |
| **비동기 결제 상태 동기화** | wait:token Hash 업데이트 + polling 조회 | — | 좀비 워커 + outbox(BOOKING_PAID/FAILED) |
| **1인1상품** | application `existsByCustomerIdAndProductIdAndStatusIn` | booking.active_key UNIQUE (generated) | — |

---

## 13. 다음 단계로 넘기는 미결

| → 단계 | 미결 |
|---|---|
| **5 (장애대응)** | (1) 비동기 결제 진행 중 노드 사망. (2) 승격 도중 노드 사망. (3) wait:token 1h 만료 정리. (4) 좀비 청소 워커 확장 (slot 만료 감지). (5) 노드 사망 시나리오 v4 매트릭스 (6개 → 10개+). (6) Resilience4j 설정. |
