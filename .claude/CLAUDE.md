## 사용 규칙 (Prefix Router)

사용자는 메시지 맨 앞에 아래 Prefix 중 하나를 붙인다.  
Prefix에 따라 **읽을 문서 범위**를 제한한다. (그 외 파일/폴더는 기본적으로 읽지 않는다)

| Prefix | 단계 | 하는 일                                                                          | 기본적으로 읽는 문서                                          | 산출물 |
|---|---|-------------------------------------------------------------------------------|------------------------------------------------------|---|
| [구조] | 계획 | STRUCT.md 기준으로 **현재 구현/구조를 점검**하고, 이번 작업에서 어떤 파일을 **[REUSE]/[MOD]/[NEW]** 할지 결정 | `/.claude/agent/STRUCT.md`                           | `plans/{domain}/{api}.md` (plan) |
| [개발] | 구현 | plan 범위 안에서 **컨벤션대로 코드 작성** (repo 스캔 금지)                                      | `/.claude/agent/CODE_CONVENTION.md` + 해당 `(plan).md` | 코드 변경 unified diff |
| [체크] | 검증 | 지정한 파일에 대해서 diff부분 **검증만** 수행 (수정 X)                           | `/.claude/agent/CODE_VERIFY.md` + 지정파일의 diff 함수  | PASS/WARN/FAIL 리포트 |
| [수정] | 리팩토링 | 대량 치환/리팩토링을 **scope 고정 후** 파일당 write 1회로 처리                                   | `/.claude/agent/CODE_REFACTOR.md` + refactor context | 코드 변경 unified diff |
| [API문서] | 문서 | FE 참고용 API 문서(md) 생성 (코드 수정 X)                                                | `/.claude/agent/API_DOC.md` + plan/diff/엔드포인트 정보     | `docs/api/*.md` |
| [질문] | 질의 | 단순 질문/의견 (원칙적으로 문서 읽지 않음)                                                     | (없음)                                                 | 답변 |

## 공통 금지/강제

- **Repo 스캔 금지**: 요청 없으면 전체 탐색/전체 파일 읽기 금지.
- **Plan/Context 기반**: 사용자가 준 plan/context 밖 파일은 읽지 않는다.
- **출력은 diff 우선**: 코드 변경이 있으면 unified diff로만 출력.
- **한 번에 끝내기**: 파일당 read 1회(또는 필요한 라인 범위만), 파일당 write 1회 원칙.

## 입력이 부족한 경우

- [개발]/[수정]/[체크]/[API문서]인데 plan 또는 대상 파일/컨텍스트가 없으면,
  - “필요한 최소 정보 1개”만 요청하고 멈춘다.
