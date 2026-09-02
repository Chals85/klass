# 강의 관리 기능 갭 분석 (Check — 재기준화)

> **Summary**: 정책 확정(D-25 전체 교체 · D-27 PUT · D-28 제목만 수정) 이후 재분석. **Match Rate 97%**. FR 17건 중 16 구현 · 1 Deferred(FR-06 비관적 락, D-21). 런타임 207건 전부 통과. 이번 회차에서 문서 갭 2건(FR 상태 열 미갱신, 에러 코드 수 오기)을 발견해 수정했다.
>
> **Project**: klass
> **Author**: developer2@lulumedic.com
> **Date**: 2026-09-02
> **Status**: Resolved
> **Design Doc**: [klass-management.design.md](./klass-management.design.md) v1.1
> **Plan Doc**: [klass-management.plan.md](./klass-management.plan.md) v0.7

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
| 구조적 (Structural) | **100%** | `klass` 28파일 + `common` 2 + `QueryDslConfig`. 엔드포인트 6/6. 설계에 없는 3건은 D-22·D-23·D-24 로 등재 |
| 기능적 (Functional) | **94%** | FR 17건 중 16 구현 · 1 Deferred(FR-06 → D-21, 근거 있는 의도적 미이행) |
| API 계약 (Contract) | **100%** | `openapi3.json` path 8 / 오퍼레이션 10. `UpdateKlassRequest` 7속성 · required 6(`cancellationPeriodDays` 만 선택) |
| 런타임 (Runtime) | **100%** | `./gradlew clean build` 통과, **207건 전부 성공** |
| **종합** | **97%** | `0.15×100 + 0.25×94 + 0.25×100 + 0.35×100` |

> **직전 회차 93% → 97%.** 상승분은 계약 축(85 → 100, C-1·I-1 수정)과 구조 축(94 → 100, D-22~D-24 등재로 "설계에 없는 파일"이 해소)에서 왔다.

---

## 2. 정책 확정 사항의 코드 반영

이번 회차의 핵심은 **사용자 정책 3건이 코드·문서·테스트에 일관되게 반영됐는지**다.

| 정책 | 코드 | 테스트 |
|------|------|--------|
| **D-25** 전체 교체 | `UpdateKlassRequest` 6필드 `@NotNull`/`@NotBlank` (`cancellationPeriodDays` 만 선택) | `openapi3.json` required 6종 |
| **D-27** `PATCH` → `PUT` | `@PutMapping("/{id}")` · `/{id}/status` 는 `@PatchMapping` 유지 | `openapi3.json` `GET,PUT` / L3 7곳 · L4 2곳 |
| **D-28** 공개 후 제목만 | `Klass.isFullyEditable()` + `KlassService.update` 분기 | **L1 4건 · L2 3건 · L4 1건** |
| 낙관적 잠금 미도입 | `@Version` 없음 | — |

### 2.1 D-28 테스트 커버리지

| 레벨 | 테스트 | 검증 대상 |
|------|--------|-----------|
| L1 | `draftIsFullyEditable` · `publishedIsNotFullyEditable` | 판정 자체 |
| L1 | `editabilityNeverReturns` | DRAFT→OPEN→CLOSED 순회에서 **되돌아오지 않음** (D-18 의존) |
| L1 | `changeMethodsDoNotGuardStatus` | `change*` 가 상태를 검사하지 않는다는 계약 |
| L2 | `ignoresNonTitleFieldsOnPublishedKlass` · `...OnClosedKlass` | 7필드를 다른 값으로 보내도 제목만 바뀜 |
| L2 | `replacesAllFieldsOnDraft` | DRAFT 는 전 필드 반영 |
| L4 | `ignoresNonTitleFieldsAfterOpen` | **응답 본문**으로 무시를 확인 (상태코드는 200) |
| L4 | `allowsCancellationPeriodChangeOnDraft` | DRAFT 취소기간 변경 가능 |

> **L4 가 특히 중요하다.** 무시 정책에서 응답은 **200** 이므로 상태코드만 단언하면 "수정 성공"으로 읽힌다 — 이 사이클에서 반복해 잡은 위양성의 또 다른 형태다. 본문의 각 필드가 원값인지까지 확인해야 정책이 고정된다.

### 2.2 판정 지점을 하나로 모은 근거

`isFullyEditable()` 이 유일한 판정이고 `change*` 6종은 상태를 검사하지 않는다. 검사를 각 메서드에 심으면 **6곳에 같은 조건이 복제되고, 그중 하나를 빠뜨리면 그 필드만 조용히 공개 후에도 바뀐다.** `isOwnedBy`·`isVisibleTo` 와 같은 자리 — 도메인이 판단하고 서비스가 조립한다.

`changeMethodsDoNotGuardStatus` 테스트가 이 계약을 명문화한다 — `change*` 를 직접 호출하면 값이 바뀌며, 막는 것은 호출자의 책임이다.

---

## 3. 정책이 만든 파생 결과 2건

### 3.1 `CANCELLATION_PERIOD_NOT_EDITABLE` 제거

취소기간만의 규칙(D-26)이 신청 조건 전부로 일반화되면서 전용 에러 코드가 존재 이유를 잃었다. **에러 코드 8 → 7종.**

### 3.2 `CAPACITY_BELOW_ENROLLMENT` 가 이 API 로는 도달 불가

```
정원은 DRAFT 에서만 변경 가능 (D-28)
DRAFT 는 신청을 받지 못한다 (ERD 정본 §2.2)
OPEN → DRAFT 역전이 차단 (D-18)
→ DRAFT 강의의 enrollment_count 는 항상 0
→ "이미 앉은 인원보다 적게 줄이는" 상황이 발생 불가
```

**이 때문에 `KlassServiceTest.propagatesDomainViolation` 이 실패했다** — 도달 불가한 경로를 단언하고 있었다. `INVALID_KLASS_PERIOD`(DRAFT 에서 실제 도달 가능)로 교체하고, 원래 에러는 **도메인 불변식 전용**으로 남겨 `KlassTest` 가 직접 검증한다.

> 수강신청 사이클에서 정원 변경 경로가 새로 생기면 다시 도달 가능해진다. 그때 API 레벨 테스트를 붙일 자리다.

---

## 4. 이번 회차에서 발견·수정한 갭 2건

정책 변경 자체는 정합하게 반영됐고, **문서 갱신 누락**이 둘 있었다.

| # | 갭 | 왜 문제인가 | 조치 |
|---|-----|------------|------|
| **G-1** | Plan 의 FR-01~FR-16 이 전부 `Pending` | FR-06·FR-17 만 손댄 탓. **문서만 보면 아무것도 구현되지 않은 것으로 읽힌다** | 14건 `✅` 로 갱신 (Plan v0.7) |
| **G-2** | "에러 코드 7 → 6종" 오기 | 실제는 **8 → 7종.** 설계 v1.1 이력과 분석 문서 두 곳에 잘못 적혀 있었다 | 정정 |

**G-1 은 이 사이클에서 반복된 패턴이다** — C-1(PATCH 요청 필드), §4.3 `KlassPeriod` 초안 잔존에 이어 세 번째 "문서 일부만 갱신". 매번 **갱신한 곳은 맞고 갱신하지 않은 곳이 거짓말을 하는** 형태다.

---

## 5. 확인됨

- **§6.3 검사 순서** — 존재 → 가시성 → 소유권. L2 가 `as("가시성 검사가 소유권보다 먼저")` 로 고정
- **§4.2 선택적 인증** — `hasRole` 먼저 + `{id:[0-9]+}` 좁힘 두 방어선. `CREATOR_ROLE = "CREATOR"`(`ROLE_` 미포함)
- **D-21 락 좌표 3곳** — `KlassService.loadForCommand` · `KlassQueryPort` · `KlassJpaRepository` javadoc 에 근거와 함께 실재
- **`Clock` 주입** — `src/main` 의 무인자 `now()` 3건은 **전부 오탐**(javadoc 언급 2 + 헬퍼 메서드 선언 1). 위반 없음
- **헥사고날 의존 규칙** — `domain` 에 Spring 타입 0건, `application.service` → `adapter` 0건
- **divergence 15건** — D-14 ~ D-28, ID 순서 정렬 완료. 전건 코드 반영 확인

---

## 6. Plan Success Criteria

| 기준 | 상태 | 근거 |
|------|:----:|------|
| 타 크리에이터 수정·전이 403 | ✅ | `loadForCommand` + L4 `otherCreatorCannotUpdate` |
| 금지된 상태 전이 전부 거부 | ✅ | `publish`/`close` 전제 + L1 파라미터화 |
| DRAFT 완전 비노출 | ✅ | `PUBLIC_STATUSES` + `isVisibleTo` + L4 `anonymousCannotSeeDraft` |
| 커서 무중복·무누락 | ✅ | `cursorLt` 가 `<` + L2 `hasSize(7).doesNotHaveDuplicates()` |

**4/4 충족.**

---

## 7. 최종 상태

```
./gradlew clean build  →  BUILD SUCCESSFUL
테스트 207건 / 실패 0 / 오류 0
openapi3.json  path 8 / 오퍼레이션 10

/v1/klasses            → GET, POST
/v1/klasses/{id}       → GET, PUT
/v1/klasses/{id}/status → PATCH
/v1/klasses/me         → GET
```

| 문서 | 버전 |
|------|:----:|
| plan | 0.7 |
| design | 1.1 |
| analysis | 1.0 |
| ERD 정본 | 1.12 |

커밋 2개 (`feat/klass-management` 브랜치): `9dfa55b` 기능 구현 · `725a4ae` 제목만 수정 정책

---

## 8. 수강신청 사이클로 넘길 것

전부 코드 주석에 좌표가 있다.

| 항목 | 좌표 | 근거 |
|------|------|------|
| `findByIdForUpdate` 복구 | `KlassService.loadForCommand` javadoc | D-21 — 막을 상대(수강신청)가 생기는 시점 |
| `enrollment_count` 증감 | `Klass` 신설 | 이 사이클은 읽기만 한다 |
| 정원 증가 시 대기열 승격 | `Klass.changeCapacity` | D-16 |
| `CLOSED` 전이 시 잔여 `WAITING` 정리 | `Klass.close` | D-16 |
| `CAPACITY_BELOW_ENROLLMENT` API 테스트 | — | §3.2 — 정원 변경 경로가 생기면 다시 도달 가능 |

---

## 변경 이력

| 버전 | 날짜 | 변경 | 작성자 |
|------|------|------|--------|
| 1.0 | 2026-09-02 | 정책 확정(D-25·D-27·D-28) 후 **재기준화**. Match Rate 93% → **97%**. 문서 갱신 누락 2건(G-1 FR 상태 열, G-2 에러 코드 수) 발견·수정. 직전 이력은 정책 변경 전 기준이라 이 문서로 대체 | developer2@lulumedic.com |
