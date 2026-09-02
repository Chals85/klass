# 강의 관리 기능 갭 분석 (Check)

> **Summary**: 설계-구현 갭 분석 결과 **Match Rate 93%**. Critical 1 · Important 7 · Minor 7 · 무용 테스트 7종을 발견해 **전건 수정 완료**. 가장 값진 발견은 "통과하지만 아무것도 검증하지 않는 테스트"가 7건 더 남아 있었다는 것이다.
>
> **Project**: klass
> **Author**: developer2@lulumedic.com
> **Date**: 2026-09-02
> **Status**: Resolved (전건 수정)
> **Design Doc**: [klass-management.design.md](../02-design/features/klass-management.design.md) v0.5
> **Plan Doc**: [klass-management.plan.md](../01-plan/features/klass-management.plan.md) v0.3

---

## Context Anchor

| Key | Value |
|-----|-------|
| **WHY** | 강의를 만들·고칠·볼 경로가 없어 수강신청 도메인이 착지할 대상이 존재하지 않는다 |
| **WHO** | 크리에이터(ROLE_CREATOR) / 일반 사용자·비로그인 방문자 |
| **RISK** | `ROLE_CREATOR` 권한 검사만 통과하면 **남의 강의를 수정할 수 있는** 수평 권한 상승 |
| **SUCCESS** | 타 크리에이터 수정·전이 403 / 금지된 상태 전이 전부 거부 / DRAFT 완전 비노출 / 커서 무중복 |
| **SCOPE** | module-1 도메인·스키마 → module-2 포트·영속 → module-3 서비스 → module-4 웹·문서 |

---

## 1. Match Rate

| 축 | 일치율 | 근거 |
|----|:-----:|------|
| 구조적 (Structural) | 94% | 설계 §11.1 신규 28 중 27 존재 · 엔드포인트 6/6 · 미존재 2건은 D-22·D-23 으로 등재 |
| 기능적 (Functional) | 90% | FR 16건 중 13 완전 · 2 부분 · 1 의도적 미이행(FR-06 → D-21) |
| API 계약 (Contract) | 85% → **100%** | C-1·I-1 수정 후 요청 필드 15/15 · 쿼리 파라미터 6/6 |
| 런타임 (Runtime) | **100%** | `./gradlew clean build` 통과, 195건 전부 성공 |
| **종합** | **93%** | `0.15×94 + 0.25×90 + 0.25×85 + 0.35×100` (수정 전 기준) |

> 품질 게이트(90%)를 넘겼으나 **전건 수정을 선택**했다. 근거는 §4 참조 — 발견된 것 중 상당수가 "수강신청 사이클 시작 전에 처리해야 싼" 항목이었다.

---

## 2. 전략적 정합성

### 2.1 Plan Success Criteria

| 기준 | 상태 | 근거 |
|------|:----:|------|
| 타 크리에이터 수정·전이 403 | ✅ | `KlassService.loadForCommand` + L4 `otherCreatorCannotUpdate` (`error.code == NOT_KLASS_OWNER`) |
| 금지된 상태 전이 전부 거부 | ✅ | `Klass.publish`/`close` 전제 + `KlassService` DRAFT 분기 + L1 파라미터화 3종 |
| DRAFT 완전 비노출 | ✅ | `PUBLIC_STATUSES` + `isVisibleTo` + 어댑터 가드 + L4 `anonymousCannotSeeDraft` |
| 커서 무중복·무누락 | ✅ | `cursorLt` 가 `<` (`<=` 아님) + L2 `hasSize(7).doesNotHaveDuplicates()` |

**4/4 충족.**

### 2.2 Plan 검증 시나리오 S1~S8

8건 전부 테스트로 대응되며, **6건은 두 레벨에서 겹쳐 검증**된다 — 도메인 규칙(L1/L2)과 실제 HTTP 경로(L4). S1·S4 는 그 이중화가 특히 중요하다: L2 가 검사 **순서**를, L4 가 필터 체인이 실제로 이어지는지를 본다.

### 2.3 FR 이행

| ID | 상태 | 비고 |
|----|:----:|------|
| FR-01 ~ FR-05 | ✅ | 등록·부분수정·상태전이·정원방어·소유권 |
| **FR-06** | ⏸ **Deferred** | 비관적 락. **D-21** — 막을 상대(수강신청)가 2차 범위라 지금은 아무것도 직렬화하지 않는다 |
| FR-07 ~ FR-14 | ✅ | `updated_at` · 조회 3종 · 커서 · fetch join · 응답 봉투 |
| FR-15 | ✅ | RestDocs 6종. **C-1 수정 후** 필드 단위까지 완전 |
| FR-16 | ✅ | 400 + `details`. **I-2 수정 후** 파라미터 경로까지 |

---

## 3. 발견된 갭과 조치

### 3.1 Critical (1건) — 전부 수정

**C-1. `PATCH /v1/klasses/{id}` 문서에 요청 필드 5개 누락**

`requestFields` 에 `title`·`capacity` **2개만** 선언돼 있었다. DTO 는 7필드인데 `description`·`price`·`startsOn`·`endsOn`·`cancellationPeriodDays` 가 스펙에 없었다.

원인은 테스트가 보낸 payload 가 2필드뿐이었던 것 — **RestDocs 는 실제로 보낸 것만 문서화한다.** CLAUDE.md 가 경고한 "안 쓰면 조용히 누락된다"의 **필드 단위 버전**이고, L5 는 오퍼레이션 수만 세므로 **어떤 테스트도 실패하지 않았다.**

→ payload 7필드 확장 + `requestFields` 7개. `openapi3.json` 의 `UpdateKlassRequest` 스키마에 7필드 반영 확인.

### 3.2 Important (7건) — 전부 수정

| ID | 내용 | 조치 |
|----|------|------|
| **I-1** | 공개 목록에 `cursor` 파라미터 없음. 그런데 **설명 텍스트는 "nextCursor 를 cursor 로 넘긴다"** — 문서 자기모순 | `.param("cursor", "43")` + `queryParameters` 추가. `openapi3.json` 에 3종 반영 확인 |
| **I-2** | `HandlerMethodValidationException` 핸들러가 **도달 불가능한 죽은 코드** (파라미터 제약 0건) | `@Min`/`@Max` 추가 → 핸들러가 실제로 살아남. §3.3 참조 |
| **I-3** | 설계 §6.5 표가 `/klasses/abc` → 400 을 기대하나 실제는 **401** | **코드가 옳다.** §4.2 가 매처를 `{id:[0-9]+}` 로 좁혔으므로 컨트롤러에 닿지 않는다. 표를 정정 |
| **I-4** | `Klass` javadoc 이 "상태 전이는 2차에서" — **같은 파일에 그 메서드들이 있다** | "무엇이 여전히 없는가"(`enrollment_count` 증감)로 재작성 |
| **I-5** | CLAUDE.md 범위 경계가 `klass` 에 대해 거짓 | `klass` 전 계층 완료 / `enrollment`·`waitlist` 만 엔티티 단계로 분리 기술 + D-21 좌표 안내 |
| **I-6** | 설계 §2.4 가 "이 성질을 테스트가 고정한다"고 주장하나 **그 테스트는 항상 통과** | 조인 없는 경로로 읽도록 테스트 실효화 + §2.4 에 한계 명시 |
| **I-7** | `common/CursorPageResponse` 가 `klass` 패키지 import — 공통화 근거 자기배반 | `CursorPageResult` 를 `common/application/dto` 로 이동 (import 7곳). **D-24** 등재 |

### 3.3 I-2 의 후속 발견 — `@Validated` 를 붙이면 500 이 된다

설계 §6.5 가 약속한 두 방어선을 실제로 세우려고 `@Min`/`@Max` 를 붙였는데, **클래스에 `@Validated` 를 함께 붙이자 500 이 났다.**

Spring 6.1 부터 `@RequestParam` 의 제약 애노테이션은 **내장 메서드 검증**이 처리하고 `HandlerMethodValidationException` 을 던진다 — 우리 핸들러가 잡는 그 예외다. 그런데 `@Validated` 가 있으면 **AOP 기반 검증이 대신 동작**해 `ConstraintViolationException` 을 던지고(이중 검증을 피하려 내장 쪽이 물러난다), 그 예외는 핸들러가 없어 `handleUnexpected` 로 떨어진다.

**애노테이션을 더 붙였는데 응답이 나빠지는** 종류의 함정이다. 걷어내고 설계서에 근거를 남겼다.

부수 소득: 두 방어선의 실제 역할이 명확해졌다.

| 경로 | 잡는 곳 | 응답 코드 | 검증 |
|------|---------|-----------|------|
| HTTP `?size=101` | 컨트롤러 `@Max` → Advice | `VALIDATION_ERROR` | `KlassFlowIntegrationTest#12` |
| 포트 직접 호출 | `KlassQuery` 생성자 | `INVALID_KLASS_PAGE_SIZE` | `KlassQueryTest` |

### 3.4 무용 테스트 7종 — 이 사이클에서 세 번 잡은 유형이 또

**가장 값진 발견이다.** 이미 세 번(스키마 CHECK 테스트 / N+1 절대 쿼리 수 / L3 의 400 단언) 고친 유형이 7건 더 남아 있었다.

| ID | 왜 무의미했나 | 조치 |
|----|--------------|------|
| **T-1** | `withdrawDraft` 가 상태코드만 단언 — 리다이렉트된 HTML 도 200 으로 통과. **이 파일 javadoc 이 바로 그 형태를 금지한다** | 본문 `data.status == "CLOSED"` 단언 추가 |
| **T-2** | `allowsCapacityEqualToEnrollment` 가 `doesNotThrowAnyException()` 만 — `changeCapacity` 가 조용히 아무것도 안 해도 통과 | 값 반영 + `updatedAt` 단언 |
| **T-3** | `creatorIsInitialized` 가 빈 컬렉션에 `allSatisfy` — 쿼리가 빈 결과를 돌려주면 무조건 통과 | `hasSize(7)` 가드 추가 |
| **T-4** | `ownershipCheckDoesNotTriggerQuery` 가 `@EntityGraph` 경로로 읽어 **조건이 항상 참** | 조인 없는 `JpaRepository.findById` 로 읽고 사전조건(`isInitialized == false`)까지 단언 |
| **T-5** | `rejectsNullDescription` 이 `isInstanceOf(Exception.class)` — SQL 문법 오류로도 통과 | `ConstraintViolationException` 으로 좁힘. **`DataIntegrityViolationException` 이 아니다** — `EntityManager` 네이티브 쿼리는 Spring 예외 변환을 타지 않는다 |
| **T-6** | `rejectsMissingDescription` 에 `error.code` 단언 누락 (형제 테스트엔 있음) | 추가 |
| **T-7** | `QueryValidation` 이 어댑터를 호출하지 않는데 `@DataJpaTest` + `@Import` 3종 컨텍스트를 띄움 | `KlassQueryTest` 로 분리 (순수 단위) |

**칭찬할 것도 있었다** — §8.3 #7(fetch join 쿼리 카운트)은 절대값이 아니라 **증가분**을 재도록 이미 고쳐져 있었고, javadoc 에 그 경위까지 남아 있었다.

### 3.5 Minor (7건) — 전부 수정

M-1 `KlassPeriod` 미존재(**D-22** 등재) · M-2 `application-test.yml` 미존재(**D-23**) · M-3 `KlassCreatorResult` 목록 누락 · M-4 설계의 락 서술 4곳 stale · M-5 L3 테스트 위치 · M-6 `?status=DRAFT` 동작 문서화 · M-7 `EnrollmentSchemaTest` 의 무인자 `now()` + `KlassServiceTest` 미사용 import 2건과 **사실과 다른 `@SuppressWarnings` 주석**.

---

## 4. divergence 정합성 — 11건 전부 코드에 반영

D-14 ~ D-21 여덟 건은 **문서가 말한 대로 코드가 되어 있음**이 확인됐다. 특히 **D-21(락 제거)이 좌표 세 곳을 남겼다는 주장이 사실**로 검증됐다 — `KlassService.loadForCommand`(정원 축소가 깨지는 시나리오 포함) · `KlassQueryPort` · `KlassJpaRepository`(`@EntityGraph` 함께 금지 이유).

Check 단계에서 3건이 추가됐다.

| ID | 내용 |
|----|------|
| **D-22** | `KlassPeriod` 미생성 — 컨트롤러가 현재 값을 몰라 쌍을 만들 수 없다. 서비스가 조립 |
| **D-23** | `application-test.yml` 미생성 — 계측을 `@DataJpaTest(properties=...)` 로 국소화 |
| **D-24** | `CursorPageResult` 를 `common` 으로 — `klass` 에 두면 `enrollment` 가 `klass` 를 경유해야 재사용된다 |

---

## 5. 확인됨 (문제 없는 영역)

- **§6.3 검사 순서** — 존재 → 가시성 → 소유권. 2·3 이 뒤집히지 않았고 L2 테스트가 `as("가시성 검사가 소유권보다 먼저")` 로 고정
- **§4.2 선택적 인증** — `SecurityConfig` 가 설계와 문자 단위 일치. `hasRole` 먼저 + `{id:[0-9]+}` 좁힘 두 방어선, `CREATOR_ROLE = "CREATOR"`(`ROLE_` 미포함)
- **§3.5 가시성 / D-14** — 공개 목록이 `viewerId` 를 **아예 받지 않는다.** 본인 DRAFT 가 구조적으로 섞일 수 없다
- **헥사고날 의존 규칙** — `domain` 에 Spring 타입 0건, `application.service` → `adapter` 0건, `adapter.in` 이 엔티티 미노출
- **`Clock` 주입** — `src/main` 전체에 무인자 `now()` 0건
- **ERD 정본 개정 3곳** — mermaid · 컬럼표 · DDL + Version History v1.11 이 어긋나지 않음
- **응답 계약** — 상세 13필드 · 요약 9필드 · `hasNext`/`nextCursor` 전부 설계와 일치

---

## 6. 최종 상태

```
./gradlew clean build  →  BUILD SUCCESSFUL
테스트 195건 / 실패 0 / 오류 0
openapi3.json  path 8 / 오퍼레이션 10
```

| 문서 | 버전 | 갱신 내용 |
|------|:----:|-----------|
| `klass-management.plan.md` | **0.6** | FR-06 → `Deferred (D-21)`, 락 서술 3곳 |
| `klass-management.design.md` | **1.1** | §6.5 표 · §2.4 한계 · §11.1 파일 목록 · D-22~D-24 · **§4.3 `KlassPeriod` 초안 정정** |
| `class-enrollment-erd.design.md` | 1.11 | (Do 단계에서 갱신됨) |
| `CLAUDE.md` | — | 범위 경계 재기술 + D-21 좌표 안내 |

---

## 7. 다음 단계

1. [ ] `/pdca report klass-management`
2. [ ] `/pdca archive klass-management`

**수강신청 사이클로 넘길 것** (전부 코드 주석에 좌표가 있음):
- `findByIdForUpdate` 복구 → `KlassService.loadForCommand` (D-21)
- `enrollment_count` 증감 → `Klass` 에 메서드 신설
- 정원 증가 시 대기열 승격 → `Klass.changeCapacity` (D-16)
- `CLOSED` 전이 시 잔여 `WAITING` 정리 → `Klass.close` (D-16)

---

## 변경 이력

| 버전 | 날짜 | 변경 | 작성자 |
|------|------|------|--------|
| 0.1 | 2026-09-02 | 갭 분석 + 전건 수정 완료. Match Rate 93% | developer2@lulumedic.com |
| 0.4 | 2026-09-02 | 사용자 정책 확정 반영 — 수정 방식을 **전체 교체**로(D-25) 두고 `PATCH` → **`PUT`**(D-27), **공개 후에는 제목만 변경 가능**(D-28). 낙관적 잠금은 불필요 판정. `CANCELLATION_PERIOD_NOT_EDITABLE` 제거(에러 코드 6종), `CAPACITY_BELOW_ENROLLMENT` 는 이 API 로 도달 불가해져 도메인 불변식 전용이 됐다 | developer2@lulumedic.com |
| 0.3 | 2026-09-02 | **후속 Critical 1건 발견·수정** — §4.3 이 "`@NotBlank` 가 막는다"고 약속했으나 `UpdateKlassRequest.description` 에 제약이 **하나도 없어** 공백 값이 도메인까지 도달했다. D-18 의 필수값 취지가 **수정 경로에서만** 무너져 있던 것. `@NotBlank` 는 `null` 도 거부해 PATCH 를 PUT 으로 만들므로 `@Pattern(regexp = "(?s).*\\S.*")` 으로 차단. L3 3건 추가 | developer2@lulumedic.com |
| 0.2 | 2026-09-02 | 재검증에서 후속 2건 발견·수정 — §4.3 이 D-22 를 반영하지 않아 `KlassPeriod` 초안이 남아 있었다(§11.1·§12 는 갱신됨). **C-1 과 같은 "문서 일부만 갱신" 패턴의 재발**이며, 이번엔 API 스펙 섹션이라 파급이 더 컸다. divergence 표 ID 순서도 정렬 | developer2@lulumedic.com |
