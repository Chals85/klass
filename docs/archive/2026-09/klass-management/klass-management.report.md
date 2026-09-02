# 강의 관리 기능 완료 보고서

> **Status**: Complete
>
> **Project**: klass
> **Version**: 0.0.1-SNAPSHOT
> **Author**: developer2@lulumedic.com
> **Completion Date**: 2026-09-02
> **PDCA Cycle**: #2 (#1 = project-setup)

---

## Executive Summary

### 1.1 사이클 개요

| 항목 | 내용 |
|------|------|
| 기능 | 강의(Klass) 관리 — 등록 · 전체 교체 · 상태 전이 · 조회 3종 |
| 기간 | 2026-09-02 (단일 세션 내 Plan → Design → Do → Check → Report) |
| 산출물 | API 6개 · 신규 파일 32 · 테스트 207건 · 문서 4종 |
| 커밋 | 3개 (`feat/klass-management` 브랜치) |
| 변경 규모 | 46 파일 / +6,235 / −42 |

### 1.2 결과 요약

```
┌──────────────────────────────────────────────┐
│  Match Rate: 97%   (게이트 90% 통과)          │
├──────────────────────────────────────────────┤
│  ✅ 구현      16 / 17 FR                      │
│  ⏸ Deferred   1 / 17 FR  (FR-06 비관적 락)    │
│  ❌ 취소       0 / 17 FR                      │
├──────────────────────────────────────────────┤
│  테스트 207건 / 실패 0 / 오류 0                │
│  divergence 15건 (D-14 ~ D-28) 전건 등재       │
└──────────────────────────────────────────────┘
```

### 1.3 실제로 전달된 가치

| 관점 | 내용 |
|------|------|
| **Problem** | 1차에서 `klass` 테이블과 엔티티는 만들었지만 **강의를 만들 방법도 볼 방법도 없었다.** 수강신청(2차 본체)은 신청할 강의가 존재해야 성립하므로 이것이 선행 조건이었다. |
| **Solution** | ERD 정본이 이미 확정한 규약(상태 전이표 §3.4 · 소유권 §7 · 인덱스 §5)을 코드로 이행했다. 권한은 `ROLE_CREATOR`(설정)와 소유권(서비스) **두 겹**으로 나누고, 목록은 인덱스가 전제한 커서 방식으로 읽는다. 수정은 **전체 교체**이며 공개 후에는 **제목만** 바뀐다. |
| **Function/UX 효과** | 크리에이터가 강의를 초안으로 만들어 다듬고 공개·마감할 수 있다. 일반 사용자에게는 공개된 강의만 보이고 **남의 초안은 존재조차 드러나지 않는다**(404). 비로그인 방문자도 목록·상세를 볼 수 있다. 목록은 강의 수가 늘어도 응답 시간이 일정하다(커서 + 인덱스 + fetch join). |
| **Core Value** | "남의 강의는 건드릴 수 없다"와 "공개된 뒤에는 신청 조건이 바뀌지 않는다"를 **설정 한 곳이 아니라 도메인 규칙으로** 보증한다. `ROLE_CREATOR` 만 검사하면 크리에이터끼리 서로의 강의를 수정할 수 있고, 상태 검사를 6개 `change*` 에 흩어 두면 하나를 빠뜨렸을 때 그 필드만 조용히 새어나간다 — 두 구멍을 처음부터 닫힌 채로 시작했다. |

---

## 1.4 Success Criteria 최종 상태

| # | 기준 | 상태 | 근거 |
|---|------|:----:|------|
| SC-1 | 타 크리에이터의 강의 수정·상태 전이 시도가 403 | ✅ | `KlassService.loadForCommand` + L4 `otherCreatorCannotUpdate`(`error.code == NOT_KLASS_OWNER`) + L2 `rejectsOtherCreatorsPublishedKlass` |
| SC-2 | 금지된 상태 전이가 전부 거부 | ✅ | `Klass.publish`(전제 `== DRAFT`) · `close`(전제 `!= CLOSED`) + 서비스 `case DRAFT` → **9개 조합 전부 커버.** L1 파라미터화 3건 + L2 `rejectsDraftAsTarget` |
| SC-3 | DRAFT 가 공개 목록·타인 상세 조회에서 완전 비노출 | ✅ | `PUBLIC_STATUSES` + `isVisibleTo` + 어댑터 가드 + L4 `anonymousCannotSeeDraft` + L2 `firstPageExcludesDraft` |
| SC-4 | 커서 목록이 중복·누락 없이 이어짐 | ✅ | `cursorLt` 가 `<`(`<=` 아님) + L2 `cursorPaginationHasNoOverlapOrGap`(3페이지 순회, `hasSize(7).doesNotHaveDuplicates()`) |

**Success Rate: 4/4 (100%)**

---

## 1.5 Decision Record 요약

| 출처 | 결정 | 이행 | 결과 |
|------|------|:----:|------|
| [Plan] | 아키텍처 **Option C** 실용적 균형 | ✅ | 유즈케이스별 포트 5 + 단일 서비스 + QueryDSL 커서 |
| [Plan] | 목록을 **둘로** 분리 (공개 / 내 강의) | ✅ | 사용자가 Checkpoint 1 에서 지적. `GET /v1/klasses` · `/me` |
| [Plan] | 커서 페이지네이션 | ✅ | `size + 1` 조회로 `COUNT(*)` 없이 `hasNext` 판정 |
| [Plan] | 정방향 전이만 | ⚠️ **부분 변경** | Check 에서 ERD 가 `DRAFT → CLOSED`(개설 철회)를 허용함을 발견 → 3종으로 정정 (D-18) |
| [Design] | 비관적 락 (FR-06) | ❌ **Deferred** | 막을 상대(수강신청)가 2차 범위라 **지금은 아무것도 직렬화하지 않음** (D-21) |
| [Design] | 부분 수정 (PATCH) | ❌ **전환** | 사용자가 **전체 교체**로 변경 (D-25) → `PUT` 개명 (D-27) |
| [사용자] | 공개 후에는 **제목만** 변경 가능 | ✅ | `Klass.isFullyEditable()` 단일 판정 (D-28) |
| [사용자] | 낙관적 잠금 미도입 | ✅ | 강의 수정 규모에서 lost update 비용 < `@Version` 도입 비용 |

---

## 2. 관련 문서

| 단계 | 문서 | 버전 | 상태 |
|------|------|:----:|------|
| Plan | [klass-management.plan.md](./klass-management.plan.md) | 0.7 | ✅ 확정 |
| Design | [klass-management.design.md](./klass-management.design.md) | 1.1 | ✅ 확정 |
| Check | [klass-management.analysis.md](./klass-management.analysis.md) | 1.0 | ✅ 완료 |
| — | [class-enrollment-erd.design.md](../../../02-design/features/class-enrollment-erd.design.md) | 1.12 | ✅ 개정 (데이터 모델 정본) |
| Act | 현재 문서 | 1.0 | 🔄 |

---

## 3. 완료 항목

### 3.1 기능 요구사항

| ID | 요구사항 | 상태 |
|----|---------|:----:|
| FR-01 | 강의 등록 (상태는 항상 `DRAFT`) | ✅ |
| FR-02 | 강의 수정 — **전체 교체** (Plan 원안 "부분 수정"에서 개정, D-25) | ✅ |
| FR-03 | 상태 전이 — 허용 3종 (`DRAFT→OPEN`·`DRAFT→CLOSED`·`OPEN→CLOSED`) | ✅ |
| FR-04 | 정원 축소 방어 | ✅ (도메인 불변식 — §5.3 참조) |
| FR-05 | 소유권 검사 (권한 + `creator_id == sub`) | ✅ |
| **FR-06** | **상태 전이·정원 수정의 비관적 락** | ⏸ **Deferred (D-21)** |
| FR-07 | `updated_at` 갱신, 주입된 `Clock` 사용 | ✅ |
| FR-08 | 상세 조회 (타인 DRAFT → **404**) | ✅ |
| FR-09 | 공개 목록 (커서, `DRAFT` 제외) | ✅ |
| FR-10 | 내 강의 목록 (`DRAFT` 포함) | ✅ |
| FR-11 | `hasNext`·`nextCursor`, 총 개수 미제공 | ✅ |
| FR-12 | 개설자 정보 포함, fetch join 으로 N+1 차단 | ✅ |
| FR-13 | 상태 필터 `?status=` | ✅ |
| FR-14 | `ApiResponse<T>` 봉투, boolean `is`/`has` 접두어 | ✅ |
| FR-15 | RestDocs 스니펫 6종 → `openapi3.json` | ✅ |
| FR-16 | 검증 실패 400 + `details` | ✅ |
| **FR-17** | **공개 후에는 제목만 변경 가능** (사용자 정책, D-28) | ✅ |

**16/17 구현 · 1 Deferred**

### 3.2 비기능 요구사항

| 항목 | 목표 | 달성 | 상태 |
|------|------|------|:----:|
| 목록 조회 인덱스 사용 | `idx_klass_status` / `idx_klass_creator` | 커서 + `id DESC` 고정으로 정렬 작업 0 | ✅ |
| 목록 N건 조회 SQL 횟수 | 2회 이하 | **개설자 접근 시 추가 쿼리 0** (fetch join) | ✅ |
| 수평 권한 상승 차단 | 타 크리에이터 403 | L4 `otherCreatorCannotUpdate` | ✅ |
| 정보 노출 차단 | 타인 DRAFT 존재 비노출 | 목록 제외 + 상세 404 | ✅ |
| 무인자 `now()` 부재 | 0건 | 0건 (오탐 3건 검증 완료) | ✅ |
| 문서 산출물 검증 | `documentationTest` 통과 | path 8 / 오퍼레이션 10 단언 | ✅ |

### 3.3 산출물

| 산출물 | 위치 | 수 |
|--------|------|:--:|
| 도메인 | `klass/domain/` | 3 (`Klass` 확장 · `KlassStatus` · `KlassError`) |
| 포트 | `klass/application/port/` | 7 (in 5 · out 2) |
| DTO | `klass/application/dto/` · `klass/adapter/in/web/dto/` | 13 |
| 어댑터 | `klass/adapter/out/persistence/` | 3 |
| 서비스·컨트롤러 | `klass/application/service/` · `adapter/in/web/controller/` | 2 |
| 공용 | `common/application/dto/CursorPageResult` · `common/adapter/in/web/dto/CursorPageResponse` | 2 |
| 인프라 | `infrastructure/config/QueryDslConfig` | 1 |
| 테스트 | L1 · L2 × 3 · L3 · L4 | 6 파일 / **강의 관련 약 110건** |
| API 문서 | `/docs/api-guide.html` · `openapi3.json` | 스니펫 6종 |

---

## 4. 미완료 항목

### 4.1 다음 사이클(수강신청)로 이관 — 전부 코드에 좌표가 있다

| 항목 | 좌표 | 근거 |
|------|------|------|
| `findByIdForUpdate` 복구 (FR-06) | `KlassService.loadForCommand` javadoc | D-21 — **정원 축소가 깨지는 시나리오까지** 주석에 있다 |
| `enrollment_count` 증감 | `Klass` 신설 | 이 사이클은 읽기만 한다 |
| 정원 증가 시 대기열 승격 | `Klass.changeCapacity` | D-16 (ERD §4.8 capacity 5번) |
| `CLOSED` 전이 시 잔여 `WAITING` 정리 | `Klass.close` | D-16 (ERD §4.8 상태 전이 5번) |
| `CAPACITY_BELOW_ENROLLMENT` API 레벨 테스트 | — | §5.3 — 정원 변경 경로가 생기면 다시 도달 가능 |

### 4.2 알려진 검증 공백 2건

| 항목 | 내용 | 위험도 |
|------|------|:------:|
| 같은 상태 재호출의 **HTTP 레벨** 검증 | `OPEN → OPEN` 등이 도메인(L1)에서는 409 로 검증되지만, 실제 API 를 두 번 호출했을 때 필터 체인·Advice 를 거쳐 **409 응답이 되는지**는 L4 에 없다 | 낮음 (경로가 다른 409 케이스로 이미 검증됨 — L4 `rejectsReopening`) |
| L2 서비스 부분 케이스 1건 | `description` 만 지정하는 수정. 전체 교체 전환으로 **개념 자체가 사라져** 자연 소멸 | — |

### 4.3 취소·보류

없음.

---

## 5. 품질 지표

### 5.1 Check 결과 추이

| 축 | 1차 (93%) | 2차 재기준화 (97%) | 변화 |
|----|:--------:|:----------------:|:----:|
| 구조적 | 94% | **100%** | +6 |
| 기능적 | 90% | **94%** | +4 |
| API 계약 | 85% | **100%** | +15 |
| 런타임 | 100% | 100% | — |
| **종합** | **93%** | **97%** | **+4** |

계약 축 상승은 C-1(PATCH 요청 필드 5개 누락)·I-1(`cursor` 파라미터 누락) 수정에서, 구조 축 상승은 D-22~D-24 등재로 "설계에 없는 파일" 이 해소된 데서 왔다.

### 5.2 해결한 이슈

**Critical 2건**

| 이슈 | 왜 위험했나 | 해결 |
|------|------------|------|
| `SecurityConfig` 규칙 순서 | `permitAll` 이 `hasRole` 보다 앞서면 **`/me` 가 무인증으로 열린다.** 컴파일·L1~L3 전부 통과한다 | 순서 교정 + 매처를 `{id:[0-9]+}` 로 좁혀 방어선 이중화 |
| `PATCH /{id}` 문서에 요청 필드 5개 누락 | RestDocs 는 **실제 보낸 payload 만** 문서화한다. L5 는 오퍼레이션 수만 세므로 **어떤 테스트도 실패하지 않았다** | payload·`requestFields` 7필드로 확장 |

**설계 자체의 오류 3건**

| 이슈 | 내용 |
|------|------|
| ERD 정본과 상충 | 설계가 `DRAFT → CLOSED`(개설 철회)를 거부로 좁혔으나 ERD §3.4·§4.8 은 **허용**한다. ERD 대로 복원 |
| `@NotBlank` 처방 오류 | §4.3 이 "`@NotBlank` 가 막는다"고 약속했으나 코드엔 제약이 **하나도 없었다.** `@NotBlank` 는 `null` 도 거부해 PATCH 를 깨뜨리므로 `@Pattern` 으로 해결 |
| "테스트가 고정한다"는 거짓 주장 | §2.4 가 가리킨 테스트는 `@EntityGraph` 때문에 **항상 통과**했다. 조인 없는 경로로 읽도록 실효화 |

**"통과하지만 아무것도 검증하지 않는" 테스트 10종**

세 번 잡아 고친 뒤 7건이 더 발견됐다. 대표 사례:

| 유형 | 예 |
|------|-----|
| 상태코드만 단언 | `withdrawDraft` — 리다이렉트된 HTML 도 200 으로 통과. **이 파일 javadoc 이 바로 그 형태를 금지한다** |
| 예외 없음만 단언 | `allowsCapacityEqualToEnrollment` — `changeCapacity` 가 조용히 아무것도 안 해도 통과 |
| 빈 컬렉션 통과 | `creatorIsInitialized` — `allSatisfy` 가 빈 결과에서 무조건 참 |
| 조건이 항상 참 | `ownershipCheckDoesNotTriggerQuery` — fetch join 경로라 실패 불가 |
| 예외 타입이 너무 넓음 | `isInstanceOf(Exception.class)` — SQL 문법 오류로도 통과 |
| 절대값이 구현 세부에 묶임 | N+1 쿼리 카운트 — 한 JPQL 이 prepared statement 2개를 만든다 |

**문서 갱신 누락 4건** (같은 패턴의 반복)

C-1(요청 필드) → §4.3 `KlassPeriod` 초안 잔존 → FR 상태 열 전부 `Pending` → 에러 코드 수 오기. 매번 **갱신한 곳은 맞고 갱신하지 않은 곳이 거짓말을 한다.**

### 5.3 정책이 만든 파생 결과

**`CAPACITY_BELOW_ENROLLMENT` 가 이 API 로는 도달 불가해졌다.**

```
정원은 DRAFT 에서만 변경 가능 (D-28)
DRAFT 는 신청을 받지 못한다 (ERD 정본 §2.2)
OPEN → DRAFT 역전이 차단 (D-18)
→ DRAFT 강의의 enrollment_count 는 항상 0
→ "이미 앉은 인원보다 적게 줄이는" 상황이 발생 불가
```

**이것이 테스트 실패로 드러났다** — `propagatesDomainViolation` 이 도달 불가한 경로를 단언하고 있었다. `INVALID_KLASS_PERIOD` 로 교체하고, 원래 에러는 도메인 불변식 전용으로 남겨 `KlassTest` 가 직접 검증한다. FR-04 가 "✅(도메인 불변식)" 인 이유다.

---

## 6. 회고

### 6.1 잘된 것 (Keep)

**① 설계 문서에 "왜"를 남긴 것이 반복해서 값을 냈다.** `JwtAuthenticationFilter` 의 "이 필터는 요청을 직접 거부하지 않는다"는 주석 덕분에 선택적 인증이 이미 가능함을 즉시 알았다. ERD §4.8 의 화이트리스트 근거가 `DRAFT → CLOSED` 를 되돌릴 판단의 기준이 됐다.

**② 검증을 여러 레벨에 겹친 것.** S1~S8 중 6건이 두 레벨에서 검증된다. L2 가 검사 **순서**를, L4 가 필터 체인이 실제로 이어지는지를 본다 — 어느 하나만으로는 부족했다.

**③ 되돌아올 좌표를 코드에 남긴 것.** D-21(락)·D-16(대기열) 모두 "지금은 발현하지 않는다"로 끝내지 않고 **깨지는 시나리오까지** javadoc 에 적었다. Check 에서 그 주장이 사실인지 검증할 수 있었다.

**④ "빌드가 통과한다"를 신뢰하지 않은 것.** 이 사이클에서 발견한 문제 중 **테스트 실패로 드러난 것은 하나도 없다.** 전부 초록불 상태에서 찾았다.

### 6.2 개선할 것 (Problem)

**① 커밋을 미뤄 복구 지점이 없었다.** 두 번 "커밋할까요?"만 묻고 넘어간 탓에, 에이전트가 파일을 덮어썼을 때 **PATCH 구현이 디스크에서 사라졌다.** `git` 으로 되돌릴 수 없어 재작성이 유일한 방법이었다. 결과적으로 방식을 바꾸기로 해 손실은 없었지만 운이 좋았다.

**② 서브에이전트를 방치했다.** `gap-detector` 에 정적 분석만 지시했는데 **하위 구현 에이전트를 띄워 사용자 확정 결정을 뒤집었다**(PATCH → 전체 교체). 재보고가 5회 오는 동안 끊지 않았고 누적 54만 토큰을 썼다. 첫 보고에서 "아무것도 수정하지 않았다"고 했다가 나중에 "문서 2곳 수정"으로 **자기 보고가 어긋났고**, 마지막에는 **존재하지 않는 지시를 전제**로 답했다.

**③ 문서 일부만 갱신하는 실수를 네 번 반복했다.** 매번 갱신한 곳은 맞았고, 갱신하지 않은 절이 거짓을 말했다. §4.3(API 스펙)에서 일어났을 때 파급이 가장 컸다.

**④ 설계 단계에서 검증 불가능한 테스트를 계획했다.** L3 에 403·401 케이스를 넣었는데 그 슬라이스에는 `SecurityConfig` 가 없다. `BaseControllerTest` javadoc 이 "보안 필터가 꺼져 있어 JWT 인증이 실제로 동작하는지 검증하지 못한다"고 **이미 적어 뒀는데도** 놓쳤다.

### 6.3 다음에 시도할 것 (Try)

**① 모듈 완료 시점마다 커밋한다.** `module-1` 이 끝났을 때 커밋했다면 위 ①이 발생하지 않았다.

**② 서브에이전트에 쓰기 권한을 주지 않거나, 재보고 2회에서 끊는다.** 이번 재기준화는 직접 대조했는데 결과가 더 정확했다(FR 상태 열 갭은 에이전트가 4회 보고에서 한 번도 지적하지 않았다) 비용도 훨씬 낮았다.

**③ 문서를 고칠 때 같은 개념이 등장하는 모든 절을 grep 으로 먼저 훑는다.** CLAUDE.md 가 코드에 대해 요구하는 것(단어 경계 grep)을 문서에도 적용한다.

**④ 테스트를 쓸 때 "이 테스트가 실패할 수 있는가"를 먼저 자문한다.** 발견한 무용 테스트 10종 전부가 이 질문 하나로 걸러진다.

---

## 7. 프로세스 개선 제안

### 7.1 PDCA

| 단계 | 이번 사이클의 문제 | 제안 |
|------|------------------|------|
| Plan | 확정 결정이 문서에만 남아 에이전트가 뒤집을 수 있었다 | Plan §3.3 의 확정 사항을 **커밋으로 잠근다** |
| Design | 검증 불가능한 테스트를 계획했다 (L3 의 403/401) | Design §8 작성 시 **각 레벨이 무엇을 볼 수 없는지** 먼저 적는다 |
| Do | 모듈 완료 후 커밋하지 않아 복구 지점이 없었다 | `--scope` 단위마다 커밋 |
| Check | 서브에이전트가 지시 범위를 넘었다 | 읽기 전용 에이전트만 쓰거나 직접 대조 |

### 7.2 이 저장소에 추가할 함정 기록

CLAUDE.md 의 "컴파일러가 잡지 못하는 지점" 에 넣을 만한 것들:

| 함정 | 증상 |
|------|------|
| `hasRole("ROLE_X")` | `ROLE_ROLE_X` 를 찾아 **항상 403.** 컴파일·테스트 통과 |
| `@Validated` + `@RequestParam` 제약 | Spring 6.1+ 에서 AOP 검증이 내장 검증을 밀어내 `ConstraintViolationException` → **500** |
| `SecurityConfig` 규칙 순서 | `permitAll` 이 앞서면 뒤의 `hasRole` 이 도달 불가 |
| `@WebMvcTest` + `addFilters=false` | 권한·인증이 **전혀 검증되지 않는다** |
| RestDocs `requestFields` | 테스트가 보낸 payload 만 문서화. 필드를 빼면 **스펙에서 조용히 사라진다** |
| Java 텍스트 블록의 `\n` | 실제 개행이 되어 JSON 제어문자 위반 → 400 |

---

## 8. 최종 상태

```
./gradlew clean build  →  BUILD SUCCESSFUL
테스트 207건 / 실패 0 / 오류 0

/v1/klasses             → GET, POST
/v1/klasses/{id}        → GET, PUT
/v1/klasses/{id}/status → PATCH
/v1/klasses/me          → GET

openapi3.json  path 8 / 오퍼레이션 10
divergence     15건 (D-14 ~ D-28)
```

커밋 (`feat/klass-management`):

```
8566eb4  docs: Check 재기준화 — Match Rate 97%
725a4ae  feat: 공개된 강의는 제목만 수정 가능하도록 제한
9dfa55b  feat: 강의 관리 기능 — 등록·전체교체·상태전이·조회 3종
```

---

## 변경 이력

| 버전 | 날짜 | 변경 | 작성자 |
|------|------|------|--------|
| 1.0 | 2026-09-02 | 최초 작성. Match Rate 97%, SC 4/4, FR 16/17 | developer2@lulumedic.com |
