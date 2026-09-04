# klass

## 서비스 개요

강의 서비스이며 개설자는 강의를 등록·수정하고 모집 상태를 전이시키며,수강생은 신청·확정·취소와 대기열 등록·취소하는 API 서버

## 기술 스택

Java 25 · Spring Boot 4.1.1 · Gradle 단일 모듈 · H2(인메모리) + JPA + QueryDSL ·
Nimbus JOSE JWT · Spring REST Docs + epages restdocs-api-spec

## 아키텍처

헥사고날 (Ports & Adapters). 패키지는 **도메인별 수직 분할** — `auth` · `user` · `klass` ·
`enrollment` · `waitlist` · `common` · `infrastructure`.

```
com.toby.klass.{domain}/
├── adapter/in/web/controller/    # @RestController
├── adapter/in/web/dto/           # Request / Response
├── adapter/out/persistence/      # {Domain}JpaRepository · {Domain}QueryDslRepository · {Domain}RepositoryAdapter
├── adapter/out/{security,token}/ # 기술 어댑터 (auth 전용)
├── application/dto/              # Command / Query / Result
├── application/port/{in,out}/    # UseCase · CommandPort / QueryPort / {역할}Port
├── application/service/          # 비즈니스 로직
├── domain/                       # 엔티티 · 값 객체 · enum
└── domain/error/                 # {Domain}Error implements ErrorCode
```

**의존 규칙** (위반 시 설계 위반):

| 위치 | 허용 | 금지 |
|------|------|------|
| `domain` | JPA/Jakarta·Lombok 어노테이션, JDK | **Spring 타입 전부**, 애플리케이션 포트 |
| `application.service` | `domain`, `port.*` | `adapter.*`, 웹/JPA 타입 |
| `adapter.out` | `domain`, `port.out` | `application.service`, `adapter.in` |
| `adapter.in` | `port.in`, 자신의 DTO | `domain` 엔티티 직접 노출, `adapter.out` |

`domain` 은 `@Service`·`@Transactional` 조차 모른다. 그 대가로 Spring Data 의 `@CreatedDate` 를
못 쓰므로 **생성 시각은 팩토리가 파라미터로 받는다.** 같은 이유로 `ErrorCode.httpStatus()` 가
`HttpStatus` 가 아니라 `int` 다.

## Git 운영 규칙

- **master 직접 커밋 금지** — 작업 브랜치(`<type>/<주제>`)에 커밋한 뒤 `git merge --ff-only`
  로 합치고 브랜치를 지운다. 이력은 master 일직선으로 유지한다.
- **커밋 메시지**: `<type>: <한국어 제목>` (Conventional Commits, 마침표 없음). 본문에는 **왜**
  그렇게 했는지를 적는다 — 무엇을 했는지는 diff 가 말한다.
- **설계 결정을 동반하면 divergence ID 를 남긴다.** 제목 끝에 `(D-46)` 또는 본문에
  `Design §12 D-13`. "왜 원본과 다르지?" 를 커밋에서 문서로 바로 이을 수 있다.

## 코딩·네이밍 규약

| 항목 | 규칙 |
|------|------|
| **boolean** | **전 계층 `is` 접두어.** DB `is_enabled` ↔ 필드 `isEnabled` ↔ API `data.isEnabled` |
| **시각** | 주입된 `Clock` 만. **무인자 `LocalDateTime.now()` / `LocalDate.now()` 금지** |
| 테이블 | 단수 snake_case, PK 는 `id`(IDENTITY). **예약어만 복수** — `user` → `users` |
| 컬럼 | 시각 `_at`, **날짜 `_on`**, 기간 `_days` |
| 사용자 참조 | 사용자가 *만든 것* `creator_id` / 사용자 *자신의 기록* `user_id` |
| ENUM | `@Enumerated(EnumType.STRING)`, **ordinal 금지**. `{domain}/domain/` 에 둔다 |
| 상태 변경 | public setter 없음. 의도가 드러나는 메서드로만 (`rotate()` · `occupySeat()`) |
| API 경로 | `/v1/{resource}` — `/api` 접두사 없음 |
| 주석 | 한국어. 주요 결정에 `Design Ref: §n` 부착. **왜 그렇게 했는지**를 적는다 |

클래스 접미사 — **이름이 곧 위치이자 역할**이다:

| 접미사 | 위치 · 방향 | 예시 |
|--------|-------------|------|
| `Request` / `Response` | `adapter/in/web/dto/` | `RegisterKlassRequest` · `KlassResponse` |
| `Command` / `Query` / `Result` | `application/dto/` | `RegisterKlassCommand` · `KlassQuery` · `KlassResult` |
| `UseCase` | in — **유스케이스 단위로 쪼갠다** | `RegisterKlassUseCase` |
| `CommandPort` / `QueryPort` | out — 저장소 쓰기 / 읽기 | `KlassCommandPort` · `KlassQueryPort` |
| `{역할}Port` | out — Query/Command 로 안 떨어지는 기술 | `PasswordHasherPort` · `TokenGeneratorPort` |
| `{Domain}RepositoryAdapter` | 저장소를 주입하는 영속화 어댑터 | `KlassRepositoryAdapter` |
| `{역할}Adapter` | 저장소 주입이 없는 기술 어댑터 | `NimbusJwtAdapter` |

- DTO 는 모두 Java `record`. 변환 방향이 정해져 있다 — **들어올 때는 Request 가**
  (`request.toCommand(principal.id())`), **나갈 때는 Response 가**(`KlassResponse.from(result)`).
  서비스는 웹 DTO 를 모른다.
- 시각·암호화·토큰처럼 **결정적이지 않은 기술**은 `{역할}Port` 뒤로 민다.

지키지 않으면 **조용히 깨지는** 것 넷:

- **쿼리는 QueryDSL 지향.** 동적·조인·프로젝션은 전용 `{Domain}QueryDslRepository`
  (`JPAQueryFactory` 주입)로 분리하고 어댑터가 위임한다. 파생 쿼리(`findByXxx`)는 그대로.
  **문자열 `@Query`(JPQL)는 최후 수단** — 컴파일러가 검사하지 않아 필드명을 바꾸면
  Hibernate 부트스트랩에서 앱이 기동조차 못 한다.
- **`@PreAuthorize` 를 쓰지 않는다.** `SecurityConfig` 요청 매처로만 인가하며, **구체적인 규칙이
  먼저** 와야 한다 — `permitAll` 이 앞서면 뒤의 `hasRole` 이 죽는다. `hasRole` 에 `ROLE_`
  접두어를 붙이지 않는다(자동으로 붙는다).
- **에러 코드는 enum 상수명 그대로 `error.code` 로 나간다.** 응답에 enum 타입 정보가 없으므로
  **도메인 접두어가 필수**다 — `KLASS_NOT_FOUND` 를 `NOT_FOUND` 로 두면 `CommonError` 와
  구분되지 않아 클라이언트가 분기할 수 없다.
- **상태 코드는 404 와 409 의 쓰임이 특이하다.** 타인의 `DRAFT` 강의는 403 이 아니라 **404** —
  초안은 존재 자체가 비밀이다. 409 는 요청은 옳은데 리소스 상태와 충돌하는 경우로, 400 과 달리
  **입력을 고쳐도 상태가 바뀌기 전엔 실패한다.**

## 테스트

**코드와 테스트는 한 세트다.** 테스트는 검증 수단이자 **문서 생성원**이라(아래 파이프라인)
빠뜨리면 산출물이 함께 빠진다.

L1 도메인 단위(`*/domain/`) · L2 어댑터·서비스(`*/adapter/out/`, `*/application/service/`) ·
L3 컨트롤러 + RestDocs 스니펫(`controller/`) · L4 통합(`integration/*FlowIntegrationTest`) ·
L5 문서 산출물(`integration/DocumentationIntegrationTest`).

- **엔드포인트 추가** → L3 RestDocs 테스트. `BaseControllerTest` 상속,
  `@WebMvcTest(XxxController.class)` + `addFilters = false`, UseCase 는 `@MockitoBean`.
  인증은 `authenticateAs(...)`, 식별자는 한국어 kebab(`document("강의-등록")`),
  `.tag("Klass")` · `.summary(...)` 로 그룹·제목 지정. **에러 케이스도 문서화 필수**
- **스키마 변경(제약·인덱스·FK)** → `EnrollmentSchemaTest` 갱신
- **엔티티 규칙 추가** → L1

### 테스트를 쓸 때 걸리는 것들

- **L3 에서 401·403 을 쓰지 말 것.** `SecurityConfig` 가 배제되고 필터가 꺼져 있어 `ROLE_USER`
  로 강의를 등록해도 **201 이 나온다.** 검증하는 척만 하는 테스트가 된다 — 권한이 실제로
  막히는지는 L4 가 확인한다.
- **`@DataJpaTest` 등의 import 경로가 Boot 4 에서 바뀌었다.**
  `org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest` (Boot 3 의
  `boot.test.autoconfigure.orm.jpa` 아니다). `@AutoConfigureMockMvc`·`@AutoConfigureRestDocs`·
  `TestRestTemplate` 도 각각 `boot.webmvc.test.autoconfigure`·`boot.restdocs.test.autoconfigure`·
  `boot.resttestclient` 로 이동했다.
- **`@GeneratedValue(IDENTITY)` 는 `persist()` 시점에 곧바로 INSERT 를 날린다.** 제약 위반 예외가
  `flush()` 가 아니라 `persist()` 에서 터지므로 `assertThatThrownBy` 로 **둘을 함께 감싸야** 한다.
- **상태코드만 검사하면 위양성이 난다.** Security 기본 체인이 켜져 있으면 로그인 페이지로
  리다이렉트되는데 `TestRestTemplate` 이 그것을 따라가 200 + `text/html` 을 돌려준다.

## API 문서 생성 (REST Docs + OpenAPI)

**이 결합은 의도된 것이다.** `bootJar`·`bootRun`·`jar` 가 `generatedDocument` 에 의존하므로
**테스트를 쓰지 않으면 빌드가 실패한다.**

```
test → 스니펫 → openapi3 → generatedDocument → (bootJar / bootRun)
                                ↓
                          documentationTest → build
```

- **엔드포인트를 추가하면 RestDocs 테스트를 먼저 써야 한다.** 안 쓰면 문서에서 조용히 누락되고,
  `DocumentationIntegrationTest` 의 개수 검증이 깨진다. 그때 고칠 것은 개수가 아니라 테스트다.
- `generatedDocument` 의 Copy 태스크에 **문자열 치환 filter 를 걸지 말 것** — description 의
  여러 줄 마크다운이 raw 제어문자가 되어 JSON 파싱이 깨진다.
- `documentationTest` 는 `test` 에서 제외돼 별도 태스크로 돈다. 같이 두면 순환이 된다.

문서 접근: `/docs/api-guide.html`(Redoc) · `/docs/api-test.html`(Swagger UI) ·
`/docs/openapi3.json` · `/h2-console`.

## 동시성 규약 — 건드리기 전에 읽을 것

정원과 관련된 **모든** 트랜잭션이 `klass` 행을 `SELECT ... FOR UPDATE` 로 **가장 먼저** 잡는다
(ERD §4.1). 예외는 둘뿐이며 둘 다 카운터를 건드리지 않는다 — 결제 확정(`enrollment` 단독),
대기 포기(`waitlist` 단독). **만료 회수 배치도 예외가 아니다** — `reapExpired` 는 `cancel` 과
똑같은 순서(`klass` → `enrollment` → `waitlist`)로 잡는다.

- **락 조회와 일반 조회를 분리한다.** `findById` 는 개설자를 조인하고 락을 잡지 않으며,
  `findWithLockById` 는 반대다. 조회가 락을 잡으면 목록 조회가 신청과 직렬화되고, 락 조회가
  개설자를 조인하면 `users` 행까지 잠겨 "락 대상은 `klass` 단일 행" 규약이 깨진다
- **승격은 `EnrollmentService` 의 `private` 메서드다.** 별 빈으로 빼면 `@Transactional` 전파
  하나로 락 밖에서 실행돼 그 틈에 신규 신청자가 좌석을 채간다. **스프링 이벤트도 같은
  이유로 쓰지 않는다**(D-47) — `@TransactionalEventListener` 로 한 글자만 바꿔도 조용히 깨진다.
  이벤트를 되살릴 자리는 승격이 아니라 **승격 알림**이다(불변식은 트랜잭션 안, 부수효과는 커밋 후)
- **`@Scheduled` 진입점과 `@Transactional` 처리 메서드는 다른 빈이어야 한다.** 같은 클래스에
  두면 프록시를 타지 않아 **트랜잭션이 걸리지 않는다** — 컴파일도 테스트도 통과하고 배치도
  도는데 롤백만 안 된다. `ExpiredEnrollmentScheduler`(`adapter/in/scheduler/`)와
  `EnrollmentService.reapExpired` 가 그 분리다 (D-48)
- **`findNextWaitingWithLock` 의 `FOR UPDATE` + 1건 제한은 낡은 행을 돌려줄 수 있다.**
  안전한 것은 상위 `klass` 락이 승격을 직렬화하기 때문뿐이다 — 그 락을 걷어내면 즉시 열린다
- `KlassService.changeStatus` 가 `CancelRemainingWaitlistUseCase` 로 위임할 때 **전파를
  명시하지 말 것.** `REQUIRES_NEW` 로 걸면 같은 `klass` 행을 두고 자기 자신과 락 경합한다

## 건드리면 안 되는 지점

- **`DomainAuthenticationProvider`** — 비밀번호 검증을 계정 상태 검사보다 **먼저** 한다. 표준
  `DaoAuthenticationProvider` 로 대체하면 계정 열거 방지가 조용히 깨진다. 컴파일도 테스트도
  통과하므로 드러나지 않는다.
- **`SecurityConfig.csrf(...disable)`** — Security 7 은 API 엔드포인트에도 CSRF 를 기본 적용한다.
  끄지 않으면 모든 POST 가 403 이 된다.
- **`@ConfigurationPropertiesScan`**(`KlassApplication`) — 없으면 `JwtProperties`·
  `DefaultUserProperties` 가 빈으로 등록되지 않아 **기동이 통째로 실패한다.**

## 컴파일러가 잡지 못하는 지점

필드명·컬럼명을 바꿀 때 **컴파일은 통과하고 런타임에 실패**하는 자리가 네 종류 있다.
이 저장소에서 실제로 다 걸렸다.

| 형태 | 예 | 실패 시점 |
|------|-----|-----------|
| JPQL 문자열 | `@Query("... set r.isRevoked = true ...")` | **Hibernate 부트스트랩 — 기동조차 안 된다** |
| Spring Data 파생 쿼리 | `existsByJti(...)` — 메서드명이 엔티티 속성명과 일치해야 한다 | 부트스트랩 |
| 리플렉션 문자열 | `getDeclaredField("isEnabled")` | 테스트 실행 |
| RestDocs 경로 | `fieldWithPath("data.isEnabled")` | 문서 생성 |

**이름을 바꾸기 전에 단어 경계 grep 으로 먼저 훑을 것.** 메서드 호출(`.foo()`)만 찾으면
JPQL 안의 `r.foo` 를 놓친다.

```bash
grep -rnE '\b<옛이름>\b' src/ | grep -v <새이름>
```

> 위 넷은 이름을 바꿀 때마다 밟는다. **기능을 처음 만들 때만** 밟는 함정 18종은 사이클별
> 완료 보고서 §7.2 에 있다. 그중 둘은 **조용히 깨진다** — 중첩 `record` 프로퍼티는 블록 누락 시
> `null` 이라 **기동은 성공하고 첫 사용에서 NPE**, `FOR UPDATE` + 1건 제한은 상위 락이 없으면
> **낡은 행을 돌려준다**.

## 스키마 검증

**"제약을 선언했다"와 "제약이 생성됐다"는 다르다.** `EnrollmentSchemaTest` 가
`information_schema` 로 6종을 확인한다 — 테이블 / **FK** / CHECK / UNIQUE / 인덱스 / ENUM 저장
형식. 스키마를 건드렸다면 함께 갱신할 것. 과거에 FK 검증이 빠져 FK 5개가 없는 채로 빌드가
통과한 적이 있다.

- **CHECK 제약은 표준 JPA 로 선언한다** — `@Table(check = @CheckConstraint(...))`. Hibernate
  `@Check` 는 7 부터 deprecated. **속성명 주의** — Hibernate 는 `constraints`, 표준은
  **`constraint`**(단수).
- **H2 는 `GENERATED ALWAYS AS (...) STORED` 의 `STORED` 를 거부한다.** 빼면 동작하지만 실
  DB 전환 시 되붙여야 한다 — 없으면 매 조회 재계산되는 가상 컬럼이 된다.

## 범위 경계

**버그로 보이지만 의도된 공백 둘.** 고치기 전에 읽을 것.

- **PENDING 만료 회수 — 해소됨**(D-32 → pending-expiry-reaper). `ExpiredEnrollmentScheduler` 가
  10분마다(`app.enrollment.reap-interval`) 기한이 지난 `PENDING` 을 회수하고 좌석을 반납하며,
  `OPEN` 이면 대기 1순위를 승격한다. 취소 원인은 `cancel_reason`(`USER`/`EXPIRED`)으로 남는다.
  ⚠️ **남은 위험은 다른 것이다** — 승격 알림이 없어(ERD §4.8) 대기자가 승격 사실을 모른 채
  순차 만료되면 **대기열은 소진되고 좌석은 빈 채로 남는다.** 관측 쿼리가
  `EnrollmentFlowIntegrationTest` 정합성 절 #45 에 있다.
- **정원 증가 시 대기열 승격 없음**(D-33) — `changeCapacity` 가 `DRAFT` 에서만 호출되고 `DRAFT` 는
  신청·대기가 불가능해 **도달 불가**다. 되살릴 조건은 `Klass.changeCapacity` javadoc 에 있다.

그 밖의 미구현(`cancel_reason` 구분 · `CLOSED → OPEN` 재모집 · 외부 결제 · 승격 알림)은
ERD §1.3 · §2 ⑦ · §4.8 에 근거가 있다.

## 명령어

```bash
./gradlew build                    # 컴파일 + test + documentationTest
./gradlew test                     # 단위·통합 테스트만
./gradlew documentationTest        # 문서 산출물 검증만
./gradlew bootRun                  # 기동 (http://localhost:8080)
./gradlew test --tests "*.RefreshTokenTest.rotate*"
```

기본 계정: `chals`/`test`(ROLE_USER), `creator`/`test`(ROLE_USER + ROLE_CREATOR).

## 문서 체계

```
docs/01-plan/features/     계획
docs/02-design/features/   설계 — class-enrollment-erd.design.md 가 데이터 모델 정본
docs/03-analysis/          갭 분석    ┐ 진행 중인 사이클에서만 존재하고
docs/04-report/            완료 보고  ┘ 끝나면 archive 로 옮긴다
docs/archive/YYYY-MM/      완료된 사이클. _INDEX.md 가 참조 가치를 안내한다
```

**정본·원본과 다른 코드를 발견했다면 아카이브 설계서 §12(divergence)를 먼저 볼 것.**
D-1~D-46 전건이 사이클별 §12 에 이유와 함께 있다. 대부분 의도된 변경이다.
아카이브 문서는 그 시점의 기록이며 갱신하지 않는다.

## 알려진 이슈

- **Lombok × Java 25** — `sun.misc.Unsafe::objectFieldOffset` deprecation 경고가 빌드마다 나온다.
  Lombok 1.18.46(최신)에서도 발생하고 `--add-opens` 로도 해소되지 않는다. **우리 측 조치 불가.**
  `Unsafe` 가 실제 제거되면 경고가 아니라 컴파일 실패가 된다.
- `jwt.secret` 이 `application.yml` 에 평문이다. 1차 한정이며 실서비스 전 환경변수로 분리해야 한다.
- `DefaultUserInitializer` 가 포트를 우회해 `UserJpaRepository` 를 직접 참조한다.
