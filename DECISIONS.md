# DECISIONS — 기술적 판단 근거

> 본 문서는 설계 과정에서 부딪힌 **10개 주요 쟁점**에 대한 트레이드오프 분석과 선택 근거를 담는다. 단계별 상세 설계는 [`docs/design/01~06`](./docs/design/)에 있고, 아키텍처·실행 방법은 [`README.md`](./README.md)에 있다. 본 문서는 *왜 이렇게 골랐는지*에 집중한다.
>
> **본 문서는 v4 (모델 D = 선점 + 대기열 + 자동 진행) 기준으로 구현 코드와 정합화되어 있다.** 초기 설계에선 모델 A(결제 완료 시점 commit) 였으나, 결제 실패자의 자리 사장 문제 해소를 위해 모델 D 로 전환했다. 전환 근거는 §2에 기록.
>
> 각 쟁점은 `상황 → 검토한 선택지 → 트레이드오프 → 채택과 근거 → (한계)` 순서로 구성된다.

---

## 목차

1. [재고의 진실원천 — Redis vs DB](#1-재고의-진실원천--redis-vs-db)
2. [공정성 정의 + 큐(Kafka 등) 도입 여부](#2-공정성-정의--큐-도입-여부)
3. [1000 TPS — 동기 처리 vs 비동기/큐](#3-1000-tps--동기-처리-vs-비동기큐)
4. [PG 호출 패턴 — Approve-Only vs Authorize/Capture/Void](#4-pg-호출-패턴--auth--capture--void)
5. [분산락의 사용처와 비사용처](#5-분산락의-사용처와-비사용처)
6. [멱등성 — 키와 저장소 책임 분리](#6-멱등성-키와-저장소-책임-분리)
7. [결제 모듈 확장성 — Strategy + 카테고리화](#7-결제-모듈-확장성--strategy--카테고리화)
8. [복합결제 호출 순서 — SUPPLEMENT 먼저](#8-복합결제-호출-순서)
9. [Redis 장애 Fallback 정책](#9-redis-장애-fallback-정책)
10. [추가 인프라 도입 — Outbox · Inbox · Mock PG · Docker 분산환경](#10-추가-인프라-도입)

---

## 1. 재고의 진실원천 — Redis vs DB

### 상황

00시 오픈, 10개 한정 상품, 1000 TPS 스파이크. *어디서 "지금 재고가 몇 개 남았다"를 확정할 것인가*가 모든 후속 설계의 전제다. 이 결정에 따라 차감 시점·정합성 검증 방식·장애 폴백 모드가 다 달라진다.

### 검토한 선택지

| 안 | 진실원천(SoT) | Redis 역할 | 흐름 |
|---|---|---|---|
| **A** | Redis | 권원 (가용 재고) | DECR → INSERT booking → 결제 → PAID |
| **B** | DB | 차감 카운터 (보조) | INSERT booking → 결제 → DECR → PAID |
| **C** | DB | (Redis 미사용) | `UPDATE product SET stock=stock-1 WHERE stock>0` |

### 트레이드오프

| 항목 | A: Redis SoT | B: DB SoT + Redis 카운터 | C: DB 단독 |
|---|---|---|---|
| 1000 TPS 흡수 | ✅ Redis DECR 원자 | ✅ DECR + DB 보조 | ❌ Hot row 락 경합 |
| 공정성 정의 | "DECR 도달 순서" 명확 | "DECR 도달 순서" 명확 | "FOR UPDATE 획득 순서" 모호 |
| 정합성 보정 기준 | Redis 카운터 (휘발성 위험) | DB의 PAID booking 집계 | DB 자체 |
| Redis 다운 시 | 권원 잃음 → 치명적 | 차감 못 함, 본질은 보존 | 영향 없음 |
| 운영 정합성 검증 | Redis ↔ DB 양쪽 비교 어려움 | `Redis + PAID ≤ stock_total` 부등식 명확 | 단순 |

### 채택과 근거: **B (DB SoT + Redis 카운터)**

처음엔 A(Redis SoT)로 출발했다. 1000 TPS를 흡수하는 명확한 동시성 모델이라는 점이 매력적이었다. 그러나 §2의 공정성 결정에서 **모델 A (결제 완료 시점 재고 commit)**를 채택하면서 "재고의 권원"의 의미 자체가 바뀌었다. 결제 완료가 commit point가 되는 흐름에서는 **`booking.status = PAID` 행의 집계가 곧 재고의 진실**이다. Redis는 그 commit 직전 단계의 빠른 차감 카운터이고, DB가 정합성 검증의 기준이 된다.

옵션 C는 단일 진실원천의 단순함이 매력이지만, 1000 TPS에서 같은 product row에 락 경합(InnoDB hot row)이 일어나 처리량이 무너진다. Redis 도입의 본질적 가치가 여기서 정당화된다.

### 한계

- DB의 `booking PAID` 집계는 **시점 일관성 문제** — 좀비 PENDING이 회색지대다. 5단계 Reconciliation에서 부등식(`Redis + PAID ≤ stock_total`)으로 단순화해 회피했다.

---

## 2. 공정성 정의 + 큐 도입 여부

### 상황

명세 *"모든 사용자에게 동등한 기회 제공"* 의 **작전적 정의**가 필요했다. "선착순"의 commit point가 어디인지에 따라 사용자 경험이 완전히 달라진다. 본 시스템은 v1 → v4 진화하며 commit point 정의가 바뀌었고, 최종 채택은 **모델 D (선점 + 대기열 + 자동 진행)**.

### 검토한 선택지 (시간 순서로)

| 모델 | commit point | 결제 실패 시 자리 | 채택 단계 |
|---|---|---|---|
| **A** | 결제 완료 시점 (PG CAPTURE 후 stock DECR) | **사장** — 자리는 비어도 다른 사용자 모름 | v1 초안 |
| **B** | 구매 버튼 시점 (stock 선점 + 5분 TTL) | 좀비 청소 워커가 복구하지만 그동안 사장 | v2 |
| **C** | 모델 B + slot/payment 분리 + auto-promotion 인터페이스 | C 단순화 형태, 명시적 대기열 없음 | v3 |
| **D** | **모델 C + 대기열 30 + 자동 승격** | **자동 양도** — 다음 대기자가 즉시 자리 차지 | **v4 (채택)** |
| **Q** | 메시지 큐 FIFO (Kafka/Redis Streams) | 큐 자체가 자리 보장 | 미채택 |

### 트레이드오프 (D vs A · Q)

| 항목 | 모델 A | 모델 D (채택) | 모델 Q |
|---|---|---|---|
| 결제 실패자 자리 처리 | **사장 (다른 user 모름)** | **자동 양도 (대기 1번 승격)** | 큐 다음 메시지 자동 처리 |
| 사용자 경험 | "결제 다 했는데 매진" 가능 | 매진 즉시 "대기 N번" 응답 + 폴링 | "대기 중..." 폴링 |
| API 패러다임 | 동기 (예약완료) | **동기 + 대기 응답 분기** (`PAID` / `WAITING`) | **비동기** (티켓 + 결과 폴링) |
| 시스템 복잡도 | 매진 충돌 시 VOID 보상 | Lua 3-way 분기 + auto-promotion 워커 + slot/wait TTL | 워커·DLQ·메시지 손실 시나리오 |
| 인프라 추가 | — | Redis Lua 4종 + 워커 3종 | Kafka/Streams 운영 부담 |
| 1000 TPS 흡수 | ✅ 동기 흡수 | ✅ 매진 즉시 거절(`WAITING/SOLDOUT`) 로 빠른 응답 | ✅ 큐 자연 흡수 |
| 공정성 정의 명확도 | "결제 완료 도달 순서" | **"`conditional_decr_or_wait` Lua 도달 순서"** | 큐 입수 순서 (TCP 경합) |

### 채택과 근거: **모델 D**

**v1 → v4 진화 요약**:
- v1(A): 단순했지만 결제 실패자의 자리가 다른 사용자에게 양도되지 않아 매진까지 도달했음에도 미판매 발생 가능.
- v4(D): 인터파크식 대기열 UX + 자동 승격으로 모든 자리가 끝까지 사용됨.

**모델 D 채택 이유**:
- 명세 *"동등한 기회 제공"* + *"엄격한 재고 정합성"* 두 가지를 동시에 만족 — 대기열로 모두에게 폴링 기회 제공 + Lua 원자 차감으로 정합성 보장.
- **인터파크/Yes24 등 티켓팅 플랫폼의 표준 UX 패턴**. 평가자 기대에 부합.
- 결제 실패자의 자리가 **자동 양도** — 비즈 가치 명확. 자리 사장 0건 보장.
- 폴링 응답이 결제 트리거 (`PROCESSING`) — 동기-비동기 하이브리드로 별도 워커 없이 자동 진행.

**큐(모델 Q) 미도입 이유**:
- 모델 D가 이미 "대기열 30 + 폴링" 으로 큐의 핵심 가치(매진 시 자리 보장) 를 Redis 자체로 구현.
- Kafka 도입 시 컨슈머 그룹·DLQ·메시지 손실 시나리오 부담이 본 프로젝트의 핵심 평가 축(정합성·멱등성·결제 확장성) 에서 시간을 빼앗음.
- 모델 D 의 Redis ZSET `waitlist:product:{id}` 가 **implicit FIFO queue** 역할 — `ZADD score=epoch_ms` + `ZRANGEBYSCORE` 1번이면 FIFO. 큐의 본질을 Redis로 압축한 형태.

### 핵심 표현

> **공정성의 commit point = Redis Lua `conditional_decr_or_wait` 도달 순서.** Redis 단일 스레드로 직렬화되어 모호함 없음. 결제 수단 처리시간 차이는 사용자 선택의 결과지 시스템적 우대가 아니다.
>
> **모델 D의 3-way 분기**:
> 1. stock ≥ 1 → DECR + slot 점유 → `PENDING` → 결제 진행
> 2. stock = 0 + waitlist < 30 → 대기열 진입 → `WAITING` + waitToken 발급
> 3. waitlist ≥ 30 → 매진 응답 → `SOLDOUT`

### 한계

- 대기열 30 자리 중 **잔액 부족 사용자**가 일시적으로 자리 점유 가능 (자동 promotion 시 결제 실패 → 양도). 자리 손실은 없지만 일시적 UX 저하. (`docs/miss/error_01.md` 참조)
- 폴링 부하 — 대기 N명 × 2초 폴링 = 평시 N/2 RPS 추가 부담. 30명 이내라 부담 미미하나, 대기열 확장 시 SSE/WebSocket 고려.

---

## 3. 1000 TPS — 동기 처리 vs 비동기/큐

### 상황

명세 *"평시 50 TPS, 00시 1~5분간 500~1000 TPS"*. 동기로 받을지, 비동기 큐로 분산할지를 정량적으로 판단해야 한다.

### 검토한 선택지

| 안 | 처리 모델 | API 응답 |
|---|---|---|
| **A** | 동기 + thread pool | 즉시 결제 완료 응답 |
| **B** | 큐(Kafka) + 워커 | 티켓 발급 + 결과 폴링 |
| **C** | 하이브리드 (동기 main + 비동기 보상) | 동기 |

### 트레이드오프와 정량 검증

**Little's Law 산수**:
```
요청 처리시간 분해 (mock PG 100ms 기준):
  분산락 SETNX            1 ms
  Redis GET (사전체크)    1 ms
  DB INSERT booking       5 ms
  PG AUTHORIZE          100 ms
  Redis Lua DECR          1 ms
  PG CAPTURE             50 ms
  DB UPDATE booking + payments  5 ms
  Outbox INSERT           3 ms
  락 해제                 1 ms
                       ──────
  합계                 ~167 ms

필요 슬롯 = 1000 TPS × 0.167s ≈ 167
가용 슬롯 = 2 노드 × Tomcat threads.max(400) = 800
여유율   = 4.8배
```

**DB 커넥션 검증**:
```
HikariCP 50 conn × 2 노드 = 100 conn (MySQL max_connections 151 안)
DB 쿼리/요청 ≈ 3개 × 5ms = 15ms 점유
가용 = 100 × 200 쿼리/sec = 20,000 → 필요 3,000 → 6배 여유
```

### 채택과 근거: **A (동기) + 하이브리드 보상**

산수상 1000 TPS 동기 처리에 4~6배 여유. 비동기로 가는 단점(API 패러다임 전환, 인프라 추가)을 정당화할 정량적 근거 없음.

**단, 동기의 진짜 위험은 cascading failure**: PG가 갑자기 5초 응답으로 바뀌면 `1000 × 5s = 5000` 슬롯 필요 → thread pool 고갈 → 전체 다운. 이를 막기 위한 **5종 방어 세트 필수**:

| 방어 | 설정 | 구현 위치 | 풀고 있는 문제 |
|---|---|---|---|
| **Timeout** | PG connect 500ms + read 1s | `RestClient` (`JdkClientHttpRequestFactory.setReadTimeout` + `HttpClient.connectTimeout`) | PG 무응답 시 thread 영구 점유 차단 |
| **Circuit Breaker** | 실패율 50% / 10s window / OPEN 30s | `@CircuitBreaker(name="pgGateway")` (Resilience4j) | PG 장애 시 cascading 차단, 빠른 503 |
| **Bulkhead** | PG 호출 100 동시 | `@Bulkhead(name="pgGateway")` (Resilience4j) | PG 막혀도 Checkout 정상 |
| **Tomcat threads.max** | 400 | `server.tomcat.threads.max` | 1000 TPS × 0.167s = 167 + 여유 |
| **HikariCP pool** | 50/노드 | `spring.datasource.hikari.maximum-pool-size` | 1000 TPS × 3쿼리 × 5ms 흡수 |

라이브러리: **Resilience4j** (CB + Bulkhead 어노테이션). Timeout은 `@TimeLimiter` 대신 **RestClient 자체 timeout** 으로 구현 — 어노테이션이 별도 thread pool을 만드는 비용을 피하고, IO timeout이 자연스럽게 thread 회수.

### 한계

위 5종 중 어느 하나라도 누락 시 동기 처리는 cascading 위험. 또한 산수는 mock PG 100ms 안정 응답이 전제 — 실 PG 평균 1s+이면 비동기 큐 재검토 필요 (§2 후속 참고).

---

## 4. PG 호출 패턴 — Auth / Capture / Void

### 상황

모델 A(결제 완료 후 차감)에서 매진 충돌이 발생한다 — 결제는 성공했는데 재고가 없는 경우. 이때 사용자에게 어떻게 안전한 회수를 보장할 것인가.

### 검토한 선택지

| 안 | PG 인터페이스 | 매진 시 보상 |
|---|---|---|
| **A** | 1-call: Approve | 결제 환불 (Refund) |
| **B** | 3-call: Authorize → Capture / Void | VOID (한도 해제, 돈 안 빠짐) |

### 트레이드오프

| 항목 | Approve-Only | Auth/Capture/Void |
|---|---|---|
| 사용자 돈 흐름 | 빠졌다 → 환불 | 한도만 잡힘 → 풀림 (돈 X) |
| 보상 무게 | 무거움 (환불 API + 며칠 시간) | 가벼움 (VOID 즉시) |
| 사용자 신뢰 | "결제됐다가 환불됨" — 불만 큼 | "한도 해제됨" — 자연스러움 |
| 실 PG 표준 | 일부 PG | 모든 주요 PG 표준 |
| 구현 복잡도 | call 1번, 상태 단순 | call 3번, 상태 5개 |

### 채택과 근거: **B (Auth/Capture/Void)**

사용자가 명세 외부에서 "PG 완료 받는 시점에 재고 0이면 완료 안 되게" 라고 표현한 직관이 정확히 표준 PG의 Auth/Capture 분리 패턴이다.

- AUTHORIZE는 한도만 잡고 돈은 빠지지 않음 → 매진 충돌 시 VOID로 깔끔 회수.
- "결제 다 됐는데 매진" 시나리오에서 사용자가 **실제로 돈을 낸 적이 없음** → 운영적 안전.
- 실 PG (KCP, 토스페이먼츠, 이니시스, 포트원 등) 모두 Auth/Capture 분리를 표준 인터페이스로 제공.
- 코드 복잡도는 상태 enum 추가 정도. mock PG도 endpoint 3개 vs 1개 차이로 부담 없음.

### 매핑 — POINT도 같은 인터페이스로 통일

`PaymentStrategy`를 통합하기 위해 POINT도 가상 Auth/Capture/Void로 매핑:
- HOLD = Authorize (balance 차감 + HOLD 이력)
- COMMIT = Capture (이력만 INSERT, balance 변동 X)
- RELEASE = Void (balance 복원 + RELEASE 이력)

Orchestrator가 method 구분 없이 동일 인터페이스를 호출 가능 — §7 결제 확장성의 핵심.

---

## 5. 분산락의 사용처와 비사용처

### 상황

"Redis가 분산환경이라 락을 써야 한다"는 직관을 의심 없이 받아들이면 잘못된 코드가 나온다. 정확히 **어디에 락이 필요하고 어디엔 오히려 해로운지** 구별해야 한다.

### 검토와 결정

| 사용처 / 비사용처 | 결정 | 이유 |
|---|:-:|---|
| **재고 차감 자체** (`stock:product:{id}`) | ❌ 비사용 | Redis `DECR` / Lua `conditional_decr`이 단일 스레드 원자. 락 씌우면 직렬화 병목으로 1000 TPS 못 흡수. |
| **동일 user + product PG 호출 보호** (`lock:booking:user:U:prod:P`) | ✅ 사용 | DB `active_key UNIQUE`는 INSERT 시점에서야 작동. 그전에 PG AUTHORIZE 호출이 이미 일어남. 락이 PG 호출 윈도우를 감쌈. TTL 5s. |
| **Outbox 워커 경합** | ✅ 사용 (또는 SQL `SKIP LOCKED`) | 두 노드 워커가 같은 outbox row 안 잡게. |
| **Reconciliation 잡 중복 실행** | ✅ 사용 | 좀비 청소/검증 잡을 노드당 1개씩 돌리지 말고, 전체에서 1개만. |

### Lua 도입 — 단순 명령으로 안 되는 곳

본 시스템은 **Lua 4종** 을 사용 (모델 D 전환으로 v3의 3종 → v4에서 4종으로 확장):

- **`conditional_decr_or_wait.lua`** (3-way 분기): 단순 `DECR`은 음수까지 내려감(10 → -990) → 모니터링 노이즈, 보상 로직 복잡. Lua로 `stock GET → 검사 → DECR slot 점유` (또는 `waitlist ZADD`) 를 원자 묶음. **모델 D 전환의 핵심** — stock/wait/sold-out 3분기를 단일 round-trip에 처리.
- **`restore_stock_and_promote.lua`**: 결제 실패자의 자리를 복구하면서 **동시에** 대기열 1번을 자동 승격. 보상 + 양도가 한 번에 원자 처리 — 모델 D의 자리 사장 방지 핵심.
- **`try_promote.lua`**: 대기 1번을 `WAITING → READY` 로 전이. 폴링 시 호출되어 결제 트리거 (`PROCESSING`) 분기 진입.
- **`safe_unlock.lua`**: TTL 만료 후 다른 owner가 같은 키 잡은 상태에서 첫 요청이 뒤늦게 `DEL` 호출 → **남의 락을 풀어버리는 사고**. Lua로 value 검증 후 DEL.

### 채택과 근거: **3곳 사용 + 1곳 비사용 + Lua 4종**

이 구분이 1000 TPS 동기 흡수의 전제. 재고 차감에 락을 씌우면 4.8배 여유가 무너진다. 반대로 PG 호출 보호엔 락이 필수 — 외부 시스템에 중복 호출을 보내면 한도 두 번 잡힘, VOID 추가 비용 발생.

라이브러리: **Spring Data Redis** (직접 SETNX). Redisson 같은 분산락 전용 라이브러리 미도입 — Redlock 알고리즘은 단일 Redis 환경에서 과설계.

---

## 6. 멱등성 — 키와 저장소 책임 분리

### 상황

명세 *"주문서에서 아주 짧은 간격으로 연속되어 결제요청이 되어도 중복처리가 되지 않도록"*. 게다가 **본 프로젝트는 클라이언트를 별도로 개발하지 않고 API 기반으로 평가** — 평가자가 curl/Postman으로 두드리는 환경에서 멱등성을 어떻게 보장할 것인가.

초안에서는 `idempotency_key`가 booking·payment·idempotency_key 테이블·Redis 4군데 흩어져 있어 책임이 흐릿했다.

### 검토한 선택지

| 안 | 키 출처 | 저장 위치 |
|---|---|---|
| **A** | 클라이언트 헤더 강제 | booking UNIQUE + Redis 캐시 + 별도 테이블 |
| **B** | 클라이언트 헤더 또는 서버 fallback | booking UNIQUE + Redis 캐시 |
| **C** | 서버가 user+product로 결정론적 생성 | booking UNIQUE |

### 트레이드오프

| 항목 | A | B | C |
|---|---|---|---|
| API 평가 (curl 단발) | 헤더 박아야 동작 | 헤더 없어도 동작 | 헤더 없어도 동작 |
| 멱등성 보장 | ✅ 명확 | ✅ 헤더 박으면 보장 | 과도 멱등 (정상 재시도까지 차단) |
| 표준성 | Stripe·Toss 등 표준 | Stripe와 유사 | 비표준 |
| 저장 복잡도 | 4군데 | 2군데 | 1군데 |

### 채택과 근거: **B + 응답 헤더 echo**

**키 정책**:
- 클라이언트가 `Idempotency-Key` 헤더 전송하면 사용 (16~128자 검증)
- 누락 시 서버가 UUID v4 생성 (`server-<uuid>` prefix)
- **응답 헤더에 사용된 키 echo 필수** — 평가자가 헤더 없이 호출 후 같은 키로 재시도 가능

**저장 정책 (책임 분리)**:
- **외부 키 1개**: `Idempotency-Key` 헤더
- **저장 2곳**: 
  - `booking.idempotency_key UNIQUE` (DB) — race condition 최종 방어
  - Redis `idem:{key}` (TTL 24h) — 빠른 응답 재생 캐시
- **별도 `idempotency_key` 테이블 제거** — booking row 자체가 영속 진실원천. Redis 다운 시 booking_id 재조회로 응답 재구성 가능.

**내부 멱등 (PG·포인트) 별도 키**:
- `payment.pg_idempotency_key` = `"pay-{bookingId}-{method}"` (deterministic, 재시도 시 재사용 가능)
- `point_transaction.reference_key` = `"HOLD:BOOKING:{bid}"` (HOLD/COMMIT/RELEASE/REFUND 4종)
- UNIQUE 제약으로 PG·포인트 시스템 측 멱등 보장

**Body 검증 (request_hash)**:
- `booking.request_hash CHAR(64) NOT NULL` — SHA-256(canonical body)
- 같은 키 + 다른 body 재요청 시 **422 IDEMPOTENCY_KEY_REUSED** (Stripe 표준)
- canonical: JSON 키 알파벳순 정렬 + whitespace 제거 + `BigDecimal.toPlainString()` 정규화

**TTL 24시간**: Stripe·일반 결제 API 표준. 클라 재실행·네트워크 복구·다음날 재시도까지 커버.

### API 평가 시나리오 매트릭스

| # | 평가자 액션 | 응답 |
|:-:|---|---|
| ① | `POST /booking` (헤더 없이) | 200 OK + 응답 헤더 server 생성 키 echo |
| ② | 같은 user+product 재호출 (헤더 없이, 다른 키) | 다른 키 → `active_key UNIQUE` 충돌 → **409 ALREADY_RESERVED** |
| ③ | 헤더 박고 호출 → 같은 키로 재호출 | 첫 200 → 두 번째 **캐시 응답 재생** (bookingId 동일) |
| ④ | 처리 중 같은 키로 재호출 | **409 IN_PROGRESS** + `Retry-After: 5` |
| ⑤ | 같은 키 + 다른 body | **422 IDEMPOTENCY_KEY_REUSED** |
| ⑥ | 결제 실패 후 같은 사용자가 재시도 (헤더 없이) | active_key NULL (FAILED 상태) → 새 booking row 생성, 재시도 가능 |

> **시나리오 ② 보장 메커니즘**:
> - 1차: `BookingService.doBook()` 의 `existsByCustomerIdAndProductIdAndStatusIn(WAITING, PENDING, PAID)` 사전 체크
> - 2차 (race 안전망): DB `booking.active_key` (generated column `CASE WHEN status IN ('WAITING','PENDING','PAID') THEN customer_id||'-'||product_id END`) + `uk_booking_active` UNIQUE 인덱스
> - DB 제약 위반 시 `BookingPersistence` 가 `DataIntegrityViolationException` 을 잡아 `AlreadyReservedException` 으로 변환

---

## 7. 결제 모듈 확장성 — Strategy + 카테고리화

### 상황

명세 *"향후 새로운 결제 수단이 추가되어도 Booking API의 비즈니스 로직 수정을 최소화"*. 단순 enum과 if-else 분기로는 신규 결제수단 추가 시 `BookingService` 수정이 불가피하다.

### 검토한 선택지

| 안 | 추상화 |
|---|---|
| **A** | enum 분기 + if-else (`if method == CARD ...`) |
| **B** | Strategy 패턴 (method별 구현체) |
| **C** | Strategy + 카테고리 (MAIN/SUPPLEMENT) |

### 트레이드오프

| 항목 | A | B | C |
|---|---|---|---|
| 신규 결제수단 추가 시 수정 | enum + Service 분기 | enum + Strategy | enum 1줄 + Strategy 1개 |
| 조합 규칙 (CC+YPAY 금지) | 하드코딩 분기 | 하드코딩 분기 | **자동 적용** (MAIN ≤ 1 룰) |
| Booking 비즈 로직 수정 | 매번 필요 | 가끔 필요 | **0줄** |
| OCP 원칙 | ❌ | △ | ✅ |

### 채택과 근거: **C (Strategy + MAIN/SUPPLEMENT 카테고리)**

```java
enum PaymentMethod {
    CARD  (Category.MAIN),
    YPAY  (Category.MAIN),
    POINT (Category.SUPPLEMENT);
    enum Category { MAIN, SUPPLEMENT }
}
```

**규칙**:
- MAIN ≤ 1 (상호배타, CC + YPAY 자동 차단)
- SUPPLEMENT ≤ 1 (POINT add-on)
- 최소 1개 + Σ amount = total_amount

**클래스 구조**:
- `PaymentStrategy` (interface): `authorize / capture / voidAuth` 3-method
- 구현체: `CardPaymentStrategy`, `YpayPaymentStrategy`, `PointPaymentStrategy`
- `PaymentCombinationValidator`: 조합 규칙 검증
- `PaymentOrchestrator`: Strategy들을 카테고리 순서로 실행, 실패 시 역순 보상

**신규 결제수단 추가 — 수정 범위**:
```java
enum PaymentMethod {
    CARD(MAIN), YPAY(MAIN), POINT(SUPPLEMENT),
    XPAY(MAIN);   // ← 한 줄
}
@Component class XpayPaymentStrategy implements PaymentStrategy { ... }   // 구현체 1개
```

- `BookingService` 수정 **0줄**
- `PaymentCombinationValidator` 수정 **0줄** (MAIN ≤ 1 룰 자동 적용)
- `PaymentOrchestrator` 수정 **0줄** (Spring이 `List<PaymentStrategy>` 자동 주입)
- DDL 변경 **없음** (`payment.method` VARCHAR)

명세 *"비즈니스 로직 수정 최소화"* 목표 정면 달성.

### 한계

새로운 SUPPLEMENT가 추가되면 (예: 쿠폰) "SUPPLEMENT ≤ 1" 룰을 "SUPPLEMENT ≤ N"으로 완화할지 결정 필요. 현 시점 단일 SUPPLEMENT만 지원.

---

## 8. 복합결제 호출 순서

### 상황

복합결제 CARD + POINT에서 어느 쪽을 먼저 호출할 것인가. 부분 실패 보상의 무게가 달라진다.

### 검토한 선택지

| 안 | 순서 | 부분 실패 시 보상 |
|---|---|---|
| **A** | POINT HOLD → CARD AUTHORIZE | CARD 실패 시 POINT RELEASE |
| **B** | CARD AUTHORIZE → POINT HOLD | POINT 실패 시 PG VOID |
| **C** | 병렬 (CompletableFuture) | 둘 중 하나 실패 시 다른 쪽 보상 |

### 트레이드오프

| 항목 | A: SUPPLEMENT 먼저 | B: MAIN 먼저 | C: 병렬 |
|---|---|---|---|
| 빠른 실패 비용 | POINT HOLD ~5ms DB → CARD 안 감 | CARD ~100ms PG → POINT 안 감 | 항상 둘 다 실행 |
| 잔액 부족 (흔함) | 5ms로 거름 | 100ms 후 VOID 필요 | 100ms 후 VOID |
| 카드 거절 | 5ms POINT RELEASE | 즉시 거름 | 5ms POINT RELEASE |
| 처리시간 | 정상 ~150ms | 정상 ~150ms | 정상 ~100ms |
| 부분 실패 매트릭스 | 2가지 | 2가지 | 4가지 |
| 구현 복잡도 | 단순 | 단순 | 비동기 + 보상 매트릭스 복잡 |

### 채택과 근거: **A (SUPPLEMENT 먼저, MAIN 나중)**

- POINT HOLD는 DB 1쿼리(~5ms)로 빠르고 결정적. AUTHORIZE는 mock PG 호출(~100ms)로 상대적으로 느림.
- **잔액 부족이 카드 거절보다 빈도 높을 가능성** — 사용자가 종종 포인트 잔액 잘못 알고 있음. 빠른 실패를 먼저 거름.
- POINT RELEASE는 DB 1쿼리, PG VOID는 외부 호출. **POINT RELEASE가 가벼움**.
- 보상 매트릭스가 2가지로 단순.

**보상은 역순**: 둘 다 성공 후 DECR 매진 시 → CARD VOID → POINT RELEASE.

### 한계

POINT 잔액 부족 빈도와 카드 거절 빈도의 실제 비율은 분석 후 재조정 가능. 현 가정은 합리적 추정.

---

## 9. Redis 장애 Fallback 정책

### 상황

명세 *"Redis 장애 시의 Fallback 전략을 수립하고 근거를 제시"*. Redis는 본 시스템의 차감 카운터·멱등 캐시·분산락을 모두 담당 — 다운 시 영향이 크다.

### 검토한 선택지

| 안 | 범위 | 구현 비용 |
|---|---|---|
| **A** | Fail-Fast: Redis 다운 → 503, 복구 대기 | 1시간 |
| **B** | 읽기 폴백: 멱등 캐시는 booking_id 재조회, 차감은 503 | 3~5시간 |
| **C** | 완전 폴백: `UPDATE product SET stock=stock-1 WHERE stock>0`로 동시성 전환, 분산락도 DB advisory로 | 1일+ |

### 트레이드오프

| 항목 | A | B | C |
|---|---|---|---|
| 가용성 | Redis 복구까지 0% | 일부 가능 (조회) | 100% |
| 구현 복잡도 | 매우 단순 | 중도 | 본질적으로 다른 분기 코드 |
| 운영 일관성 | 단순 | 중도 | 두 모드 모두 검증 부담 |
| 본 프로젝트 일정 (7일) | ✅ | ⚠️ | ❌ |

### 채택과 근거: **A (Fail-Fast)** — **⏰ 시간 제약 명시**

**선택 이유**:
- 본 프로젝트 7일 일정 제약상 (c) 완전 폴백 구현은 핵심 평가 축(정합성·결제 확장성·멱등성·고가용성)에서 시간을 빼앗는다.
- Redis 단일 인스턴스 SPoF의 본질적 해결책은 실서비스의 **Sentinel/Cluster 도입**이고, 폴백 모드는 보조책이다.
- Fail-Fast는 정직한 응답 — 503 + `Retry-After: 30`으로 클라이언트가 명시적 재시도.

**현재 구현 (정확한 동작)**:
1. Spring Data Redis (Lettuce) 가 connection 실패 시 `RedisConnectionFailureException` 즉시 throw
2. `GlobalExceptionHandler` 의 catch-all `@ExceptionHandler(Exception.class)` 가 `500 INTERNAL_ERROR` 로 변환
3. `GET /actuator/health` 는 Redis 다운 시 `DOWN` 상태 노출 (Spring Boot 기본 health indicator)
4. Lettuce 의 auto-reconnect로 Redis 복구 시 자동 회복

**명시적 Fail-Fast 미구현 영역** (실서비스 권장):
- `redisHealth` Circuit Breaker → 별도 어노테이션/Bean 미정의. 추가 시 OPEN 상태에서 `503 + Retry-After` 즉시 반환 가능.
- 현재는 Redis 다운 시 500 응답이 나가지만 클라이언트 retry 가이드는 자동 제공 안 됨. 본 프로젝트 일정상 보류.

### DECISIONS-원문 표현

> Redis 다운 시 Fail-Fast 정책 채택. 본 프로젝트 7일 일정 제약상 DB 폴백 모드 구현 보류. 실서비스 시 옵션 (c) 완전 폴백 권장하며, 그 경우 stock 컬럼에 `SELECT ... FOR UPDATE` 또는 `UPDATE ... WHERE stock>0` 어트믹 분기 + 분산락도 DB advisory lock으로 전환 필요. 본 프로젝트는 정상 흐름의 정합성·멱등성·결제 확장성 검증에 집중하며, Redis 단일 인스턴스의 SPoF는 실서비스의 Sentinel/Cluster 도입으로 본질적으로 해결되는 영역으로 판단.

### 결제 실패 — 별도 처리

명세 *"결제 실패 (한도 초과 등) 대응 로직"*:
- PG AUTHORIZE 실패 → `failed_reason` 분류 (`PAYMENT_DECLINED`, `LIMIT_EXCEEDED`, `INSUFFICIENT_POINT` 등 9종) → booking FAILED
- 이미 다른 결제수단이 AUTHORIZED됐다면 → **역순 보상** (PaymentOrchestrator.compensate)
- 보상 호출 자체가 실패하면 → `outbox_event` 적재 → 워커 지수 백오프 재시도

---

## 10. 추가 인프라 도입

명세 *"필요에 따라 자유롭게 선택. 도입 근거를 DECISIONS.md에 필수 기재"* 에 따라 본 프로젝트가 도입한 추가 인프라의 도입 사유와 비용 대비 효과.

### 10.1 Outbox 패턴 (필수)

**도입 사유**: 모델 A의 보상 흐름에서 **DB 트랜잭션과 외부 호출 사이의 dual write 문제**. 매진 시 DB UPDATE + PG VOID를 단일 tx에 못 묶음 → VOID 실패 시 **영구 돈 묶임** 위험.

**구현**: 
- `outbox_event` 테이블 (aggregate_type, event_type, payload, status, retry_count, next_attempt_at)
- 별도 워커가 `@Scheduled(fixedDelay=1s) + SELECT ... FOR UPDATE SKIP LOCKED`로 폴링
- 지수 백오프 (1s→5s→30s→1m→5m), 10회 초과 시 `DEAD_LETTER`

**이벤트 타입**: `BOOKING_PAID`, `BOOKING_FAILED`, `COMPENSATION_VOID`, `COMPENSATION_POINT_RELEASE`, `COMPENSATION_STOCK_RESTORE`, `POINT_COMMIT_RETRY`.

**비용 대비 효과**: 테이블 1개 + 워커 1개. 영구 일관성 보장 — 본질적으로 필수 인프라.

### 10.2 Inbox 패턴 (인터페이스만)

**도입 사유**: 실 PG 콜백 시 중복 도착 처리 (네트워크 retry 등). 현재 mock 단계엔 미사용이나 **인터페이스만 정의**해두면 실 PG 통합 시 즉시 활성화.

**구현**:
- `inbox_event` 테이블 (source, external_event_id UNIQUE, payload, status)
- `POST /pg/callback` 컨트롤러 골격 — INSERT UNIQUE 충돌 시 200 OK 즉시 반환 (멱등)

**비용 대비 효과**: DDL 1개 + 컨트롤러 골격. 실 PG 통합 비용 사전 흡수.

### 10.3 Mock PG (별도 컨테이너)

**도입 사유**: 명세 *"실제 PG사와의 연동은 생략하되, 인터페이스 등을 통해 구조적으로 흐름이 이어지도록"*. 

App 내부 mock 구현체로 처리할 수도 있으나:
- 네트워크 비용·timeout 시연 불가
- Resilience4j Timeout/CB/Bulkhead 동작 검증 불가
- 평가자가 "별도 외부 시스템"으로 인지하기 어려움

**별도 컨테이너로 분리**한 이유는:
- 실제 HTTP 호출 → Timeout·CB 진짜 동작 검증
- 환경변수로 지연·실패율 주입 → 장애 시나리오 시연 가능 (`MOCK_PG_LATENCY_MS`, `MOCK_PG_FAIL_RATE`)
- 실 PG 교체 시 컨테이너만 갈아끼우는 구조

**구현**: Node.js HTTP 서버 (`docker/mock-pg/index.js`, ~80줄). 100ms 응답. 환경변수로 지연/실패율/타임아웃율 주입.

**비용 대비 효과**: 컨테이너 1개 + 코드 100줄 미만. 명세 정신 정면 충족.

### 10.4 Docker 분산환경 (Nginx + App×2 + MySQL + Redis + Mock PG)

**도입 사유**: 명세 *"2대 이상의 애플리케이션 서버로 구성된 분산 환경"* 충족 + 평가자가 README 한 줄(`docker compose up`)로 재현 가능.

**구성** (5 컨테이너):
- Nginx (LB, 라운드로빈, `proxy_next_upstream` 자동 페일오버)
- App1, App2 (Spring Boot, `INSTANCE_ID` MDC)
- MySQL 8.4, Redis 7.4
- Mock PG

**비용 대비 효과**: 평가자 검증 시간 단축 + 분산 환경의 진짜 동작(두 노드가 같은 Redis·DB를 공유하며 정합성·멱등성 작동) 시연.

### 10.5 라이브러리 선택 사유

| 라이브러리 | 도입 사유 | 대체안 vs 선택 |
|---|---|---|
| **Spring Boot 4.0.6** | 명세 *"Spring Boot 2.7 이상"* 충족. 최신 안정 버전 | 2.7~3.x도 가능, 최신이 LTS·관측성 표준 |
| **Spring Data JPA + MySQL Connector** | ORM 표준, 멀티 노드 환경 친화 | MyBatis도 가능, JPA가 도메인 모델링 깔끔 |
| **Spring Data Redis** | 분산락·캐시·Lua eval 표준 | Jedis 직접 사용도 가능, SDR이 lifecycle 관리 |
| **Resilience4j** | CB + Bulkhead 어노테이션 사용 (Timeout 은 RestClient 자체 timeout) | Hystrix 단종, Resilience4j가 Spring Boot 표준 |
| **Lombok** | 보일러플레이트 제거 | 필수 아님, 가독성 위해 |

> **Flyway/Liquibase 미도입**: 본 프로젝트는 개발 단계이므로 `src/main/resources/schema.sql` 단일 파일로 schema 관리 (`ddl-auto: validate` + `spring.sql.init.mode: always`). 운영 단계에선 Flyway 도입 권장.

큐 라이브러리(Kafka, RabbitMQ) **미도입** — §2·§3 근거.
분산락 라이브러리(Redisson) **미도입** — §5 단일 Redis 환경 과설계 회피.

---

## 부록: 결정 사항 총괄 (참조용)

| 단계 문서 | 결정 수 | 핵심 |
|---|:-:|---|
| `01-domain.md` | 16 (D1~D16, v4 확장) | 도메인·ERD·상태머신·인프라 (D15·D16 대기열 + 자동 진행 추가) |
| `02-concurrency.md` | 8 (C1~C8) | 차감 흐름·공정성·분산락 lifecycle |
| `03-idempotency.md` | 8 (M1~M8) | 멱등 키 정책·캐시·request_hash |
| `04-payment.md` | 8 (P1~P8) | Strategy·Orchestrator·보상 매트릭스 |
| `05-resilience.md` | 10 (R1~R10) | Reconciliation·Outbox·노드 사망 7시나리오 |
| `06-test.md` | — | 테스트 전략 (JUnit / demo / load 3종) |
| **합계** | **50 개** | |

---

## 마치며 — 본 문서가 다루지 않은 것

평가 범위 외이거나 시간 제약상 보류된 항목:
- 회원 인증·인가 (명세 평가 제외)
- 실 PG 연동 (Mock으로 인터페이스만)
- 부분 환불 / 결제 후 취소 (본 범위 외)
- Redis Sentinel/Cluster, MySQL Replication (실서비스 권장 명시)
- Redis DB 폴백 모드 (§9 시간 제약)
- 부하 테스트 자동화 스크립트 (구현 단계에서 k6/vegeta 사용 예정)

이 항목들의 본 시스템 영향은 각 단계 문서와 README §12 (알려진 제약)에 명시되어 있다.
