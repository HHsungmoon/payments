# 06. 테스트 전략

> **단계 목적**: 동시성·정합성·장애 시나리오를 어떻게 검증했는지 정리. JUnit 통합 테스트, 수동 시연 스크립트, 부하 테스트 — 3종 도구로 각자 다른 층위를 커버.

---

## 1. 테스트 종류 개요

| 도구 | 위치 | 목적 | 외부 의존 |
|---|---|---|---|
| **JUnit 통합 테스트** | `src/test/java/.../BookingScenarioTest.java` | 결제 7개 시나리오 (성공 3 + 멱등 2 + 오류 2) 회귀 방어 | docker compose (mysql + redis + mock-pg + app1) |
| **수동 시연 스크립트** | `demo/*.sh` | 흐름 6종 (① CARD ② 매진→WAITING ③ polling ④ LB 분산 ⑤ 대기열 만료 ⑥ 좀비 청소) 실제 docker 환경 검증 | 6개 컨테이너 + 워커 polling 단축 환경변수 |
| **부하 테스트** | `load/*.sh` | 1000 TPS burst 시 동시성 정확성: PAID=10, WAITING=30, 나머지 SOLDOUT | docker + vegeta 설치 |

세 도구가 같은 시스템을 **다른 충실도(fidelity)** 로 검증한다:
- JUnit = **빠른 회귀** (HTTP 1회, mock-pg 통과, 단 분산 LB는 미검증)
- 시연 스크립트 = **end-to-end** (nginx → app1/app2 → mysql/redis/mock-pg 풀스택)
- 부하 = **동시성 한계** (1000 TPS burst, Lua 원자성 검증)

---

## 2. JUnit 통합 테스트 (`BookingScenarioTest`)

### 2.1 시나리오

| # | 이름 | customer | 결제 수단 | 기대 응답 |
|---:|---|---:|---|---|
| S1 | CARD 단독 | 10 | CARD 50,000 | 200 PAID |
| S2 | YPAY + POINT 복합 | 11 | YPAY 45,000 + POINT 5,000 | 200 PAID, payments.length=2 |
| S3 | POINT 단독 | 12 | POINT 50,000 | 200 PAID |
| S4 | 같은 Idem-Key 재호출 | 13 | POINT 50,000 | 200 PAID (캐시 재생, 동일 bookingId) |
| S5 | 같은 Idem-Key + 다른 body | 14 | POINT → CARD | 422 IDEMPOTENCY_KEY_REUSED |
| S6 | CARD + YPAY (MAIN 2개) | 15 | CARD 30k + YPAY 20k | 422 INVALID_COMBINATION |
| S7 | 포인트 잔액 초과 | 16 | POINT 150,000 | 422 INSUFFICIENT_POINT |

### 2.2 데이터 격리

테스트 간 잔존 데이터로 인한 flaky 회피:

```java
@BeforeEach
void resetState() {
    paymentRepo.deleteAll();
    pointTxRepo.deleteAll();
    bookingRepo.deleteAll();
    outboxRepo.deleteAll();
    // 누적 차감 방지 — 테스트 customer 잔액 복원
    jdbc.update("UPDATE customer_point SET balance=100000 WHERE customer_id BETWEEN 10 AND 99");
    stockService.setStock(PRODUCT_ID, 10);
    // 이전 테스트 idem 키 잔존 제거
    var keys = redis.keys("idem:*");
    if (keys != null && !keys.isEmpty()) redis.delete(keys);
}
```

- customer 10~16: 통합 테스트 전용 (시연·운영·부하와 분리)
- `balance` UPDATE는 `JdbcTemplate`을 통해 자체 tx로 즉시 commit (`@Transactional` 어노테이션 붙이면 Spring 테스트 기본 rollback에 휘말려 cleanup 자체가 사라짐)

### 2.3 실행

```bash
# 사전 — 컨테이너 기동
docker compose up -d

# 테스트
./gradlew test --tests BookingScenarioTest
```

---

## 3. 수동 시연 스크립트 (`demo/`)

### 3.1 구조

```
demo/
├── reset.sh                # 환경 초기화 (DB clean + customer 잔액 복원 + Redis stock=10)
├── 1-card-only.sh          # ① CARD 단독 결제 → PAID
├── 2-soldout-waiting.sh    # ② 10건 매진 + 11번째 WAITING 진입
├── 3-wait-polling.sh       # ③ /booking/wait/{token} 폴링 응답 검증
├── 4-lb-distribution.sh    # ④ nginx LB — app1/app2 docker logs 카운트
├── 5-waitlist-expire.sh    # ⑤ 대기열 만료 워커 (enqueued_at 과거 → EXPIRED)
├── 6-zombie-cleanup.sh     # ⑥ 좀비 워커 (PAID → 강제 PENDING/AUTHORIZED → FAILED + 재고복구)
└── run-all.sh              # 전체 시퀀스 실행
```

각 스크립트는 **응답 status 검증 + DB/Redis 상태 검증**까지 self-contained. 실패 시 exit 1.

### 3.2 워커 시연을 위한 환경 (⑤⑥)

운영 기본값(`waitlist 5분 / zombie 1분 polling`)으로는 시연이 너무 길어, docker-compose에 env override 지점 추가:

```yaml
# docker-compose.yml (app1/app2)
environment:
  APP_RECONCILIATION_ZOMBIE_CLEANUP_FIXED_DELAY_MS: ${RECON_ZOMBIE_INTERVAL:-60000}
  APP_RECONCILIATION_WAITLIST_CLEANUP_FIXED_DELAY_MS: ${RECON_WAIT_INTERVAL:-300000}
```

시연 전 다음과 같이 띄움:

```bash
RECON_ZOMBIE_INTERVAL=5000 RECON_WAIT_INTERVAL=5000 \
  docker compose up -d --force-recreate app1 app2
```

⑤번은 `enqueued_at = NOW - 2h` 강제 UPDATE → 1h 임계 초과 → 5s polling에 잡힘 → `WAITING → EXPIRED + Redis wait_token/waitlist 정리`.

⑥번은 PAID booking 1건을 `PENDING + AUTHORIZED + authorized_at = NOW - 10m`로 강제 update → 5m 임계 초과 → `orchestrator.compensate (PG VOID) + restoreAndPromote + finalizeFailed` → `payment VOIDED + booking FAILED + Redis stock +1`.

### 3.3 실행

```bash
# 전체 시퀀스 — reset → ①~⑥
bash demo/run-all.sh

# 개별 실행
bash demo/reset.sh
bash demo/1-card-only.sh
```

각 스크립트 출력 예시:

```
── ① CARD 단독 결제 (customer 1) ──
응답: {"status":"PAID","bookingId":44,...}
✅ PAID 확인
```

---

## 4. 부하 테스트 (`load/`)

### 4.1 목적

1000 TPS burst 환경에서:
- **재고 정확성**: 정확히 10건만 `PAID`, 30건 `WAITING`, 나머지 4960건 매진 응답
- **Lua 원자성**: 같은 product에 1000개 동시 요청해도 stock 음수 안 됨, waitlist 30명 초과 안 됨
- **Idempotency-Key 유일성**: 동일 customer가 여러 번 호출해도 같은 키는 캐시 재생

### 4.2 구조

```
load/
├── reset.sh         # DB clean + customer_point reset + Redis stock=10
├── gen-targets.sh   # vegeta JSON Lines targets 생성 (customer 100~999 rotate, idem-key unique)
├── run.sh           # 통합 실행: reset → gen → vegeta attack → report → verify
└── verify.sh        # DB+Redis 결과 검증 (PAID=10 / WAITING=30 / stock=0 / waitlist=30)
```

### 4.3 vegeta target 형식

같은 customerId여도 idem-key가 다르면 booking은 시도되도록 1000개 unique idem-key 생성:

```jsonl
{
  "method": "POST",
  "url": "http://localhost/booking",
  "header": {"Content-Type": ["application/json"], "Idempotency-Key": ["load-1-<nanos>"]},
  "body": "<base64(JSON payload)>"
}
```

### 4.4 검증 기준 (`verify.sh`)

| 항목 | 기대 | 실패 시 의미 |
|---|---|---|
| `booking.status = PAID` | **10** | 재고 정확성 깨짐 (Lua 비원자) |
| `booking.status = WAITING` | **30** | 대기열 상한 깨짐 |
| `booking.status = PENDING` | 0 | 좀비 발생 (워커 미처리 또는 동기 처리 누락) |
| `redis stock:product:1` | 0 | Lua DECR 누락/중복 |
| `redis ZCARD waitlist:product:1` | 30 | waitlist 동기화 불일치 |
| `payment.status = CAPTURED` | 10 | capture 누락 |

### 4.5 실행

```bash
# 사전
brew install vegeta              # 미설치 시
docker compose up -d

# 1000 TPS × 1s
bash load/run.sh

# 커스텀 (rate/duration 조절)
RATE=500 DURATION=2s COUNT=1000 bash load/run.sh
```

---

## 5. 데이터 격리 정책 (customer ID range)

세 종류 테스트가 같은 mysql 인스턴스를 쓰므로 ID 범위로 격리:

| 범위 | 용도 |
|---|---|
| 1~9 | 운영자/평가용 — `demo/` 스크립트 customer ① |
| 10~99 | JUnit 통합 테스트 — `customer_point.balance`를 `@BeforeEach`에서 100,000 복원 |
| 100~999 | 부하 테스트 — `load/reset.sh`에서 100~999 범위 복원 |

`data.sql`이 1~1000 customer를 한 번에 seed (WITH RECURSIVE + INSERT IGNORE), 각 테스트가 자기 범위만 reset.

---

## 6. 무엇을 테스트하지 *않는가*

- **PG 실패 분기 자동 회귀**: mock-pg 환경변수(`FAIL_RATE`, `TIMEOUT_RATE`)로 시뮬레이션은 가능하지만 회귀 테스트로 자동화하지 않음. 수동 fault injection 시연 영역.
- **여러 product 동시 부하**: 단일 product(id=1)만 대상. 운영 시 N개 product 부하 분산은 별도 시나리오.
- **Outbox dispatcher 실제 발송**: 현재 dispatcher가 log-only (외부 알림 미연동). Outbox row 생성·status 전환만 검증.
- **단위 테스트 (Lua, Validator 등)**: 통합 테스트에서 충분히 커버되는 것은 단위 테스트로 중복 작성하지 않음. 시간 여유 시 `StockServiceTest` (Lua 3-way 분기), `PaymentCombinationValidatorTest` 우선순위 후보.

---

## 7. 검증된 핵심 동작 요약

JUnit 7/7 + 시연 6/6 + 부하 5개 검증 항목으로 다음이 확인됨:

| 영역 | 검증 메커니즘 |
|---|---|
| 재고 원자성 | Lua `conditional_decr_or_wait` 3-way 분기 (PAID/WAIT/SOLDOUT) |
| 대기열 FIFO | Redis ZSET score (epoch ms) + ZRANGEBYSCORE 1번 |
| 결제 실패 자리 양도 | `restore_stock_and_promote.lua` → orchestrator.compensate → next promote |
| 멱등성 | Redis `idem:{key}` + `booking.idempotency_key` UNIQUE 이중 |
| 분산 LB | nginx round-robin → app1/app2 양쪽 처리 |
| 정합성 자가 복구 | ReconciliationWorker 3종 (zombie/consistency/wait-cleanup) |
| 트랜잭션 경계 | DB tx 안에서 외부 호출 없음 (BookingPersistence ↔ Orchestrator 분리) |
