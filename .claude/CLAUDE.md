## 사용 규칙 (Prefix Router)

메시지 맨 앞에 아래 Prefix 중 하나를 붙인다. Prefix에 따라 **읽을 문서 범위**를 제한한다.

| Prefix | 단계 | 하는 일 | 기본 읽는 문서 | 산출물 |
|---|---|---|---|---|
| [구조] | 계획 | README §10 + docs/design 기준으로 현재 구현/구조 점검, 어떤 파일을 [REUSE]/[MOD]/[NEW] 할지 결정 | `README.md` + `docs/design/01~05.md` + `docs/design/11-flow.md` | 작업 plan (in-chat) |
| [개발] | 구현 | plan 범위 안에서 컨벤션대로 코드 작성 (repo 스캔 금지) | `.claude/CODE_CONVENTION.md` + plan | 코드 변경 unified diff |
| [체크] | 검증 | 지정한 파일의 diff에 대해 검증만 (수정 X) | `.claude/CODE_VERIFY.md` + diff | PASS/WARN/FAIL 리포트 |
| [수정] | 리팩토링 | 대량 치환/리팩토링 — scope 고정 후 파일당 write 1회 | `.claude/CODE_REFACTOR.md` + refactor context | 코드 변경 unified diff |
| [질문] | 질의 | 단순 질문/의견 (원칙적으로 문서 안 읽음) | (없음) | 답변 |

## 공통 금지/강제

- **Repo 스캔 금지**: 요청 없으면 전체 탐색/전체 파일 읽기 금지
- **Plan/Context 기반**: 사용자가 준 plan/context 밖 파일은 읽지 않음
- **출력은 diff 우선**: 코드 변경이 있으면 unified diff로
- **한 번에 끝내기**: 파일당 read 1회 (또는 필요 라인 범위만), 파일당 write 1회 원칙

## 입력이 부족한 경우

[개발]/[수정]/[체크] 인데 plan 또는 대상 파일/컨텍스트가 없으면, **필요한 최소 정보 1개**만 요청하고 멈춘다.

## v4 설계 핵심 (코드 작성 시 항상 인지)

- **모델 D**: 재고 10 + 대기열 30 + 자동 진행 (polling이 결제 트리거)
- **Redis = implicit FIFO queue**: Lua `conditional_decr_or_wait`이 3-way 원자 분기
- **결제 실패자 자리 자동 양도**: `restore_stock_and_promote.lua`
- **PG = Auth/Capture/Void 3-call**: 매진/실패 시 가벼운 VOID
- **5종 방어 세트**: Timeout 1s, CB 50% 10s, Bulkhead 100, Tomcat 400, HikariCP 50
- **트랜잭션 경계**: DB tx 안에서 외부 호출 절대 X
- **record 적극 사용**, Javadoc 금지, 주석은 `//` 한 줄
