# API_DOC Agent (FE 참고용 MD 문서 생성 전용)

목적: 백엔드 구현이 끝난 API를 **FE가 바로 참고 가능한 md 문서**로 만든다.  
형식은 `CALENDAR_APIS.md` 스타일을 따른다. fileciteturn4file2L1-L18

입력: plan.md + (가능하면) Controller 매핑/DTO/SuccessCode/ErrorCode 정보 + (선택) diff  
출력: **md 문서 1개** (코드 수정 X)

---

## 출력 규칙(강제)

- 문서 상단 제목: `# {Domain} API` 또는 `# {Feature} API`
- API는 번호로 구분: `## 1. ...`, `## 2. ...`
- 각 API는 아래 섹션 순서를 고정한다:
  1) 한 줄 설명
  2) Endpoint
  3) Request
  4) (선택) 캐싱/특이사항(ETag 등)
  5) Response
  6) (선택) 응답 필드 설명(복잡할 때)
  7) Error Response(400/401/403/404/409 등)

> 예시 스타일: Endpoint 코드블록 + 표 + Request 예시 + Response JSON + 304/에러 케이스 fileciteturn4file2L7-L39

---

## 템플릿 (복붙용)

# {Feature} API

## 1. {API 제목}

{한 줄 설명. 사용자가 무엇을 조회/생성/수정/삭제하는지}

### Endpoint

```
{METHOD} {PATH}
```

| 항목 | 값 |
|------|-----|
| Method | {GET/POST/PATCH/DELETE} |
| Auth | {Bearer Token (필수/선택)} |
| Path Variable | `{var}` — {형식/예시} |
| Query Param | `{q}` — {형식/예시} |

### Request

- Body가 없으면: “별도 Body 없음. Path/Query만 사용”
- Body가 있으면: JSON 예시 제공

```
{METHOD} {PATH_EXAMPLE}
Authorization: Bearer {accessToken}
```

```json
{
  "{field}": "..."
}
```

### {선택} 캐싱/특이사항

- ETag 캐싱을 지원하면 아래 섹션을 넣는다. fileciteturn4file2L28-L39  
- 지원하지 않으면 이 섹션은 생략한다.

#### 동작 방식

```
1. 첫 요청 → 200 OK + ETag 헤더 반환
2. 다음 요청 시 If-None-Match 헤더에 ETag 값 포함
3. 데이터 변경 없음 → 304 Not Modified (body 없음)
4. 데이터 변경 있음 → 200 OK + 새 ETag + 새 데이터
```

#### 요청 예시

```
{METHOD} {PATH_EXAMPLE}
Authorization: Bearer {accessToken}
If-None-Match: {etagValue}
```

#### 304 Not Modified (데이터 변경 없음)

```
HTTP/1.1 304 Not Modified
ETag: {etagValue}
(body 없음)
```

### Response

#### 200 OK — 성공

```json
{
  "status": 200,
  "code": "{SUCCESS_CODE}",
  "message": "{성공 메시지}",
  "data": { ... }
}
```

> 참고 문구(필요 시): “데이터가 없는 경우 빈 배열 반환” 같은 클라이언트 처리 규칙. fileciteturn4file3L26-L36

#### {선택} 응답 필드 설명 (복잡할 때)

| 필드 | 타입 | 설명 |
|------|------|------|
| `{field}` | {String/Long/Boolean/Object/Array} | {설명} |

### Error Response

#### 400 Bad Request — {원인}

```json
{
  "status": 400,
  "code": "{ERROR_CODE}",
  "message": "{메시지}"
}
```

#### 401 Unauthorized — 인증 실패

```json
{
  "status": 401,
  "code": "UNAUTHORIZED",
  "message": "인증되지 않은 사용자입니다."
}
```

#### 404 Not Found — {리소스 없음}

```json
{
  "status": 404,
  "code": "{NOT_FOUND_CODE}",
  "message": "{메시지}"
}
```

---

## Agent 체크리스트(문서 품질)

- Path/Query/Body가 실제 Controller와 일치하는가?
- Auth 요구사항이 실제 Security 설정과 일치하는가?
- SuccessCode/ErrorCode 문자열이 백엔드 코드와 동일한가?
- “빈 배열/없으면 null/304 처리” 같은 FE 처리 규칙이 명확한가? fileciteturn4file0L72-L82
