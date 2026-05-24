# Payments — 한정 수량 선착순 예약·결제 플랫폼

> 00시 오픈되는 한정 수량 상품에 대한 선착순 예약·결제 시스템.
> 평시 50 TPS · 피크 500~1000 TPS · 2대 이상 분산 WAS 환경을 가정.
> **모델 D 채택**: 재고 10 + 대기열 30 + 자동 진행. 결제 실패자 자리가 대기 1번에게 즉시 양도되어 자원 사장 없음.

본 문서는 시스템의 **아키텍처 · 데이터 모델 · 핵심 흐름 · API · 실행 방법**을 다룹니다. 기술 선택의 근거와 트레이드오프는 [DECISIONS.md](./DECISIONS.md)를, 단계별 상세 설계는 [`docs/design/`](./docs/design)을 참조하세요.

---

## ⚡ 빠른 검증 — 5분 안에

> 사전 요구사항: **Docker Desktop / Compose v2 · `git` · `curl` · `jq`** (부하 테스트만 추가로 `vegeta` 필요, 선택)

```bash
# 1) 클론 + 진입 + 환경변수
git clone <repo-url> payments && cd payments && cp .env.example .env

# 2) 6 컨테이너 빌드 + 부팅 (시연용 워커 polling 5초 단축)
RECON_ZOMBIE_INTERVAL=5000 RECON_WAIT_INTERVAL=5000 docker compose up -d --build

# 3) 부팅 완료 대기 (~1~2분)
until curl -fs http://localhost/actuator/health > /dev/null 2>&1; do sleep 2; done

# 4) 자동 시연 — 6 시나리오 (CARD · 매진→WAITING · polling · LB · 만료 워커 · 좀비 워커)
bash demo/run-all.sh

# 5) JUnit 통합 테스트 — 8 시나리오
./gradlew test --tests BookingScenarioTest
```

→ 시연 `✅ 시연 6/6 완료` + JUnit `BUILD SUCCESSFUL` 이면 검증 통과.
상세 단계·예상 결과·정리 명령은 [§8.1 평가자 검증 시퀀스](#81-평가자-검증-시퀀스--6-단계) 참조.

---

## 1. 평가 핵심 5축 — 어디서 다루는가

| 평가 축 | 본 시스템의 응답 | 상세 |
|---|---|---|
| **재고 정합성 · 공정성** | Redis Lua `conditional_decr_or_wait`가 implicit FIFO queue. 재고 확보 + 대기열 진입을 3-way 원자 분기. | [§4 핵심 흐름](#4-핵심-흐름), [DECISIONS §1·§2](./DECISIONS.md) |
| **고가용성 / TPS 급증 대응** | 동기 처리 + 5종 방어 세트 + 비동기 paymentExecutor. PG 호출량 98%+ 감소(1990회 → 25~40회). | [§7 운영 튜닝](#7-운영-튜닝값), [DECISIONS §3](./DECISIONS.md) |
| **멱등성** | `Idempotency-Key` 헤더 + Redis 캐시 (24h) + `booking.idempotency_key UNIQUE` + `request_hash` 검증. WAITING 응답도 멱등. | [§4.3 멱등 흐름](#43-멱등-처리), [DECISIONS §6](./DECISIONS.md) |
| **결제 확장성** | `PaymentMethod 2-카테고리` + Strategy + Orchestrator. 신규 결제수단 추가 시 enum 1줄 + Strategy 1개, Booking 로직 수정 0줄. | [§5 결제 모듈](#5-결제-모듈-구조), [DECISIONS §7](./DECISIONS.md) |
| **장애 대응** | Auth/Capture/Void + hold/slot 키 자동 만료 + Outbox 보상 워커 + 11시나리오 Reconciliation + 대기열 만료 정리. Redis 다운 시 Fail-Fast. | [§4.5 좀비 복구](#45-좀비-복구--reconciliation), [DECISIONS §9](./DECISIONS.md) |

---

## 2. 시스템 아키텍처

### 2.1 컨테이너 구성

```
                        ┌──────────────┐
                        │    Nginx     │  LB · round-robin
                        │    :80       │  + proxy_next_upstream
                        └──────┬───────┘
                  ┌────────────┴────────────┐
            ┌─────▼─────┐              ┌────▼──────┐
            │   App1    │              │   App2    │  Spring Boot
            │  :8080    │              │  :8080    │  INSTANCE_ID MDC
            └─────┬─────┘              └─────┬─────┘
                  └────────────┬─────────────┘
            ┌──────────────────┼──────────────────┐
       ┌────▼────┐        ┌────▼────┐        ┌────▼────┐
       │  MySQL  │        │  Redis  │        │ Mock PG │
       │  :3306  │        │  :6379  │        │  :9090  │
       │  (SoT)  │        │ implicit│        │ Auth/   │
       │         │        │  queue  │        │ Capture/│
       │         │        │+waitlist│        │  Void/  │
       │         │        │ +holds  │        │  Query  │
       │         │        │ +slots  │        │         │
       │         │        │ +locks  │        │         │
       │         │        │ +idem   │        │         │
       └─────────┘        └─────────┘        └─────────┘
```

### 2.2 컨테이너 책임

| 컨테이너 | 책임 |
|---|---|
| **Nginx** | LB, `/actuator/health` 헬스체크, 노드 다운 시 자동 페일오버 |
| **App1 / App2** | Stateless Spring Boot. `INSTANCE_ID` MDC로 로그 식별. `paymentExecutor` 별도 thread pool |
| **MySQL 8.4** | 영속 진실원천 |
| **Redis 7.4** | implicit queue (Lua) + 대기열(Sorted Set) + 멱등 캐시 + 분산락 + hold/slot |
| **Mock PG** | `/pg/authorize`, `/pg/capture`, `/pg/void`, `/pg/transactions/{key}` HTTP API |

---

## 3. 데이터 모델

### 3.1 ERD (v4)

```
┌──────────────┐                    ┌────────────────────┐
│   customer   │                    │   product          │
│ id, name     │                    │ id, name, price    │
└──────┬───────┘                    │ check_in/out_at    │
       │ 1                          │ stock_total        │
       │                            │ open_at            │
       │ 1                          └─────────┬──────────┘
       ▼                                      │ N
┌──────────────────────┐        ┌────────────────────────────────┐
│  customer_point      │        │  booking                       │
│ customer_id (PK,FK)  │        │ id, customer_id, product_id    │
│ balance (≥0)         │        │ status: WAITING / PENDING /    │
│ version (낙관락)     │        │         PAID / FAILED /        │
└────────┬─────────────┘        │         EXPIRED                │
         │ 1                    │ failed_reason                  │
         │ N                    │ total_amount                   │
         ▼                      │ idempotency_key (UQ)           │
┌──────────────────────┐        │ request_hash (SHA-256)         │
│ point_transaction    │◄───────│ active_key (UQ, generated:     │
│ id, customer_id      │        │   WAITING + PENDING + PAID)    │
│ booking_id           │        │ wait_token (UQ, null if direct)│
│ delta (+/-)          │        │ slot_token (null until READY)  │
│ reason               │        │ enqueued_at / promoted_at      │
│ reference_key (UQ)   │        │ created/paid/failed/expired_at │
└──────────────────────┘        └──────┬─────────────────────────┘
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

### 3.2 Redis 키 스키마 (v4)

| Key | Type | TTL | 용도 |
|---|---|---|---|
| `stock:product:{pid}` | INT | 영구 | **implicit queue** · Lua `conditional_decr_or_wait` |
| `waitlist:product:{pid}` | **Sorted Set** | 영구 | **대기열 (score=enqueued_at, member=waitToken)**, ZADD/ZRANK/ZPOPMIN |
| `wait:token:{waitToken}` | **Hash** | **1h** | **대기자 상태 + 결제 정보 임시 저장**. polling 대상 |
| `hold:product:{pid}:booking:{bid}` | STRING | **180s** | 선점 자동 만료 |
| `slot:{slotToken}` | STRING | **180s** | 자리 점유, 만료 시 다음 승격 트리거 |
| `idem:{idempotency-key}` | STRING (JSON) | 24h | 응답 재생 캐시 |
| `lock:booking:customer:{cid}:prod:{pid}` | SETNX | 5s | 동일 customer·product 진입 차단 |
| `lock:outbox:partition:{n}` | SETNX | 30s | Outbox 워커 경합 |
| `lock:reconcile:{taskName}` | SETNX | 60s | Reconciliation 단일 실행 (`zombie` / `verify` / `wait_cleanup`) |

### 3.3 Lua 스크립트 (v4)

- **`conditional_decr_or_wait.lua`** — 3-way 분기: SLOT 점유 / WAITLIST 진입 / FULL 매진
- **`restore_stock_and_promote.lua`** — 보상 시 INCR + DEL hold + ZPOPMIN으로 다음 승격
- **`try_promote.lua`** — polling 시 READY → PROCESSING 원자 전환
- **`safe_unlock.lua`** — 락 value 검증 후 DEL

DDL 전문은 [`docs/design/01-domain.md §6`](./docs/design/01-domain.md) 참조.

---

## 4. 핵심 흐름 (v4 모델 D)

### 4.1 Happy Path — 즉시 결제 성공 (재고 확보)

```
Client            App                 Redis             DB              MockPG
  │                │                    │                │                │
  │ POST /booking
  │ ──────────────►│                    │                │                │
  │                │ SETNX lock         │                │                │
  │                │ ──────────────────►│                │                │
  │                │                    │                │                │
  │                │ EVAL conditional_decr_or_wait.lua                    │
  │                │   → "SLOT:9" (재고 -1, hold TTL 180s)                │
  │                │ ──────────────────►│                │                │
  │                │                                                      │
  │                │ INSERT booking PENDING + payment(POINT,CARD) REQUESTED
  │                │ ───────────────────────────────────►│                │
  │                │                                                      │
  │                │ POINT HOLD → PG AUTHORIZE → PG CAPTURE → POINT COMMIT
  │                │ ─────────────────────────────────────────────────────►
  │                │ UPDATE booking PAID + DEL hold + outbox(BOOKING_PAID)│
  │                │ ───────────────────────────────────►│                │
  │                │ 락 해제 + idem 캐시                                   │
  │                │                                                      │
  │◄── 200 PAID ───│  { status:"PAID", bookingId, payments[...] }         │
```

### 4.2 대기 진입 (재고 0 + 대기열 < 30)

```
  │ POST /booking
  │ ──────────────►│                    │                │                │
  │                │ SETNX lock ✅      │                │                │
  │                │ EVAL conditional_decr_or_wait.lua                    │
  │                │   → "WAITLIST:5" (ZADD + HSET wait:token)            │
  │                │ ──────────────────►│                │                │
  │                │                                                      │
  │                │ INSERT booking WAITING (wait_token, enqueued_at)    │
  │                │ ───────────────────────────────────►│                │
  │                │ 락 해제 + idem 캐시 (WAITING 응답)                    │
  │                │                                                      │
  │◄── 200 ────────│  { status:"WAITING", waitToken, position:5,          │
  │                    pollingIntervalMs:2000 }                           │
```

### 4.3 대기 polling + 자동 승격 (v4 핵심)

```
[사용자가 2~3초마다 polling]
  │ GET /booking/wait/{waitToken}
  │ ──────────────►│                                                      │
  │                │ EVAL try_promote.lua → "WAITING"                     │
  │                │ ZRANK → position = 3                                 │
  │◄── 200 ────────│  { status:"WAITING", position:3 }                    │
  │                                                                       │
  │  ... 2~3초 후 다시 ...                                                  │
  │                                                                       │
[다른 사용자가 결제 실패 → 대기 1번 승격]
  │                │ (어떤 사용자 X의 결제 실패 흐름에서)                  │
  │                │ EVAL restore_stock_and_promote.lua                   │
  │                │   → "PROMOTED:wt_abc" (이 사용자)                    │
  │                │ slot 발급, hold 재SET                                 │
  │                │ UPDATE booking PENDING + HSET wait:token READY      │
  │                                                                       │
  │  ... 이 사용자의 다음 polling ...                                       │
  │                                                                       │
  │ GET /booking/wait/{waitToken}                                         │
  │ ──────────────►│                                                      │
  │                │ EVAL try_promote.lua → "READY"                       │
  │                │   (READY를 PROCESSING으로 원자 전환)                  │
  │                │ @Async paymentExecutor.submit(결제 흐름)              │
  │                │   (백그라운드: 모델 C 동기 흐름)                       │
  │◄── 200 ────────│  { status:"PROCESSING" }   ← 즉시 응답               │
  │                                                                       │
  │  ... 2~3초 후 ...                                                       │
  │                                                                       │
  │ GET /booking/wait/{waitToken}                                         │
  │ ──────────────►│                                                      │
  │                │ EVAL try_promote.lua → "PAID"                        │
  │                │ HGET wait:token bookingId                            │
  │◄── 200 PAID ───│  { status:"PAID", bookingId:99 }                     │
```

### 4.4 매진 (대기열 가득)

```
  │                │ EVAL conditional_decr_or_wait.lua → "FULL"           │
  │                │ 락 해제 + idem 캐시                                   │
  │◄── 409 ────────│  { reason:"SOLD_OUT" } + Retry-After: 60             │
```

### 4.5 좀비 복구 — Reconciliation

3종 워커:
- **좀비 청소 (1분)**: AUTHORIZED 5분 / REQUESTED 5분 / slot 만료 좀비 식별 → 보상 + 다음 대기자 승격
- **정합성 검증 (5분)**: `Redis stock + booking PAID + PENDING ≤ stock_total` + 대기열 drift 검증
- **대기 만료 정리 (5분)**: `booking WAITING + enqueued_at < 1h` → 정리 + booking EXPIRED

노드 죽음 시점별 11가지 시나리오는 [`docs/design/05-resilience.md §3`](./docs/design/05-resilience.md) 매트릭스 참조.

---

## 5. 결제 모듈 구조

### 5.1 카테고리 모델

```java
enum PaymentMethod {
    CARD  (Category.MAIN),
    YPAY  (Category.MAIN),
    POINT (Category.SUPPLEMENT);
    enum Category { MAIN, SUPPLEMENT }
}
```

**규칙**: MAIN ≤ 1 · SUPPLEMENT ≤ 1 · 최소 1개 · Σ amount = total_amount · method 중복 X.

### 5.2 클래스

```
BookingService
  └─ PaymentCombinationValidator
  └─ StockService (Lua wrapper)
  └─ PaymentOrchestrator
        └─ PaymentStrategy (interface)
              ├─ CardPaymentStrategy
              ├─ YpayPaymentStrategy
              └─ PointPaymentStrategy
  └─ PromotionService (v4 신규)
  └─ WaitPollingService (v4 신규)
  └─ @Async resumePromoted() — paymentExecutor 별도 풀
```

### 5.3 호출 순서 (동기·비동기 동일)

```
authorize:  SUPPLEMENT 먼저 (POINT HOLD) → MAIN 나중 (PG AUTHORIZE)
capture:    MAIN 먼저 (PG CAPTURE) → SUPPLEMENT (POINT COMMIT)
compensate: 위 역순 + restore_stock_and_promote (자동 승격 트리거)
```

### 5.4 신규 결제수단 추가 비용

```java
enum PaymentMethod {
    CARD(MAIN), YPAY(MAIN), POINT(SUPPLEMENT),
    XPAY(MAIN);   // ← 한 줄
}

@Component
class XpayPaymentStrategy implements PaymentStrategy { ... }   // 구현체 1개
```

**Booking 비즈 로직 수정 0줄**. Validator·Orchestrator는 Spring 자동 주입.

---

## 6. API

### 6.1 엔드포인트 (v4)

| Method | Path | 설명 |
|---|---|---|
| `GET`  | `/checkout?productId={id}&customerId={id}` | 상품 정보 + 가용 포인트 조회 |
| `POST` | `/booking` | 결제 + 예약 (멱등). 응답: PAID / WAITING / SOLD_OUT |
| **`GET`**  | **`/booking/wait/{waitToken}`** | **대기 상태 polling (v4 신규)**. 응답: WAITING / PROCESSING / PAID / FAILED |
| `GET`  | `/booking/{id}` | 예약 조회 |
| `GET`  | `/actuator/health` | 헬스 체크 |

내부 / 운영용:
| Method | Path | 설명 |
|---|---|---|
| `POST` | `/pg/callback` | 실 PG 콜백 (현재 mock 미사용) |

### 6.2 POST /booking — 요청 / 응답 예시

```http
POST /booking HTTP/1.1
Content-Type: application/json
Idempotency-Key: 8f3e2c10-7b4a-4e91-9c2f-7e2a1d3b5f80

{
  "customerId": 1,
  "productId": 1,
  "payments": [
    { "method": "CARD",  "amount": 45000 },
    { "method": "POINT", "amount": 5000 }
  ]
}
```

**응답 1 — 즉시 결제 성공**:
```http
HTTP/1.1 200 OK
Idempotency-Key: 8f3e2c10-...
{ "status": "PAID", "bookingId": 42, "paidAt": "...", "payments": [...] }
```

**응답 2 — 대기 진입 (v4 신규)**:
```http
HTTP/1.1 200 OK
Idempotency-Key: 8f3e2c10-...
{
  "status": "WAITING",
  "waitToken": "wt_abc123",
  "position": 5,
  "pollingIntervalMs": 2000,
  "estimatedWaitSeconds": 60
}
```

**응답 3 — 매진**:
```http
HTTP/1.1 409 Conflict
Retry-After: 60
{ "reason": "SOLD_OUT", "message": "재고 매진 + 대기열 가득. 1분 후 재시도 권장." }
```

### 6.3 GET /booking/wait/{waitToken} — polling

```http
GET /booking/wait/wt_abc123 HTTP/1.1

# 응답 1 — 대기 중
HTTP/1.1 200 OK
{ "status": "WAITING", "position": 3, "pollingIntervalMs": 2000 }

# 응답 2 — 승격되어 결제 진행 중
HTTP/1.1 200 OK
{ "status": "PROCESSING", "pollingIntervalMs": 2000 }

# 응답 3 — 결제 완료
HTTP/1.1 200 OK
{ "status": "PAID", "bookingId": 99 }

# 응답 4 — 결제 실패
HTTP/1.1 422 Unprocessable Entity
{ "status": "FAILED", "reason": "PAYMENT_DECLINED" }

# 응답 5 — 만료
HTTP/1.1 410 Gone
{ "reason": "WAIT_TIMEOUT" }
```

### 6.4 응답 코드 종합

| 코드 | 의미 |
|---|---|
| 200 PAID | 결제 성공 |
| 200 WAITING | 대기 진입 (v4) |
| 200 PROCESSING | 승격 후 결제 진행 중 (v4) |
| 400 | Idempotency-Key 형식 위반 / body 검증 실패 |
| 409 SOLD_OUT | 재고 + 대기열 모두 가득 |
| 409 ALREADY_RESERVED | 1인1상품 위반 (WAITING/PENDING/PAID 활성) |
| 409 IN_PROGRESS | 동일 Idem-Key 처리 중 |
| 410 EXPIRED | 대기 만료 (1h polling 없음) |
| 422 IDEMPOTENCY_KEY_REUSED | 같은 키 + 다른 body |
| 422 INVALID_COMBINATION | 결제 수단 조합 위반 |
| 422 PAYMENT_DECLINED | PG 거절 |
| 422 INSUFFICIENT_POINT | 포인트 잔액 부족 |
| 503 PG_UNAVAILABLE | PG CircuitBreaker OPEN (`CallNotPermittedException`) 또는 Bulkhead full (`BulkheadFullException`) |
| 503 PG_TIMEOUT | PG read timeout (1s) — `RestClientResponseException` |
| 500 INTERNAL_ERROR | Redis 다운 등 처리되지 않은 예외 (현재 명시적 Fail-Fast 미구현) |

---

## 7. 운영 튜닝값

```yaml
server:
  tomcat:
    threads:
      max: 400
  shutdown: graceful

spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s
  datasource:
    hikari:
      maximum-pool-size: 50

resilience4j:
  # Timeout 은 @TimeLimiter 어노테이션 대신 RestClient 자체 timeout 사용
  # (별도 thread pool 비용 회피, IO timeout 으로 thread 자연 회수)
  circuitbreaker.instances.pgGateway:
    sliding-window-type: TIME_BASED
    sliding-window-size: 10
    failure-rate-threshold: 50
    slow-call-rate-threshold: 80
    slow-call-duration-threshold: 800ms
    wait-duration-in-open-state: 30s
    minimum-number-of-calls: 20
  bulkhead.instances.pgGateway:
    max-concurrent-calls: 100
    max-wait-duration: 100ms

# MockPgClient 의 RestClient 직접 설정
#   HttpClient.connectTimeout: 500ms
#   JdkClientHttpRequestFactory.readTimeout: 1s
#
# paymentExecutor (v4) — AsyncConfig 에서 Java 코드로
#   core=10, max=50, queue=100, prefix=pay-async-
```

산수: `1000 TPS × 0.015s (평균, 대부분 매진+대기 빠른 응답) ≈ 15 슬롯 / 2노드 × 400 thread = 800 → 압도적 여유`.

---

## 8. 실행 방법

### 8.1 평가자 검증 시퀀스 — 6 단계

> **사전 요구사항**: Docker Desktop / Compose v2 · `git` · `curl` · `jq`
> 부하 테스트만 추가로 `vegeta` 필요 (선택)

```bash
# 1. 레포 클론 + 진입
git clone <repo-url> payments
cd payments

# 2. 환경변수 (기본값으로도 동작)
cp .env.example .env

# 3. 6 컨테이너 빌드 + 부팅 (시연용 워커 polling 5초)
#    RECON_*_INTERVAL 안 붙이면 운영 기본값 (60초/5분) — 시연 ⑤⑥ 대기 길어짐
RECON_ZOMBIE_INTERVAL=5000 RECON_WAIT_INTERVAL=5000 \
  docker compose up -d --build

# 4. 모든 컨테이너 healthy 대기 (~1~2분, MySQL 초기화 포함)
until curl -fs http://localhost/actuator/health > /dev/null 2>&1; do sleep 2; done
curl -s http://localhost/actuator/health
# → {"groups":["liveness","readiness"],"status":"UP"}

# 5. 자동 시연 (6 시나리오 + DB/Redis 검증 ~40초)
#    ① CARD 단독 PAID ② 매진→WAITING ③ polling
#    ④ LB 분산 ⑤ 대기열 만료 워커 ⑥ 좀비 복구 워커
bash demo/run-all.sh

# 6. JUnit 통합 테스트 (8 시나리오 ~1분)
./gradlew test --tests BookingScenarioTest
```

**예상 결과**:

| 단계 | 소요 | 검증 포인트 |
|---|---|---|
| 3 → 4 | ~2분 | 6 컨테이너 모두 `healthy` + actuator UP |
| 5 | ~40초 | **시연 6/6 모두 ✅** 표시. 결제 + 대기열 + 워커 복구 |
| 6 | ~1분 | **BUILD SUCCESSFUL** + 8 tests passed |

**정리**:

```bash
docker compose down -v    # 컨테이너 + volume(MySQL 데이터) 삭제
```

### 8.2 빠른 시나리오 검증

```bash
# ① 상품 조회
curl 'http://localhost/checkout?productId=1&customerId=1'

# ② 즉시 결제 시도 (Idem-Key 없이)
curl -X POST http://localhost/booking \
  -H 'Content-Type: application/json' \
  -d '{"customerId":1,"productId":1,"payments":[{"method":"CARD","amount":50000}]}'
# → 200 PAID 또는 200 WAITING (재고에 따라)

# ③ 대기 진입 시 — polling
WAIT_TOKEN="..."  # 응답의 waitToken
while true; do
  RESPONSE=$(curl -s "http://localhost/booking/wait/$WAIT_TOKEN")
  STATUS=$(echo $RESPONSE | jq -r '.status')
  echo "$STATUS"
  if [ "$STATUS" = "PAID" ] || [ "$STATUS" = "FAILED" ]; then
    echo $RESPONSE | jq .
    break
  fi
  sleep 2
done

# ④ 전체 시연 (6 시나리오 자동 실행 — reset → CARD → 매진+WAITING → polling → LB → 워커 ⑤⑥)
bash demo/run-all.sh

# ⑤ 1000 TPS 부하 시뮬레이션 (vegeta 필요)
brew install vegeta
bash load/run.sh                        # 기본: 1000 TPS × 1s
RATE=500 DURATION=2s bash load/run.sh   # 커스텀
```

> `demo/` 와 `load/` 의 스크립트 구성·검증 기준은 [`docs/design/06-test.md`](./docs/design/06-test.md) 참조.

### 8.3 환경 변수

```env
MYSQL_DATABASE=payments
MYSQL_USER=payments
MYSQL_PASSWORD=payments
MYSQL_ROOT_PASSWORD=root

DB_HOST=mysql
DB_PORT=3306
REDIS_HOST=redis
REDIS_PORT=6379

MOCK_PG_URL=http://mock-pg:9090
MOCK_PG_LATENCY_MS=100
MOCK_PG_FAIL_RATE=0.0
MOCK_PG_TIMEOUT_RATE=0.0
```

---

## 9. API 평가 테스트 시나리오 (v4 확장)

| # | 평가자 액션 | 우리 응답 |
|:-:|---|---|
| ① | POST /booking (Idem-Key 없이) | 200 PAID / 200 WAITING / 409 SOLD_OUT + Idem-Key echo |
| ② | 같은 customer+product 재호출 | 409 ALREADY_RESERVED |
| ③ | 같은 Idem-Key 재호출 | 캐시 응답 재생 |
| ④ | 처리 중 같은 Idem-Key 재호출 | 409 IN_PROGRESS + Retry-After: 5 |
| ⑤ | 같은 키 + 다른 body | 422 IDEMPOTENCY_KEY_REUSED |
| ⑥ | 결제 실패 후 재시도 | 새 booking 시도 |
| **⑦ (v4)** | **WAITING 받은 후 GET /booking/wait/{wt}** | **200 WAITING + position** |
| **⑧ (v4)** | **승격 후 polling** | **200 PROCESSING → 다음 polling에서 PAID** |
| **⑨ (v4)** | **1h 후 polling** | **410 EXPIRED** |
| **⑩ (v4)** | **WAITING 받은 후 같은 Idem-Key로 POST 재호출** | **캐시 재생: 같은 waitToken 반환** |

---

## 10. 디렉토리 구조

```
.
├── README.md
├── DECISIONS.md
├── docker-compose.yml
├── build.gradle · settings.gradle
├── docs/
│   └── design/
│       ├── 01-domain.md         도메인 모델 & ERD (v4)
│       ├── 02-concurrency.md    동시성 / 재고 / 대기열 (v4)
│       ├── 03-idempotency.md    멱등성 (v4)
│       ├── 04-payment.md        결제 모듈 + 비동기 (v4)
│       ├── 05-resilience.md     장애 대응 + 11시나리오 (v4)
│       ├── 06-test.md           테스트 전략 (JUnit · demo · load)
│       └── 07-flow.md           사용자 요청 플로우 (v4)
├── docker/
│   ├── app/Dockerfile
│   ├── nginx/nginx.conf
│   └── mock-pg/                 Node.js 80줄 + Dockerfile + package.json
├── demo/                        수동 시연 6종 (curl + DB/Redis 검증)
│   ├── reset.sh
│   ├── 1-card-only.sh ~ 6-zombie-cleanup.sh
│   └── run-all.sh
├── load/                        1000 TPS 부하 테스트 (vegeta)
│   ├── reset.sh · gen-targets.sh · verify.sh
│   └── run.sh
└── src/main/
    ├── java/com/platform/payments/
    │   ├── PaymentsApplication.java
    │   ├── booking/             BookingService, Controller, Persistence, Repository
    │   │   ├── dto/             BookingCreateRequest, Response, Output
    │   │   ├── checkout/        CheckoutController + Response
    │   │   └── wait/            WaitPollingController/Service + DTO + Exception
    │   ├── payment/             Payment 코어, Orchestrator, Context, Request
    │   │   ├── strategy/        PaymentStrategy + Card/Ypay/Point 구현체
    │   │   ├── outcome/         Auth/Capture/Void Outcome record
    │   │   └── validation/      PaymentCombinationValidator + Exception
    │   ├── customer/            Customer + Repository (1~1000 시드)
    │   ├── point/               CustomerPoint, PointTransaction
    │   ├── product/             Product + Repository
    │   ├── stock/               StockService (Lua 4종 wrapper)
    │   ├── promotion/           PromotionService (대기자 승격 + @Async, v4)
    │   ├── lock/                DistributedLockService (SETNX + safe_unlock.lua)
    │   ├── outbox/              OutboxEvent + Worker + Dispatcher
    │   ├── reconciliation/      ReconciliationWorker 3종 (zombie/verify/wait_cleanup)
    │   ├── pg/                  PaymentGateway + MockPgClient (RestClient + @CB + @BH)
    │   ├── idempotency/         IdempotencyService (캐시 + request_hash)
    │   ├── inbox/               InboxEvent (인터페이스 골격)
    │   ├── async/               AsyncConfig (paymentExecutor, v4)
    │   └── common/              SeedDataRunner, GlobalExceptionHandler, properties
    └── resources/
        ├── application.yml
        ├── schema.sql           단일 schema 소스 (8개 테이블)
        ├── data.sql             customer 1000명 + product 1개 seed
        └── lua/
            ├── conditional_decr_or_wait.lua    (v4 — 3-way 분기)
            ├── restore_stock_and_promote.lua   (v4 — 자리 양도)
            ├── try_promote.lua                  (v4 — WAITING→READY 전이)
            └── safe_unlock.lua
```

---

## 11. 추가 참고

- 단계별 상세 설계: [`docs/design/01-06`](./docs/design/) + [`07-flow.md`](./docs/design/07-flow.md)
- 기술 결정 근거: [`DECISIONS.md`](./DECISIONS.md)

---

## 12. 알려진 제약

| 제약 | 이유 | 실서비스 권장 |
|---|---|---|
| Redis 단일 인스턴스 SPoF | 명시적 Fail-Fast CB 미구현 (Spring 기본: 500 응답) — 시간 제약상 DB 폴백도 미구현 | Sentinel/Cluster + redisHealth CB + DB 폴백 |
| MySQL 단일 인스턴스 | 본 범위 외 | Replication / Aurora |
| 회원 인증·인가 미구현 | 명세 평가 범위 제외 | OAuth2 / JWT |
| 실 PG 미연동 | 명세 평가 범위 제외 | 표준 PG 어댑터 구현체 |
| 부분 환불 / 결제 후 취소 | 본 과제 범위 외 | `Booking.status=REFUNDED` + REFUND |
| 대기열 알림이 polling 방식 | 클라이언트 없는 평가 환경 | 실서비스는 SSE / WebSocket / Push |
