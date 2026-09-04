# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 프로젝트

강의 수강신청 + JWT 인증 백엔드. Spring Boot 4.1.1 · Java 25 · H2(인메모리) · 헥사고날 아키텍처.

**세 사이클이 끝나 기능 범위가 완결됐다** — 빌드 환경·인증 도메인·문서 파이프라인(project-setup),
강의 관리(klass-management), 수강신청·취소·대기열(enrollment-management). API 19개,
테스트 431건. 남은 것은 아래 [범위 경계](#범위-경계) 참조.

## 명령어

```bash
./gradlew build                    # 컴파일 + test + documentationTest (문서 산출물 검증까지)
./gradlew test                     # 단위·통합 테스트만 (DocumentationIntegrationTest 제외)
./gradlew documentationTest        # 문서 산출물 검증만
./gradlew bootRun                  # 기동 (http://localhost:8080)

# 단일 테스트
./gradlew test --tests "com.toby.klass.auth.domain.RefreshTokenTest"
./gradlew test --tests "*.RefreshTokenTest.rotate*"
```

기동 후 확인 지점: `/docs/api-guide.html`(Redoc) · `/docs/api-test.html`(Swagger UI) ·
`/docs/openapi3.json` · `/h2-console` · `/swagger-ui.html`(springdoc 보조).

기본 계정: `chals`/`test`(ROLE_USER), `creator`/`test`(ROLE_USER + ROLE_CREATOR).

## 문서 파이프라인 — 테스트를 쓰지 않으면 빌드가 실패한다

**이 결합은 의도된 것이다.** `bootJar`·`bootRun`·`jar` 가 `generatedDocument` 에 의존하고,
`generatedDocument` 는 `test` 가 남긴 RestDocs 스니펫에서 `openapi3.json` 을 만든다.

```
test → 스니펫 → openapi3 → generatedDocument → (bootJar / bootRun)
                                ↓
                          documentationTest → build
```

따라서:
- **엔드포인트를 추가하면 RestDocs 테스트를 먼저 써야 한다.** 안 쓰면 문서에서 조용히 누락되고,
  `DocumentationIntegrationTest` 의 엔드포인트 개수 검증이 깨진다. 그때 고칠 것은 개수가 아니라 테스트다.
- `generatedDocument` 의 Copy 태스크에 **문자열 치환 filter 를 걸지 말 것** — description 의
  여러 줄 마크다운이 raw 제어문자가 되어 JSON 파싱이 깨진다.
- `documentationTest` 는 `test` 에서 제외돼 별도 태스크로 돈다. 같이 두면 순환이 된다.

`build.gradle` 의 주석이 이 함정들의 근거를 담고 있다. 태스크를 건드리기 전에 읽을 것.

## 아키텍처

헥사고날(Port ↔ Adapter). 패키지는 **도메인별 수직 분할** — `auth/`, `user/`, `klass/`,
`enrollment/`, `waitlist/`, `common/`, `infrastructure/`.

```
adapter.in ──▶ application.port.in
                     │
               application.service ──▶ domain
                     │                   ▲
                     ▼                   │
              application.port.out ◀── adapter.out
                                          │
                                    infrastructure
```

**의존 규칙** (위반 시 설계 위반):

| 위치 | 허용 | 금지 |
|------|------|------|
| `domain` | JPA/Jakarta·Lombok 어노테이션, JDK | **Spring 타입 전부**, 애플리케이션 포트 |
| `application.service` | `domain`, `port.*` | `adapter.*`, 웹/JPA 타입 |
| `adapter.out` | `domain`, `port.out` | `application.service`, `adapter.in` |
| `adapter.in` | `port.in`, 자신의 DTO | `domain` 엔티티 직접 노출, `adapter.out` |

`domain` 이 Spring 을 모르는 대가로 Spring Data 의 `@CreatedDate` 를 못 쓴다 — **생성 시각은
팩토리가 파라미터로 받는다.**

### 건드리면 안 되는 지점

- **`DomainAuthenticationProvider`** — 비밀번호 검증을 계정 상태 검사보다 **먼저** 하도록 순서를
  보장한다. 표준 `DaoAuthenticationProvider` 로 대체하면 계정 열거 방지가 조용히 깨진다.
  컴파일도 테스트도 통과하므로 드러나지 않는다.
- **`SecurityConfig.csrf(...disable)`** — Security 7 은 API 엔드포인트에도 CSRF 를 기본 적용한다.
  끄지 않으면 모든 POST 가 403 이 된다.
- **`@ConfigurationPropertiesScan`**(`KlassApplication`) — 없으면 `JwtProperties`·
  `DefaultUserProperties` 가 빈으로 등록되지 않아 **기동이 통째로 실패한다.**
  `@SpringBootApplication` 은 이 스캔을 포함하지 않는다.

## 코딩 규약

이 저장소의 규약은 `docs/02-design/features/class-enrollment-erd.design.md`(ERD 정본)와
`docs/archive/2026-09/project-setup/project-setup.design.md` §10 이 정본이다. 자주 걸리는 것들:

| 항목 | 규칙 |
|------|------|
| **boolean** | **전 계층 `is` 접두어.** DB `is_enabled` ↔ 필드 `isEnabled` ↔ API `data.isEnabled` |
| **시각** | 주입된 `Clock` 만 사용. **무인자 `LocalDateTime.now()` / `LocalDate.now()` 금지** |
| 컬럼 명명 | 시각 `_at`, **날짜 `_on`**, 기간 `_days` |
| 사용자 참조 | 사용자가 *만든 것* `creator_id` / 사용자 *자신의 기록* `user_id` |
| ENUM | `@Enumerated(EnumType.STRING)`. **ordinal 금지** |
| 엔티티 접근자 | Lombok `@Getter`. 단 로직이 있는 것은 손으로 유지 (예: `User.roles()` 는 불변 뷰를 돌려주므로 `@Getter(AccessLevel.NONE)`) |
| 상태 변경 | public setter 없음. 의도가 드러나는 메서드로만 (`rotate()` 등) |
| 주석 | 한국어. 주요 결정에 `Design Ref: §n` 부착. **왜 그렇게 했는지**를 적는다 |

## 테스트

**코드와 테스트는 한 세트다.** 이 저장소에서 테스트는 검증 수단이자 **문서 생성원**이라
(위 파이프라인 참조) 테스트를 빠뜨리면 산출물이 함께 빠진다.

| 레벨 | 대상 | 위치 |
|------|------|------|
| L1 도메인 단위 | 엔티티 규칙 (`rotate()`, `verifyEnabled()`) | `*/domain/` |
| L2 어댑터·서비스 | 포트 구현, 서비스 조립 | `*/adapter/out/`, `*/application/service/` |
| L3 컨트롤러 + 문서 | 엔드포인트 계약 + **RestDocs 스니펫** | `controller/` |
| L4 통합 | 인증 전 흐름 (`@SpringBootTest`) | `integration/AuthFlowIntegrationTest` |
| L5 문서 산출물 | 생성된 스펙이 실제로 서빙되는지 | `integration/DocumentationIntegrationTest` |

무엇을 건드렸을 때 무엇을 함께 써야 하는지:

- **엔드포인트 추가** → L3 RestDocs 테스트. 안 쓰면 문서에서 누락되고 L5 검증이 깨진다
- **스키마 변경(제약·인덱스·FK)** → `EnrollmentSchemaTest` 갱신. 선언만으로는 생성됐는지 알 수 없다
- **엔티티 규칙 추가** → L1. 규칙은 도메인 안에 있어야 하므로 테스트도 도메인 단위로 붙는다

### 테스트를 쓸 때 걸리는 것들

- **`@DataJpaTest` 등의 import 경로가 Boot 4 에서 바뀌었다.**
  `org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest` (Boot 3 의
  `boot.test.autoconfigure.orm.jpa` 아니다). `@AutoConfigureMockMvc`·`@AutoConfigureRestDocs`·
  `TestRestTemplate` 도 각각 `boot.webmvc.test.autoconfigure`·`boot.restdocs.test.autoconfigure`·
  `boot.resttestclient` 로 이동했다.
- **`@GeneratedValue(IDENTITY)` 는 `persist()` 시점에 곧바로 INSERT 를 날린다.** 제약 위반 예외가
  `flush()` 가 아니라 `persist()` 에서 터지므로, `assertThatThrownBy` 로 **둘을 함께 감싸야** 한다.
- **상태코드만 검사하면 위양성이 난다.** Security 기본 체인이 켜져 있으면 로그인 페이지로
  리다이렉트되는데 `TestRestTemplate` 이 그것을 따라가 200 + `text/html` 을 돌려준다.
  응답 본문에 기대하는 마커가 있는지까지 확인할 것.
- 컨트롤러 테스트는 `BaseControllerTest` 의 공통 설정(인증된 principal 주입 등)을 상속한다.

## 컴파일러가 잡지 못하는 지점

필드명·컬럼명을 바꿀 때 **컴파일은 통과하고 런타임에 실패**하는 자리가 네 종류 있다.
이 저장소에서 실제로 다 걸렸다.

| 형태 | 예 | 실패 시점 |
|------|-----|-----------|
| JPQL 문자열 | `@Query("... set r.isRevoked = true ...")` | **Hibernate 부트스트랩 — 앱이 기동조차 안 된다** |
| Spring Data 파생 쿼리 | `existsByJti(...)` — 메서드명이 엔티티 속성명과 일치해야 한다 | 부트스트랩 |
| 리플렉션 문자열 | `getDeclaredField("isEnabled")`, `setField(user, "isEnabled", ...)` | 테스트 실행 |
| RestDocs 경로 | `fieldWithPath("data.isEnabled")` | 문서 생성 |

**이름을 바꾸기 전에 단어 경계 grep 으로 먼저 훑을 것.** 메서드 호출 패턴(`.foo()`)만 찾으면
JPQL 안의 `r.foo` 를 놓친다.

```bash
grep -rnE '\b<옛이름>\b' src/ | grep -v <새이름>
```

> 위 네 종류는 **이름을 바꿀 때마다** 밟는다. 반면 **기능을 처음 만들 때만** 밟는 함정은
> 사이클별 완료 보고서 §7.2 에 모은다. **두 사이클 합쳐 12종**이다.
>
> - `docs/archive/2026-09/klass-management/klass-management.report.md` §7.2 — Security 설정 ·
>   RestDocs · 파라미터 검증 6종
> - `docs/archive/2026-09/enrollment-management/enrollment-management.report.md` §7.2 —
>   6종. 그중 둘은 **조용히 깨진다**: 중첩 `record` 프로퍼티가 블록 누락 시 `null` 이라
>   **기동은 성공하고 첫 사용에서 NPE**, `FOR UPDATE` + 1건 제한이 상위 락 없이는
>   **낡은 행을 돌려준다**

## 스키마 검증

**"제약을 선언했다"와 "제약이 생성됐다"는 다르다.** `@CheckConstraint` 를 붙여도 DDL 에
반영되지 않으면 조용히 무방비가 된다. `EnrollmentSchemaTest` 가 `information_schema` 로
6종을 확인한다 — 테이블 / **FK** / CHECK / UNIQUE / 인덱스 / ENUM 저장 형식.

스키마를 건드렸다면 이 테스트를 함께 갱신할 것. 과거에 FK 검증이 빠져 있어 FK 5개가 없는 채로
빌드가 통과한 적이 있다.

**CHECK 제약은 표준 JPA 로 선언한다** — `@Table(check = @CheckConstraint(...))`.
`jakarta.persistence.CheckConstraint` 는 **JPA 3.2 에서 추가됐고**(이 프로젝트는 3.2.0),
Hibernate `@Check` 는 **Hibernate 7 부터 deprecated** 다 (`@Deprecated(since = "7")`,
권장 대체가 바로 이 표준 API). 속성명이 다르니 주의 — Hibernate 는 `constraints`,
표준은 **`constraint`** (단수).

H2 관련 확인된 제약:
- **`GENERATED ALWAYS AS (...) STORED` 의 `STORED` 를 거부한다.** 빼면 동작하지만,
  실 DB(MySQL/PostgreSQL) 전환 시 되붙여야 한다 — 없으면 매 조회 재계산되는 가상 컬럼이 된다.

## 범위 경계

**세 도메인 모두 전 계층이 완료됐다.** `klass`(klass-management) · `enrollment`/`waitlist`
(enrollment-management) — 도메인 행위·포트·서비스·컨트롤러·RestDocs 문서까지.

### 의도적으로 만들지 않은 것

| 항목 | 근거 | 되살릴 좌표 |
|------|------|-------------|
| **PENDING 만료 회수** | 사용자 결정 — 외부 배치 서버 전제 (enrollment D-32) | **회수 로직은 `EnrollmentService.cancel` 경로에 이미 있다.** `expires_at` 도 정확히 채워지므로 **트리거만 붙이면 동작한다** |
| 정원 증가 시 대기열 승격 | `changeCapacity` 가 `DRAFT` 에서만 호출되고 `DRAFT` 는 신청·대기가 불가능해 **도달 불가** (enrollment D-33) | `Klass.changeCapacity` javadoc 에 "되살려야 하는 조건"이 있다 — `OPEN` 에서도 정원 수정을 허용하는 순간이다 |
| `cancel_reason` 구분 | 만료 회수가 없어 취소 원인이 하나뿐이다 (ERD §2 ⑦) | 만료 회수와 함께 |
| `CLOSED → OPEN` 재모집 | ERD §4.8 이 초기 구현 차단으로 확정. 잔여 `WAITING` 처리 규약이 먼저 필요하다 | — |
| 외부 결제 연동 | 요건상 상태 변경 API 로 대체 (ERD §1.3) | — |
| 승격 알림 발송 | 채널 미정 (ERD §1.3) | — |

> ⚠️ **가장 큰 잔여 위험은 만료 회수 부재다.** 정원 10 강의에서 10명이 신청하고 아무도
> 결제하지 않으면 **영구히 만석**이 된다. 관측 쿼리가
> `EnrollmentFlowIntegrationTest` 정합성 절에 있으니 그것으로 규모를 먼저 확인할 것.

### 동시성 규약 — 건드리기 전에 읽을 것

정원과 관련된 **모든** 트랜잭션이 `klass` 행을 `SELECT ... FOR UPDATE` 로 **가장 먼저** 잡는다
(ERD 정본 §4.1). 예외는 둘뿐이며 둘 다 카운터를 건드리지 않는다 — 결제 확정(`enrollment` 단독),
대기 포기(`waitlist` 단독).

- **승격은 `EnrollmentService` 의 `private` 메서드다.** 별 빈으로 빼면 `@Transactional` 전파
  하나로 락 밖에서 실행돼 그 틈에 신규 신청자가 좌석을 채간다
- **`findNextWaitingWithLock` 의 `FOR UPDATE` + 1건 제한은 낡은 행을 돌려줄 수 있다.**
  안전한 것은 상위 `klass` 락이 승격을 직렬화하기 때문뿐이다 — 그 락을 걷어내면 즉시 열린다
- `KlassService.changeStatus` 가 `CancelRemainingWaitlistUseCase` 로 위임할 때 **전파를
  명시하지 말 것.** `REQUIRES_NEW` 로 걸면 같은 `klass` 행을 두고 자기 자신과 락 경합한다

## 커밋 규약

```
<type>: <한국어 제목>

<본문 — 왜 그렇게 했는지. 무엇을 했는지는 diff 가 말한다>
```

| type | 대상 | 이 저장소의 예 |
|------|------|----------------|
| `feat` | 기능 추가 | `feat: JWT 인증 도메인 이식` |
| `fix` | 버그·결함 수정 | `fix: 수강 도메인 FK 5종이 DDL 에 생성되지 않던 문제` |
| `refactor` | 동작 변경 없는 구조·이름 개선 | `refactor: tokenId → jti 전 계층 통일` |
| `test` | 테스트 추가·수정 | `test: FK 존재 검증 2건 추가` |
| `docs` | 문서 (PDCA 문서 포함) | `docs: 설계서에 D-13 등재` |
| `build` | 빌드 스크립트·의존성 | `build: QueryDSL 5.1.0 추가` |
| `chore` | 그 외 설정 (`.gitignore`, IDE 등) | `chore: .gitignore 에 openapi3.json 제외` |

- **제목은 한국어**, type 은 소문자 영어. 제목에 마침표를 찍지 않는다.
- **본문에 "왜"를 적는다.** 이 저장소의 주석 규약과 같은 이유다 — 무엇을 바꿨는지는 diff 로 알 수 있지만
  왜 바꿨는지는 커밋 메시지에만 남는다.
- **설계 결정을 동반하는 변경은 divergence ID 를 본문에 남긴다** (예: `Design §12 D-13`).
  나중에 "왜 원본과 다르지?" 를 추적할 때 커밋에서 문서로 바로 이어진다.
- 이름 변경처럼 **여러 계층을 한 번에 건드리는 커밋**은 범위를 본문에 적는다
  (DB 컬럼 / 엔티티 / DTO / API 응답 중 어디까지인지).

## 문서 체계

```
docs/01-plan/features/     계획 — class-enrollment-erd.plan.md (컨벤션 §8.2)
docs/02-design/features/   설계 — class-enrollment-erd.design.md 가 데이터 모델 정본
docs/03-analysis/          갭 분석 (진행 중인 사이클)
docs/04-report/            완료 보고 (진행 중인 사이클)
docs/archive/YYYY-MM/      완료된 사이클. _INDEX.md 가 목록과 참조 가치를 안내한다
```

**`class-enrollment-erd.design.md` 가 스키마의 정본이다.** 테이블·제약·인덱스·동시성 규약을
바꾸려면 이 문서를 먼저 본다. 아카이브 문서는 그 시점의 기록이며 갱신하지 않는다.

**divergence 는 사이클별로 아카이브의 설계서 §12 에 있다.** 정본·원본과 다른 코드를 발견했다면
먼저 여기를 확인할 것. 대부분 의도된 변경이고 이유가 적혀 있다.

| 사이클 | 위치 | 내용 |
|--------|------|------|
| project-setup | `docs/archive/2026-09/project-setup/project-setup.design.md` §12 | **13건** — 인증 원본(`Chals85/sample-jwt-authentication`) 대비 |
| klass-management | `docs/archive/2026-09/klass-management/klass-management.design.md` §12 | **15건** (D-14~D-28) — ERD 정본·이 문서 대비. **D-21**(락 제거)·**D-16**(대기열 미구현)은 enrollment-management 에서 전건 해소됐다 |
| enrollment-management | `docs/archive/2026-09/enrollment-management/enrollment-management.design.md` §12 | **18건** (D-29~D-46). **D-32·D-33** 은 *왜 안 만들었고 언제 되살려야 하는지*가 적혀 있다. **D-42~D-45 는 문서가 코드보다 틀렸던 4건** — Check 에서 정정했다 |

> `docs/archive/2026-09/_INDEX.md` 가 사이클별 "참조 가치가 있는 것"을 안내한다.

## 알려진 이슈

- **Lombok × Java 25** — `sun.misc.Unsafe::objectFieldOffset` deprecation 경고가 빌드마다 나온다.
  Lombok 1.18.46(최신)에서도 발생하고 `--add-opens` 로도 해소되지 않는다. **우리 측 조치 불가**이며
  Lombok 대응을 기다린다. `Unsafe` 가 실제 제거되면 경고가 아니라 컴파일 실패가 된다.
- `jwt.secret` 이 `application.yml` 에 평문이다. 1차 한정이며 실서비스 전 환경변수로 분리해야 한다.
- `DefaultUserInitializer` 가 포트를 우회해 `UserJpaRepository` 를 직접 참조한다
  (`UserCommandPort` 미신설).
