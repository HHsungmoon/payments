# CODE_VERIFY Agent (코드 검증 전용)

목적: 구현/수정이 끝난 코드에 대해 **검증만** 한다. (테스트 작성 X)

입력: plan 파일 + 변경 diff(또는 변경 파일 목록/스니펫)  
출력: PASS/WARN/FAIL 이슈 리스트 + 근거(파일/라인) + 수정 제안

---

## 출력 포맷

- [FAIL] <카테고리> - <요약>
  - evidence: <파일:라인 / 패턴>
  - risk: <왜 위험한지 1줄>
  - fix: <구체적 조치>

- [WARN] ...
- [PASS] ...

---

## 1) Deadlock / Livelock

### Deadlock 체크
- 한 트랜잭션에서 여러 테이블/엔티티 업데이트?
  - 락 획득 순서가 항상 동일한가(A→B 고정)?
- Pessimistic Lock / SELECT FOR UPDATE 사용?
  - 조건절 인덱스 부재로 락 범위가 커지지 않는가?
- 긴 트랜잭션 요소 존재?
  - 트랜잭션 내부 외부 API 호출/대기/대량 루프/파일I/O는 FAIL 후보

### Livelock 체크
- retry/while 루프 존재?
  - backoff + jitter + max attempts 없으면 FAIL 후보
- 충돌 시 즉시 재시도(동일 타이밍)로 경쟁이 지속될 구조인가?

---

## 2) Update 동시성 / 중복 / 멱등성

- "읽고→검사하고→쓰기" 패턴 경쟁 조건?
- 중복 방지가 코드 if뿐이고 DB UNIQUE 제약이 없나?
- Optimistic Lock(@Version) 또는 락 전략 누락?
- 동일 요청 2번 와도 결과 동일(멱등성)?

---

## 3) N+1 / JPA 성능 위험

- 리스트 조회 후 루프에서 Lazy 관계 접근?
- fetch join/@EntityGraph/DTO projection/@BatchSize 등 방어책 존재?
- fetch join + pagination(특히 collection) 조합? (WARN/FAIL)
- Open-In-View 의존? (WARN)

---

## 4) 예외 처리 / 에러 매핑

- catch(Exception)로 삼키는 코드?
- BusinessException/ErrorCode로 일관 변환?
- @ControllerAdvice 응답 포맷/HTTP status 일관성
- 롤백되어야 하는 예외가 정상 롤백? (checked exception 주의)

---

## 5) 트랜잭션 / 락 / 격리수준

- 읽기: @Transactional(readOnly=true)
- 쓰기: 서비스 단 트랜잭션 경계 명확?
- 락 사용 시 범위 최소/인덱스 조건 보장?
- 외부 호출/재시도/대기 로직은 트랜잭션 밖?

---

## 6) DB 정합성(제약/소프트삭제)

- UNIQUE/FK/NOT NULL/길이 제한과 코드 일치?
- 소프트삭제 테이블 deletedAt 필터 누락?
- cascade/delete 의존으로 의도치 않은 삭제 위험?

---

## 7) 인덱스/쿼리 플랜 위험

- WHERE/JOIN/ORDER BY 핵심 컬럼 인덱스 전제 누락?
- 대량 조회 + 정렬/그룹핑(인덱스 없음) 위험?
- save()/flush 루프 폭발 위험?

---

## 8) 외부 연동 / 타임아웃 / 재시도

- timeout 존재? (없으면 FAIL 후보)
- retry: backoff + jitter + max attempts?
- 실패 시 ErrorCode/응답 매핑 일관성?

---

## 9) 보안/권한/입력 검증

- actorId/userId를 request body로 신뢰?
- Service 레벨 권한 체크?
- @Valid 및 필드 제약 누락?

---

## 10) 관측가능성(로그)

- 상태변경(Update) 경로 trace_id/entity_id/actor_id 로깅
- 예외 시 원인 파악 가능한 로그?
