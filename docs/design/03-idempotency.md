# 03. 멱등성 처리 (v4)

> **단계 목적**: 외부·내부 멱등 키 정책.
>
> **v4 변경 요지**: 대기 진입(WAITING)도 멱등 처리. POST /booking의 응답 종류가 3가지(PAID/WAITING/SOLD_OUT)로 늘어남에 따라 idem 캐시도 셋 다 저장. polling은 read-only처럼 보이지만 `try_promote.lua`가 상태 전환을 일으키므로 별도 멱등 보장.

---

## 1. 핵심 결정 요약

| ID | 결정 | 근거 |
|---:|---|---|
| **M1** | `Idempotency-Key` 헤더 누락 시 서버가 UUID v4 fallback 생성. 응답 헤더에 echo. | 클라이언트 미개발 환경 |
| **M2** | `booking.request_hash` SHA-256. 같은 키 + 다른 body → 422. | Stripe 표준 |
| **M3** | Redis `idem:{key}` TTL 24h. | Stripe 표준 |
| **M4** | 락 충돌 시 4분기 응답 (α/β/γ/γ'). | 클라이언트 명시적 재시도 |
| **M5** | 응답 캐시 시점: 응답 직전. 422·5xx는 캐시 안 함. | 정상 응답만 멱등 보장 |
| **M6** | IN_PROGRESS 식별: 분산락 존재 + idem 캐시 부재 | 별도 placeholder 안 씀 |
| **M7** | `payment.pg_idempotency_key` = `pay-{bookingId}-{method}` deterministic | retry 시 같은 키 재사용 |
| **M8** | `point_transaction.reference_key` = `{reason}:BOOKING:{bookingId}` | HOLD/COMMIT/RELEASE |
| **M9 (v4)** | **대기 진입(WAITING)도 idem 캐시 저장**. 같은 Idem-Key 재시도 시 기존 waitToken 그대로 반환. | 대기 진입의 중복 ZADD 방지 |
| **M10 (v4)** | **polling은 멱등 자동**. `try_promote.lua`의 READY→PROCESSING 전환은 1회성이라 race-safe. 두 번째 polling은 PROCESSING으로 보임. | Lua 원자성이 멱등 보장 |

---

## 2. Idempotency-Key 헤더 정책

### 2.1 요청 측

```
POST /booking
Idempotency-Key: 8f3e2c10-7b4a-4e91-9c2f-7e2a1d3b5f80   ← 선택
```

### 2.2 서버 처리

- 헤더 있으면 형식 검증 (16~128자)
- 누락 시 `server-<uuid>` 자동 생성
- 응답 헤더에 echo

### 2.3 API 평가 시나리오 (v4 갱신)

| # | 평가자 액션 | 응답 |
|:-:|---|---|
| ① | `POST /booking` (헤더 없이) | 200 PAID / 200 WAITING / 409 SOLD_OUT + 응답 헤더 server 생성 키 |
| ② | 같은 customer+product 재호출 (헤더 없이) | 다른 키 → application `existsByCustomerIdAndProductIdAndStatusIn` 사전 체크 → **409 ALREADY_RESERVED** (race 시 DB `uk_booking_active` 가 안전망) |
| ③ | 헤더 박고 호출 → 같은 키로 재호출 | 첫 응답 그대로 **캐시 재생** (PAID/WAITING/SOLD_OUT 어떤 것이든) |
| ④ | 처리 중 같은 키로 재호출 | **409 IN_PROGRESS** + Retry-After: 5 |
| ⑤ | 같은 키 + 다른 body | **422 IDEMPOTENCY_KEY_REUSED** |
| ⑥ | 결제 실패 후 같은 사용자가 재시도 (헤더 없이) | active_key NULL → 새 booking 시도 |
| ⑦ **(v4)** | **WAITING 응답 받은 후 같은 키로 다시 POST /booking** | **캐시 재생: 200 WAITING + 같은 waitToken** |
| ⑧ **(v4)** | **WAITING 응답 후 GET /booking/wait/{waitToken}** | 200 WAITING/PROCESSING/PAID/FAILED (실시간 상태) |

---

## 3. 요청 처리 흐름 (v4)

### 3.1 POST /booking 흐름

```
[A] Idem-Key 결정 (헤더 또는 server-UUID)
[B] canonical body → SHA-256 → request_hash
[C] Redis GET idem:{key}
     있음 → request_hash 비교
            일치 → 캐시 응답 그대로 재생 (PAID/WAITING/SOLD_OUT)
            불일치 → 422 IDEMPOTENCY_KEY_REUSED
     없음 → [D]
[D] 분산락 SETNX
     실패 → idem 캐시 재확인 → 있으면 재생, 없으면 409 IN_PROGRESS
     성공 → [E]
[E] EVAL conditional_decr_or_wait.lua
     "SLOT:N"        → [F1] 즉시 결제 흐름
     "WAITLIST:P"    → [F2] 대기 진입
     "FULL"          → [F3] 매진 응답
[F1] DB INSERT booking PENDING + 결제 흐름 → 200 PAID 또는 422 FAILED
[F2] DB INSERT booking WAITING + HSET wait:token → 200 WAITING + waitToken
[F3] 락 해제 → 409 SOLD_OUT
[G] 응답 직전: SET idem:{key} {status, body, request_hash} EX 86400
     (단 422·5xx·IN_PROGRESS는 캐시 안 함)
```

### 3.2 GET /booking/wait/{waitToken} 흐름

```
[A] waitToken 유효성 검증 (Hash 존재, 만료 X)
[B] EVAL try_promote.lua → 현재 상태 반환 (필요 시 READY→PROCESSING 전환)
[C] 분기별 응답:
     WAITING    → ZRANK 조회 후 position 반환
     PROCESSING → "처리 중" 응답
     PAID       → bookingId 반환 (HGET wait:token bookingId)
     FAILED     → reason 반환
     NOT_FOUND  → 410 Gone (만료됨)
```

**polling 멱등성**: try_promote.lua가 READY→PROCESSING 전환을 원자로 수행. 동시 polling이 와도 한 번만 전환됨. PAID/FAILED는 idempotent 응답.

---

## 4. request_hash canonical form

실제 구현 (`IdempotencyService.canonicalMapper`):
1. JSON 키 알파벳순 정렬 (`SORT_PROPERTIES_ALPHABETICALLY` + `ORDER_MAP_ENTRIES_BY_KEYS`)
2. null 필드 제거 (`JsonInclude.Include.NON_NULL`)
3. `WRITE_DATES_AS_TIMESTAMPS` disable (Instant 문자열 직렬화)
4. UTF-8 → SHA-256 hex

> 본 시스템 금액은 모두 `Long` 사용. `BigDecimal` 정규화 불필요.

---

## 5. 응답 캐시 구조 (v4)

### 5.1 Redis 키 / 값

```
Key:   idem:{idempotency-key}
Value: CachedResponse record (cacheMapper 직렬화)
       {
         "status": 200,
         "body": "{ \"status\":\"WAITING\", \"waitToken\":\"wt_abc\", ... }",
         "request_hash": "3f2a1b...",
         "cached_at": "2026-05-24T00:00:00Z"
       }
TTL:   86400 (24h, Stripe 표준)
```

> `Idempotency-Key` 헤더 echo + `Retry-After` 헤더는 응답 시 `BookingOutput` + `BookingController.toResponse` 가 매번 부착 — 캐시 값에 미저장.

### 5.2 캐시 정책 (v4)

| 응답 상태 | 캐시? | 이유 |
|---|:-:|---|
| 200 PAID | ✅ | 정상 멱등 |
| 200 WAITING | ✅ | 대기 진입도 멱등 |
| 409 SOLD_OUT | ✅ | 매진 멱등 |
| 409 ALREADY_RESERVED | ✅ | 위반 멱등 |
| 409 IN_PROGRESS | ❌ | 처리 끝나면 다른 응답 가능 |
| 422 IDEMPOTENCY_KEY_REUSED | ❌ | 요청 자체 잘못 |
| 422 PAYMENT_DECLINED 등 | ❌ | 사용자 측 입력 정정 후 재시도 가능 |
| 5xx | ❌ | 재시도 가능해야 |

---

## 6. C5 락 충돌 분기 (v4)

| 분기 | 판정 | 응답 |
|---|---|---|
| **α** | 같은 Idem-Key, 처리 중 | 409 IN_PROGRESS + Retry-After: 5 |
| **β** | 다른 Idem-Key, 같은 customer+product 처리 중 | 409 IN_PROGRESS + Retry-After: 5 |
| **γ** | 같은 Idem-Key, 완료 | 캐시 응답 재생 (PAID/WAITING/SOLD_OUT) |
| **γ'** | 다른 Idem-Key, 같은 customer+product 활성 (WAITING/PENDING/PAID) | 409 ALREADY_RESERVED |

---

## 7. PG·포인트 멱등 키 (v3와 동일)

- `payment.pg_idempotency_key` = `pay-{bookingId}-{method}` deterministic
- `point_transaction.reference_key` = `HOLD:BOOKING:{bid}` / `COMMIT:BOOKING:{bid}` / `RELEASE:BOOKING:{bid}` / `REFUND:BOOKING:{bid}`
- UNIQUE 제약으로 멱등 보장

---

## 8. 정합성 방어선 종합 (v4)

| 자원 | 1차 | 2차 | 3차 |
|---|---|---|---|
| **외부 멱등** | Redis `idem:{key}` 24h + request_hash | `booking.idempotency_key UNIQUE` | — |
| **대기 진입 멱등** | idem 캐시 (WAITING 응답 저장) | `booking.wait_token UNIQUE` | — |
| **polling 멱등** | `try_promote.lua` 원자 전환 | — | — |
| **PG 호출 멱등** | 분산락 + Mock PG 캐시 | `payment.pg_idempotency_key UNIQUE` | Outbox COMPENSATION_* (Phase 1) |
| **포인트 멱등** | `existsByReferenceKey` 사전 조회 | `point_transaction.reference_key UNIQUE` | (합산 검증 워커는 향후 — Pt1 보류) |
| **재고** | Lua | `booking.PAID + PENDING` ≤ `stock_total` | Reconciliation (verifyConsistency) |
| **1인1상품** | application `existsByCustomerIdAndProductIdAndStatusIn` | `booking.active_key UNIQUE` (generated) | — |

---

## 9. 다음 단계로 넘기는 미결

| → 단계 | 미결 |
|---|---|
| **4 (결제 모듈)** | 비동기 결제 진행 (@Async). wait:token에 결제 결과 업데이트. |
| **5 (장애대응)** | 대기 중 idem cache 다운 시 폴백 (booking_id + wait_token 재조회). |
