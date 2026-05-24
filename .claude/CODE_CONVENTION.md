# CODE_CONVENTION

> 본 프로젝트는 모델 D (선점 후 결제 + 대기열) 기반. 설계 근거: `docs/design/01~05.md`, `README.md`, `DECISIONS.md`.
> 패키지 구조는 README §10 참조.

## 0) 금지

- Entity에 Setter 금지 — 상태 변경은 도메인 메서드로
- DB tx 안에서 외부 호출(PG / Redis 카운터) 절대 금지 — tx 분리 필수
- 재고 차감에 분산락 사용 금지 — Redis Lua 원자 명령으로 직렬화
- 단순 `DECR` 사용 금지 — 음수 가능, 항상 Lua `conditional_decr_or_wait` 사용
- 무의미한 주석 금지 (`/** */` Javadoc 금지, 변수명으로 명백한 건 주석 X)
- 인증 관련 코드 X — 명세 §4 평가 제외

## 0) 권장

- record 적극 사용 (Properties, Request, Response DTO, 내부 Result 객체)
- 멱등 키 기반 보상 호출 (PG `pg_idempotency_key`, 포인트 `reference_key`)
- 보상 호출 실패 → Outbox에 적재 → 워커 재시도
- 분산락은 `safe_unlock.lua`로 value 검증 후 해제

## 1) 패키지 구조

```
com.platform.payments/
├── booking/                ← BookingService, Controller, WaitPollingService
├── payment/                ← PaymentStrategy, Orchestrator, Validator
├── stock/                  ← StockService (Redis Lua wrapper)
├── promotion/              ← 대기자 승격 처리
├── lock/                   ← DistributedLockService
├── outbox/                 ← Outbox 워커
├── reconciliation/         ← 좀비 청소·검증·대기 만료 워커 3종
├── pg/                     ← PaymentGateway 인터페이스 + MockPgClient
├── idempotency/            ← Idem 키 lookup, 응답 캐시, request_hash
├── inbox/                  ← PG 콜백 인터페이스 (mock 미사용)
├── async/                  ← AsyncConfig (paymentExecutor)
└── common/                 ← properties, config, error, advice
```

각 도메인은 `service / controller / repository / entity / dto` 같은 sub-layer 분리 안 함. 평면 구조 + 클래스 책임으로 구분.

## 2) Record 정책

- **Properties** (`common/properties/*Properties`): `@ConfigurationProperties` + record
- **Request DTO**: record + `@Valid` 검증 어노테이션
- **Response DTO**: record (정적 팩토리 `of()` 권장)
- **내부 Result**: record (예: `StockReserveResult`, `AuthorizeResult`)
- **JPA Entity**: class (record 불가). `@Entity` + `@NoArgsConstructor(PROTECTED)` + Builder

## 3) 주석 정책

- `/** */` Javadoc 금지 — 모든 주석은 `//`
- 클래스 상단 한 줄 (의도만)
- 변수명으로 명백하면 주석 없음
- 필요한 곳은 필드 옆 inline `//` 한 줄
- 설계 문서 링크는 코드 안에 박지 않음 (DECISIONS/design 폴더가 별도)

예시:
```java
// 대기열 시스템 (v4)
@ConfigurationProperties(prefix = "app.waitlist")
public record WaitlistProperties(
    int maxSize,                          // 대기열 최대 인원
    int holdTtlSeconds,
    int slotTtlSeconds,
    int pollingIntervalMs                 // 클라이언트 권장 polling 주기
) {}
```

## 4) Entity 규칙

- `@Entity @Table @Getter @NoArgsConstructor(PROTECTED) @Builder`
- 상태 변경은 도메인 메서드로 (`markPaid(Instant)`, `markFailed(FailureReason)`)
- 연관관계는 기본 `LAZY`
- Enum은 `@Enumerated(STRING)`
- `BaseTimeEntity` 같은 글로벌 베이스 X — 각 Entity에 `@CreationTimestamp` / `@UpdateTimestamp` 또는 `created_at` 컬럼 직접

## 5) Service 규칙

- `@Service @RequiredArgsConstructor @Slf4j`
- 클래스 기본 `@Transactional(readOnly = true)`
- 쓰기 메서드만 `@Transactional`
- 트랜잭션 짧게 — 외부 호출(PG, Redis Lua) 절대 안 묶음
- 비동기 결제: `@Async("paymentExecutor")`

## 6) Controller 규칙

- `@RestController @RequiredArgsConstructor @RequestMapping("/{domain}")`
- 응답이 다형이라 표준 ResponseDTO 강제 X
  - `200 PAID` / `200 WAITING` / `200 PROCESSING` 각각 다른 body 구조 가능
- 에러는 `@ControllerAdvice`에서 `FailureReason` → HTTP status 매핑
- Idempotency-Key 헤더는 인터셉터 또는 컨트롤러에서 추출 → 서비스 위임

## 7) Exception / Error

- `FailureReason` enum 사용 (v4 기준 10종: PAYMENT_DECLINED, INSUFFICIENT_POINT, SLOT_TIMEOUT 등)
- 도메인 예외: `BookingException(FailureReason reason, String message)`
- `@ControllerAdvice` 에서 → HTTP status + 응답 body 매핑
- 응답 코드 표는 README §6.4 그대로

## 8) Redis Lua 사용 정책

- 스크립트는 `src/main/resources/lua/*.lua` 파일로 분리
- `DefaultRedisScript<T>` 로 로드, Spring 빈으로 등록
- v4 4개 스크립트:
  - `conditional_decr_or_wait.lua` — 3-way 분기
  - `restore_stock_and_promote.lua` — 보상 + 다음 승격
  - `try_promote.lua` — READY→PROCESSING 원자 전환
  - `safe_unlock.lua` — 락 안전 해제
- 인라인 Lua 문자열 금지 — 항상 파일 로드

## 9) 트랜잭션 경계

`docs/design/04-payment.md §6` 참조. 한 줄 요약:

```
[DB tx1] INSERT booking + payment(REQUESTED)
↓ tx 밖
[POINT HOLD] [PG AUTHORIZE]
↓
[DB tx2] UPDATE payment AUTHORIZED
↓ tx 밖
[Redis Lua DECR] [PG CAPTURE] [POINT COMMIT]
↓
[DB tx3] UPDATE booking PAID + outbox + DEL hold
```

## 10) 테스트 (시간 허락 시)

- 통합 테스트는 Testcontainers (MySQL + Redis)
- 동시성 테스트는 `@SpringBootTest` + `ExecutorService`로 N개 동시 호출
- Lua 스크립트는 Redis 임베디드 또는 Testcontainers Redis로 직접 검증
