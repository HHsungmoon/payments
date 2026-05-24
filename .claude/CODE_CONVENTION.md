## 0) 금지사항 (사용금지)
- Entity/DTO에 Setter,record 타입 금지 (상태 변경은 도메인 메서드로)
- Controller에서 ResponseDTO 없이 임의 응답 포맷 반환 금지
- ErrorCode 없이 문자열/RuntimeException 던지기 금지
- 연관관계 EAGER 금지 (기본 LAZY)
- Service에서 트랜잭션/검증 패턴 없이 즉흥 구현 금지

## 0) 권장사항 (추천)
- “기존 코드 재사용” 우선: Repo/Service/Util/Global 컴포넌트부터 찾고 확장
- Controller는 “스켈레톤(라우팅/권한/응답)” 먼저, 비즈니스는 Service로
- 조회/검증은 `validateAndGetX()` 패턴으로 통일
- readOnly 트랜잭션 기본 + 쓰기 메서드만 @Transactional
- DTO에서는 Builder 패턴을 사용.

## 1) 개발 순서 (코드 구현 단계)
> 개발 문서/재사용 플랜은 STRUCT.md 1번을 먼저 작성하고 시작

1) ErrorCode + SuccessCode 선언
- ErrorCode: `domain/{domain}/exception/{Domain}ErrorCode.java`
- SuccessCode: `global/code/SuccessCode.java` 도메인 섹션 추가

2) DTO 작성 (Request/Response)
- `domain/{domain}/dto/*{Request|Response}DTO.java`
- Request: 검증 어노테이션 + Controller `@Valid`
- Response: `toDTO()` 정적 메서드(권장)

3) API(Controller) 작성 (스켈레톤 먼저)
- `domain/{domain}/controller/{Domain}Controller.java`
- 라우팅/권한/응답(ResponseDTO + SuccessCode status)까지 우선 완성
- Service 호출은 시그니처만 맞춰두고 구현은 다음 단계

4) Service 비즈니스 로직 구현 + 필요한 Repository만 작성/확장
- Service: `domain/{domain}/application/{Domain}Service.java`
- Repository: 기존 Repo 재사용/확장 우선, 없을 때만 신규 생성

5) (선택) ApiDocs/Swagger 정리 + PR 체크리스트 확인
- `domain/{domain}/controller/{Domain}ApiDocs.java` (선택)

## 2) ErrorCode 규칙
- enum 이름: `{Domain}ErrorCode implements ApiCode`
- 필드: `HttpStatus httpStatus`, `String message`
- 대표: NOT_FOUND/ BAD_REQUEST/ CONFLICT/ FORBIDDEN/ UNAUTHORIZED
- 예외: `throw new CustomException({Domain}ErrorCode.XXX)`

## 3) SuccessCode 규칙
- `SuccessCode.java`에 도메인 주석 섹션으로 추가
- 대표: CREATE=201, GET/UPDATE=200, DELETE=204
-
## 4) 응답 규칙 (Controller 고정)
- 항상 `ResponseDTO<T>` 래핑
- 패턴:
    - `ResponseEntity.status(SuccessCode.xxx.getStatus().value()).body(new ResponseDTO<>(SuccessCode.xxx, data))`
- 데이터 없음: `ResponseDTO<Void>`(또는 String)

## 5) Entity 규칙 (코드 작성 규칙만)
- 어노테이션: `@Entity @Table @Getter @Builder @NoArgsConstructor(PROTECTED) @AllArgsConstructor`
- 필수:
    - `extends BaseTimeEntity`
    - 연관관계: `@ManyToOne(fetch = LAZY)`
    - Enum: `@Enumerated(STRING)`
    - 기본값: `@Builder.Default`
- 금지:
    - Setter 금지 → 상태 변경은 비즈니스 메서드로

## 6) Service 규칙
- 클래스:
    - `@Service @Transactional(readOnly = true) @RequiredArgsConstructor @Slf4j`
- 쓰기 메서드만 `@Transactional`
- 검증 패턴(권장):
    - `validateAndGet{Entity}(id)` → 없으면 `{Domain}ErrorCode.*_NOT_FOUND`

## 7) Controller 규칙
- 클래스:
    - `@RestController @RequiredArgsConstructor @RequestMapping("/{domains}") @Tag`
- REST 경로:
    - POST `/xxx` | GET `/xxx` | GET `/xxx/{id}` | PUT `/xxx/{id}` | DELETE `/xxx/{id}`
- 인증:
    - `Long memberId = SecurityUtils.getCurrentMemberId();`
