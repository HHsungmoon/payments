
## 0) 금지사항 (사용금지)
- 도메인 폴더 구조 임의 변경 금지
- 전역 공통 컴포넌트를 “도메인별로 복제” 금지 (BaseTimeEntity/ResponseDTO 등)
- 새 Entity를 “습관적으로” 추가 금지 (문서에서 [NEW]로 명시된 경우만)

## 0) 권장사항 (추천)
- 개발 시작 전, 반드시 “문서 + 재사용 플랜([REUSE]/[MOD]/[NEW])” 작성
- 기존 도메인에서 유사 케이스 1개를 골라 “구조/네이밍/응답코드”를 1:1로 맞추기


## 1) 개발 문서 작성 템플릿 (Step 1)
아래 형식을 먼저 채우고, 코드는 CLAUDE.md 순서대로 진행.

### 1-1. 기능 명세(간단)
- Endpoint:
- Method:
- Auth(권한):
- Request 필드:
- Response 필드:
- SuccessCode:
- Error cases(어떤 상황에서 어떤 ErrorCode):

### 1-2. 재사용 플랜 (필수)
- [REUSE] 그대로 쓸 기존 코드 경로(최우선):
  - Entity/Repo/Service/Util/Global/Code
- [MOD] 수정할 기존 코드 경로:
- [NEW] 새로 만들 파일(최소화):

> Entity는 기본적으로 [REUSE] 또는 “이미 존재”로 처리.
> 정말 필요할 때만 [NEW]에 Entity를 적는다.


### 1-3. 작업용 plan.md 템플릿(권장)
- 아래 내용을 **API마다 plan 파일로 복사**해서 사용한다. (예: `plans/{domain}/{api}.md`)
- [구조] 단계의 최종 산출물은 이 plan 파일이다.

#### 1) 기능 명세
- Endpoint:
- Method:
- Auth(권한):
- Request:
- Response:
- SuccessCode:
- Error cases(어떤 상황에서 어떤 ErrorCode):

#### 2) 재사용 플랜 (필수)
- [REUSE]
- [MOD]
- [NEW]

#### 3) 이번 작업에서만 적용할 컨벤션 요약(5~10줄)
- (CODE_CONVENTION.md 중 “이번 작업에 필요한 것만” 요약해서 plan에 붙인다)

#### 4) 구현 순서(체크리스트)
1) ErrorCode + SuccessCode
2) DTO (Request/Response)
3) Controller skeleton (라우팅/권한/응답까지)
4) Service + Repository
5) (선택) ApiDocs/Swagger

#### 5) Claude 출력 요구(토큰 절약)
- 출력은 **unified diff로만**
- [MOD]/[NEW] 파일만 수정
- 파일당 read 1회(또는 필요한 라인 범위만), 파일당 write 1회
- repo 전체 스캔 금지 (필요 시 “추가로 읽을 파일 1개”만 요청)

---

## 2) 도메인 폴더 구조 (표준)
`src/main/java/com/ceos/beatbuddy/domain/{domain}/`
- application/
- controller/ (+ ApiDocs 선택)
- dto/
- entity/ (+ Status enum 필요시)
- exception/
- repository/
- validator/

---

## 3) 공통 컴포넌트 (무조건 재사용)
- 시간: `global/BaseTimeEntity` (createdAt/updatedAt)
- 에러 인터페이스: `global/ApiCode`
- 예외: `CustomException`
- 응답 포맷: `global/dto/ResponseDTO`
- 인증 사용자: `SecurityUtils.getCurrentMemberId()`
- 성공 코드: `global/code/SuccessCode.java`

---

## 4) “Claude 입력 규칙” (기존 코드 재사용 강제)
Claude에게 요청할 때, 아래 3블록을 항상 포함한다.

- [REUSE]
- [MOD]
- [NEW]

예시)
- [REUSE] global/dto/ResponseDTO.java
- [REUSE] domain/user/entity/User.java
- [MOD] global/code/SuccessCode.java
- [NEW] domain/group/exception/GroupErrorCode.java
- [NEW] domain/group/dto/CreateGroupRequestDTO.java
