# 수강신청 · 취소 갭 분석 보고서

> **Analysis Type**: Gap Analysis (Design vs Implementation)
>
> **Project**: klass (강의 수강신청 + JWT 인증 백엔드)
> **Version**: 2차 — 수강신청 사이클
> **Analyst**: Chals85
> **Date**: 2026-09-03
> **Design Doc**: [enrollment-management.design.md](./enrollment-management.design.md)
> **Plan Doc**: [enrollment-management.plan.md](./enrollment-management.plan.md)

---

## Context Anchor

| Key | Value |
|-----|-------|
| **WHY** | `enrollment_count` 를 **쓰는 코드가 없어** 정원 검사가 무의미했다. 이번 사이클이 그 최초의 코드를 만들었다 |
| **WHO** | 수강생(ROLE_USER) / 크리에이터(ROLE_CREATOR). 겸용 사용자는 **자기 강의에 신청 불가** |
| **RISK** | ① PENDING 만료 회수 미구현 → 좌석 영구 점유 ② 락 순서 위반 시 데드락 ③ H2 락 거동이 실 DB 와 달라 위양성 |
| **SUCCESS** | 잔여 1석 100건 동시 신청 → 1건 성공·99건 거부, `enrollment_count == capacity`. 정합성 쿼리 0행. 엔드포인트 9개 문서화 |
| **SCOPE** | M0 스키마 → M1 도메인 → M2 포트·락 → M3 서비스 → M4 API·문서 → M5 통합 |

---

## Strategic Alignment Check

### Plan 대비 정렬

| 요소 | 기대 | 결과 |
|------|------|:----:|
| **핵심 문제 (WHY)** | 신청 자체가 불가능한 상태를 해소 | ✅ API 9개 개통, `enrollment_count` 가 실제 값을 갖는다 |
| **대상 (WHO)** | 수강생·크리에이터, 겸용 사용자 차단 | ✅ FR-19 3지점 전부 구현 |
| **핵심 가치** | 마지막 한 자리 동시 신청을 락과 제약으로 이중 보증 | ✅ 100건 동시 → 1건 (3회 반복 실측) |

### Success Criteria 상태

| # | 기준 (Plan §4) | 상태 | 증거 |
|---|---------------|:----:|------|
| SC-1 | FR-05~14 · 18~21 전부 구현 (FR-16 부분 충족) | ✅ | §2.1 FR 추적표 |
| SC-2 | ERD §8 시나리오 중 이번 범위 전부 테스트 | ✅ | **31/31.** #6 추가 완료 (`EnrollmentFlowIntegrationTest:350~`, 1.26초) |
| SC-3 | `./gradlew build` 통과 | ✅ | clean build **421건**, 실패 0 |
| SC-4 | `EnrollmentSchemaTest` 가 `waitlist` 제약 **동작** 확인 | ✅ | `EnrollmentSchemaTest.java:414~` 6건. `assertThatThrownBy` 가 `persist()`+`flush()` 를 함께 감쌈 |
| SC-5 | D-16 / D-21 부채 해소 | ✅ | `KlassService.java:161`(close 구현) · `Klass.java:305-317`(capacity "필요 없음") · `:234`(락 복원) |
| SC-6 | 정합성 검증 쿼리 0행 | ✅ | `EnrollmentFlowIntegrationTest.java:1029` |
| SC-7 | 동시 신청 100건이 **반복 실행에도** 안정적 | ✅ | 3회 연속 통과, 13.29·13.53·13.43초 (편차 0.24초) |
| SC-8 | 무인자 `now()` 0건 | ✅ | `src/` 전체 grep — 주석 6건뿐, 코드 0건 |
| SC-9 | 신규 도메인 주석에 "왜" + `Design Ref: §n` | ✅ | 신규 파일 전건 |
| SC-10 | 빈 순환 없음 (`@Lazy` 없이 기동) | ✅ | 생성자에 `@Lazy` 0건, `@SpringBootTest` 계열 전부 통과 |
| SC-11 | "2차에서 추가된다" javadoc 갱신 | ✅ | 해당 문구 grep **0건** |

**Success Rate: 11/11** — Act 단계에서 SC-2 해소

### Decision Record 검증

| 출처 | 결정 | 준수 | 편차 |
|------|------|:----:|------|
| [Plan] | Option C — 좌석 유스케이스 단일 서비스 | ✅ | `waitlist/` 에 서비스 파일 0개 |
| [Plan] | 비관적 락, `klass` 선행 고정 | ✅ | §4.4 락 순서표 7/7 일치 |
| [Design D-29] | `KlassService → port.in` 위임 1건만 | ✅ | 서비스 간 의존 1개, 단방향 |
| [Design D-37] | `CancellationPolicy` 값 객체 | ✅ | `CancellationPolicy.java` |
| [Design D-38] | 카운터 증감이 `updatedAt` 불변 | ✅ | `KlassTest.java:494` 가 검증 |
| [Design D-40] | 소유권을 상태보다 먼저 | ✅ | `EnrollmentService.java:431-434` |
| [Design D-41] | 프로퍼티를 `application` 계층에 | ✅ | `enrollment/application/EnrollmentProperties.java` |
| [Design §3.2.1] | `cancel()` 이 `isCancellableAt` 을 **호출**한다 | ✅ | 문서를 코드에 맞춰 정정 (D-42) |
| [Design §2.0/§2.2] | 패키지 의존 `enrollment → waitlist` **단방향** | ✅ | 문서를 실제 양방향으로 정정 (D-43) |

---

## 1. 분석 개요

### 1.1 목적

설계서가 요구한 것이 실제로 구현·검증됐는지 대조한다. **런타임 검증은 Gradle 테스트 스위트가
담당하므로** 이 분석은 "테스트가 통과했는가"가 아니라 **"설계가 요구한 것을 테스트하는가"** 를 본다.

### 1.2 범위

| 대상 | 경로 |
|------|------|
| 설계 정본 | `docs/02-design/features/enrollment-management.design.md` |
| 데이터 모델 정본 | `docs/02-design/features/class-enrollment-erd.design.md` §4 |
| 구현 | `src/main/java/com/toby/klass/{enrollment,waitlist,klass}/` |
| 산출물 | `build/resources/main/static/docs/openapi3.json` |

---

## 2. Gap Analysis

### 2.1 FR 추적 (Plan §3.1)

| FR | 요건 | 상태 | 증거 |
|----|------|:----:|------|
| FR-05 | `Enrollment` 상태 전이 메서드 | ✅ | `Enrollment.java:203`(confirm) · `:238`(cancel) |
| FR-06 | `PENDING→CONFIRMED→CANCELLED` 전이 규칙 | ✅ | 전이표 §3.3 대로. L1 26건 |
| FR-07 | 활성 중복 차단 (앱 + DB 이중) | ✅ | `EnrollmentService.java:146` + `uq_enrollment_active` |
| FR-08 | 정원 초과 거부 + 동시 신청 직렬화 | ✅ | `findWithLockById` + `Klass.occupySeat` · 100건 실측 |
| FR-09 | 카운터 정합성 보증 | ✅ | `ck_klass_count` + 정합성 쿼리 5종 |
| FR-10 | 내 신청 목록 | ✅ | `EnrollmentService.java:382` |
| FR-11 | 취소 가능 기간 (결제일 기준) | ✅ | `CancellationPolicy.isWithinPeriod` — 경계 포함 |
| FR-12 | 대기열 등록 (명시 요청) | ✅ | `EnrollmentService.java:298` |
| FR-13 | 강의별 수강생 목록 (권한+소유권) | ✅ | `:402` + `SecurityConfig:105` |
| FR-14 | 커서 페이지네이션 | ✅ | `EnrollmentQuery.fetchLimit` |
| FR-18 | 대기열 승격 (같은 트랜잭션·락) | ✅ | `promoteNextWaiting` — `private` |
| FR-19 | 개설자 본인 차단 **3지점** | ✅ | `:448`(신청·대기 공유) · `:280`(승격 적격성) |
| FR-20 | `ends_on` 경과 후 취소 차단 | ✅ | `CancellationPolicy.isKlassFinished` |
| FR-21 | 신청 상세 단건 | ✅ | `:371` |
| FR-16 | PENDING 만료 정책 | **부분** | 의도된 것 (D-32). `expires_at` 채움 + 결제 거부, 회수 없음 |

**FR 충족: 14/14 + 1 의도된 부분 충족**

### 2.2 API 계약 — 3-way 일치 9/9

설계 §6.1 ↔ 컨트롤러 ↔ `openapi3.json` 을 URL·메서드·상태코드·파라미터·응답필드·권한 6축으로 대조.

| # | 엔드포인트 | 설계 | 컨트롤러 | openapi3 | 계약 |
|:-:|-----------|:----:|:--------:|:--------:|:----:|
| 1 | `POST /v1/klasses/{klassId}/enrollments` | ✅ | `:93` | ✅ 201 | PASS |
| 2 | `GET /v1/klasses/{klassId}/enrollments` | ✅ | `:113` | ✅ 200 | PASS |
| 3 | `GET /v1/enrollments/me` | ✅ | `:139` | ✅ 200 | PASS |
| 4 | `GET /v1/enrollments/{id}` | ✅ | `:164` | ✅ 200 | PASS |
| 5 | `POST /v1/enrollments/{id}/confirm` | ✅ | `:184` | ✅ 200 | PASS |
| 6 | `POST /v1/enrollments/{id}/cancel` | ✅ | `:202` | ✅ 200 | PASS |
| 7 | `POST /v1/klasses/{klassId}/waitlists` | ✅ | `WaitlistController:66` | ✅ 201 | PASS |
| 8 | `GET /v1/waitlists/me` | ✅ | `:83` | ✅ 200 | PASS |
| 9 | `POST /v1/waitlists/{id}/cancel` | ✅ | `:106` | ✅ 200 | PASS |

**path 16 / operation 19** — 설계 §6.1 예측과 정확히 일치. 응답 DTO 필드 32종 전부 일치.

### 2.3 도메인 행위 (설계 §3.2) — 15/15

`Enrollment`(전이 2 + 판별 3) · `Waitlist`(전이 2 + 판별 2 + 불변식 1) ·
`Klass`(카운터 2 + 판별 1 + 정책 1) · `CancellationPolicy`(2) 전부 존재.

### 2.4 트랜잭션 (설계 §4.3) — 6종 36단계 전부 이관

| 유스케이스 | 락 순서 | 검사 순서 | 상태 |
|-----------|---------|-----------|:----:|
| ① 신청 | `klass` 단독 | 존재→OPEN→**개설자→중복**→정원 | ✅ |
| ② 결제 확정 | `enrollment` 단독 (§4.1 예외 1) | 존재→소유권→상태·만료 | ✅ |
| ③ 취소 | 무락 `klassId`→`klass`→`enrollment`→`waitlist` | 소유권→종료일→기간 | ✅ |
| ④ 승격 | 호출자 락 하위 (`private`) | OPEN→순번→적격성 3종→1건 | ✅ |
| ⑤ 대기 등록 | `klass` 단독 | OPEN→개설자→활성신청→중복대기→빈자리 | ✅ |
| ⑥ 대기 포기 | `waitlist` 단독 (§4.1 예외 2) | 존재→소유권→상태 | ✅ |
| ⑦ 마감 정리 | 호출자 락 하위, 전파 `REQUIRED` | — | ✅ |

**§4.4 락 순서표 7/7 일치.** 승격 순변화 0 이 `releaseSeat`↔`occupySeat` 상쇄로 성립.

### 2.5 Divergence 검증 — D-29~D-41 13/13 코드 일치

문서가 "이렇게 했다"고 적은 13건이 모두 코드와 맞는다. 상세는 gap-detector 대조표 참조.

### 2.6 테스트 계획 (설계 §9) 대비

| 레벨 | 설계 요구 | 실제 | 상태 |
|------|:---------:|:----:|:----:|
| L1 도메인 | ~16종 | 110건 (이번 사이클 61) | ✅ |
| L2 어댑터·서비스 | ~11종 | 173건 (이번 사이클 57) | ✅ |
| L3 컨트롤러+RestDocs | 9건 | 11건 (9 + 파라미터 검증 2) | ✅ |
| L4 통합 | 시나리오 31 + 신규 3 | 45건 — **31/31 + 3/3** | ✅ |
| L5 문서 산출물 | path/op 갱신 | 16/19 확인 | ✅ |
| 스키마 | 3종 추가 | 24건 (신규 6) | ✅ |

**정본 시나리오 31건 전부 커버** — Act 단계에서 #6 을 추가해 해소했다 (§8).

### 2.7 런타임 검증 결과

이 저장소의 런타임 검증은 Gradle 테스트 스위트다 (웹 UI 가 없어 Playwright 축은 N/A).

| 레벨 | 건수 | 통과 |
|------|-----:|:----:|
| L1 도메인 | 110 | 110 |
| L2 어댑터·서비스 | 173 | 173 |
| L3 컨트롤러+RestDocs | 35 | 35 |
| L4 통합 | 76 | 76 |
| L5 문서 산출물 | 3 | 3 |
| 스키마 | 24 | 24 |
| **합계** | **421** | **421 (100%)** |

**동시성 반복 안정성** — 100건 동시 신청 테스트 3회 연속 통과, 13.29 / 13.53 / 13.43초.
Plan R-03(H2 락 거동 차이로 위양성)이 실측으로 관측되지 않았다.

### 2.8 Match Rate

```
┌─────────────────────────────────────────────┐
│  Check 시점 (2026-09-03)                     │
│  ─────────────────────────────────────────── │
│  구조적 일치율:  98%   (87 항목 중 85)       │
│  기능적 깊이:    97%   (182 항목 중 179)     │
│  계약 일치율:    99%   (엔드포인트 54/54,    │
│                        에러 계약 15/16)      │
│  런타임:        100%   (408/408 통과)        │
│  Overall Match Rate:  98%                    │
├─────────────────────────────────────────────┤
│  Critical:   0건                             │
│  Important:  4건  → Act 에서 전건 해소        │
│  Minor:      7건  → 3건 해소, 4건 판정 유지   │
└─────────────────────────────────────────────┘
```

> **숫자보다 중요한 것**: Match Rate 는 게이트(90%)를 넘었지만 **Plan DoD 한 항목이 미충족**이다
> (SC-2). 98% 라는 값이 그것을 가리지 않도록 아래 G-1 을 최우선으로 둔다.

---

## 3. 발견 사항

### 3.1 Important (4건)

#### G-1 · 정본 시나리오 #6 테스트 부재 — **Plan DoD 미충족**

| | |
|---|---|
| **무엇이** | "취소 2건 동시 발생, 대기자 1명 → 승격 1건만" (ERD 정본 §8 #6). 설계 §9.5 가 ✅ 로, Plan §4.1 이 DoD 목록에 넣었다 |
| **현재** | `EnrollmentFlowIntegrationTest` 의 동시성 테스트는 `:277` 신청 100건 **하나뿐**. L2 `promotesOnlyOne`(`:618`)은 **단일 취소**라 경합이 아니다 |
| **왜 문제인가** | Plan §3.2 의 완화책("취소·승격을 뒤섞어 동시 실행")과 R-02(데드락)가 이 시나리오에 걸려 있다. **취소 경로의 락 순서가 동시 부하에서 검증된 적이 없다** — 신청 경로만 검증됐다 |
| **조치** | `:277` 의 `ExecutorService`+`CountDownLatch` 패턴 재사용. 좌석 2개를 채우고 두 취소를 동시 발사해 승격 1건·최종 카운터를 3중 단정 |

#### G-2 · `INVALID_ENROLLMENT_PAGE_SIZE` 의 도달 경로가 문서와 다르고, 그 경로가 미검증

| | |
|---|---|
| **무엇이** | `EnrollmentError.java:118` 정의, `EnrollmentQuery.java:38` throw. 그런데 컨트롤러가 `@Min`/`@Max` 를 먼저 걸어(`EnrollmentController:118,143` · `WaitlistController:87`) HTTP 로는 **`CommonError.VALIDATION_ERROR` 가 나간다** |
| **선례** | klass-management 가 같은 구조를 **의도적으로** 만들고 명시했다 — `KlassController:57-58` "중복이 아니라 두 방어선이다", `KlassQueryTest:24` "포트를 직접 호출하는 경로를 막는다". 즉 **패턴 자체는 옳다** |
| **왜 문제인가** | 두 가지다. ① 설계 §7.1 이 발생 지점을 "목록"으로 적어 **HTTP 로 나가는 것처럼 읽힌다** ② klass 쪽에는 그 둘째 방어선을 검증하는 `KlassQueryTest` 9건이 있는데 **`EnrollmentQueryTest` 대응물이 없다** — 유일한 도달 경로가 무검증이다 |
| **조치** | `EnrollmentQueryTest` 추가 + 설계 §7.1 에 "HTTP 는 `VALIDATION_ERROR`, 이 코드는 포트 직접 호출용 둘째 방어선" 명시 |

#### G-3 · `cancel()` 이 `isCancellableAt` 을 호출하지 않는다 — 설계 서술과 반대

| | |
|---|---|
| **무엇이** | 설계 §3.2.1 은 "`cancel()` 은 이 메서드를 호출하고, 거부 시 어느 관문에 걸렸는지 판별해 예외를 고른다"라고 적었다. §11.3 module-1 완료 조건도 "같은 판정을 쓴다" |
| **현재** | `Enrollment.java:238-253`(cancel)과 `:295-304`(isCancellableAt)가 조건 조합을 **두 번 구현**한다. `:287-289` 가 "재사용하지 않는다"는 근거를 적어 뒀다 — boolean 하나로는 어느 관문에 걸렸는지가 사라져 예외를 고를 수 없다 |
| **판정** | **코드가 옳고 문서가 틀렸다.** 기능 위험은 낮다 — `EnrollmentTest.java:386` `agreesWithCancel` 이 6케이스로 동치성을 검증한다 |
| **왜 문제인가** | D-39 의 근거가 "판정이 서버 안에서 두 번 구현되는 것을 막는다"였는데, 설계 문구를 그대로 읽으면 **다음 사람이 코드를 버그로 오해**한다 |
| **조치** | 설계 §3.2.1 문구를 코드에 맞춰 정정하고 **왜 재사용하지 않는지**를 divergence 로 등재 |

#### G-4 · 패키지 의존이 `enrollment ⇄ waitlist` 양방향 — 설계 §2.0 의 단방향 서술과 다르다

| | |
|---|---|
| **무엇이** | 역방향 참조 3곳 — `WaitlistQueryPort.java:4` · `WaitlistRepositoryAdapter.java:4` · `WaitlistResponse.java:3` 이 `enrollment.application.dto` 를 import (`EnrollmentQuery`, `WaitlistResult`) |
| **영향** | **기동은 정상.** 빈 생성자 순환이 아니라 패키지 레벨 양방향이다. 근거도 코드에 적혀 있다(`WaitlistQueryPort:17-19`, `WaitlistResult:10-13`) |
| **왜 문제인가** | 설계 §2.0 비교표의 "패키지 의존: `enrollment → waitlist` **단방향**"이 **Option C 선택 근거 중 하나**였다. 그 서술이 코드와 어긋난 상태로 남으면 나중에 "왜 A 안을 안 골랐지?" 를 추적할 때 근거가 흔들린다 |
| **조치** | 설계 §2.0 표를 정정하고 divergence 로 등재. 좌석 유스케이스가 한 서비스에 모인 결과 `EnrollmentQuery`/`WaitlistResult` 가 공유 타입이 됐다는 것이 실제 이유다 |

### 3.2 Minor (7건)

| # | 내용 | 판정 |
|:-:|------|------|
| M-1 | 설계 §2.1·§2.2 가 선언한 `EnrollmentService → KlassCommandPort` 의존이 없다 | **의도된 것.** 카운터는 더티 체킹으로 flush 된다. `KlassService:143` 의 선례("save 를 부르지 않는다")와 일관. 설계 표를 정정 |
| M-2 | `WaitlistResult` 가 설계 §10.1 의 `waitlist/application/dto/` 가 아니라 `enrollment/application/dto/` 에 있다 | 근거가 클래스 javadoc 에 있다. 설계 정정 |
| M-3 | §10.1 목록에 없는 신규 파일 4종 (`WaitlistQueryDslRepository` · `KlassEnrollmentResult` · `RegisterWaitlistCommand` · `GiveUpWaitlistCommand`) | 누락이 아니라 설계 목록의 불완전. 설계 정정 |
| M-4 | 메서드명이 §2.1 다이어그램과 다름 7건 (`cancelRemainingWaitlist`→`cancelRemaining` 등) | 다이어그램이 개념 표기였다. 설계 정정 |
| M-5 | `@Query` 2곳 (`EnrollmentJpaRepository:59` · `WaitlistJpaRepository:66`) | **규약 위반 아님.** 둘 다 프로젝션·집계라 파생 쿼리로 표현 불가. 설계 §4.3⑦ 이 금지한 것은 **벌크 UPDATE** 뿐 |
| M-6 | §4.3⑥ 3번 상태 검사가 서비스가 아니라 도메인(`Waitlist:209-217`)에 있다 | **의도된 것.** 승격·포기가 검사를 공유해야 경합이 막힌다. 에러·상태코드 동일 |
| M-7 | 시나리오 #5 가 대기자 2명으로 작성(설계는 3명), 2순위 `WAITING` 잔존 미단정 | 보강 여지. 승격 1건만 일어나는 것은 이미 단정됨 |

---

## 4. Clean Architecture 준수

| 위치 | 규칙 | 결과 |
|------|------|:----:|
| `domain` | Spring 타입 금지 | ✅ 신규 도메인 파일에 Spring import 0건 |
| `domain` | `Clock` 미의존, 시각을 파라미터로 | ✅ `cancel(now, today, policy)` |
| `application.service` | `adapter.*` 참조 금지 | ✅ 포트만 참조 |
| `application.service` | `infrastructure` 역행 금지 | ✅ `EnrollmentProperties` 를 `application` 에 (D-41) |
| `adapter.out` | `klass` 락을 잡지 않는다 | ✅ 락은 `KlassQueryPort` 소관 |
| `adapter.in` | 엔티티 직접 노출 금지 | ✅ Response DTO 경유 |
| 서비스 간 의존 | 단방향 1건 | ✅ `KlassService → CancelRemainingWaitlistUseCase` |
| 패키지 간 의존 | 단방향 (설계 서술) | **⚠️ G-4** |

**Architecture Score: 96%** (8항목 중 7 완전 준수)

---

## 5. 컨벤션 준수

| 항목 | 결과 |
|------|:----:|
| 무인자 `now()` / `LocalDate.now()` 금지 | ✅ 코드 0건 |
| boolean `is` 접두어 (전 계층) | ✅ `isCancellable` 이 DB↔도메인↔API 일관 |
| ENUM `@Enumerated(STRING)`, ordinal 금지 | ✅ 스키마 테스트가 문자열 저장 확인 |
| public setter 없음 | ✅ 0건 |
| 주석 한국어 + `Design Ref: §n` | ✅ 신규 파일 전건 |
| 커밋 규약 (한국어 제목, 본문에 왜) | ✅ 6개 커밋 |

**Convention Score: 100%**

---

## 6. 권고 조치

### 6.1 즉시 (Plan DoD 미충족)

| 우선 | 항목 | 위치 |
|:----:|------|------|
| 🔴 1 | **시나리오 #6 동시 취소 테스트 추가** (G-1) | `EnrollmentFlowIntegrationTest` |
| 🔴 2 | **`EnrollmentQueryTest` 추가** — 둘째 방어선 검증 (G-2) | `enrollment/application/dto/` |

### 6.2 단기 (문서 정정 — 코드가 정답)

| 우선 | 항목 | divergence |
|:----:|------|:----------:|
| 🟡 3 | `cancel()`↔`isCancellableAt` 서술 정정 (G-3) | D-42 |
| 🟡 4 | 패키지 의존 단방향 서술 정정 (G-4) | D-43 |
| 🟡 5 | `KlassCommandPort` 의존 표기 제거 (M-1) | D-44 |
| 🟡 6 | §7.1 에 두 방어선 명시 (G-2 문서분) | D-45 |
| 🟢 7 | §10.1 파일 목록·§2.1 메서드명 실제 배치로 갱신 (M-2·M-3·M-4) | — |

### 6.3 다음 사이클

| 항목 | 근거 |
|------|------|
| **PENDING 만료 회수** | R-01 이 High 로 남아 있다. 관측 쿼리(`:1084`)만 있고 회수가 없어 미결제 신청이 좌석을 영구 점유한다. 완료 보고서 최우선 등재 |
| 시나리오 #5 보강 (M-7) | 대기자 3명 + 2순위 `WAITING` 잔존 단정 |

---

## 7. 설계 문서 갱신 필요 목록

코드가 정답인 항목들이다. 문서를 코드에 맞춘다.

- [ ] §3.2.1 — `cancel()` 이 `isCancellableAt` 을 호출한다는 서술 → 재사용하지 않는 근거로 교체 (D-42)
- [ ] §2.0 비교표 · §2.2 — 패키지 의존 단방향 서술 정정 (D-43)
- [ ] §2.1 컴포넌트 도면 · §2.2 — `KlassCommandPort` 제거, 메서드명 실제와 일치 (D-44, M-4)
- [ ] §7.1 — `INVALID_ENROLLMENT_PAGE_SIZE` 의 도달 경로를 두 방어선으로 명시 (D-45)
- [ ] §10.1 — 신규 파일 4종 추가, `WaitlistResult` 위치 정정 (M-2, M-3)
- [ ] §11.3 module-1 완료 조건 — "같은 판정을 쓴다" → "같은 답을 낸다" (G-3 파생)

---

## 8. Act 단계 조치 결과

Check 직후 **Important 4건 · Minor 3건을 전건 해소**했다.

| ID | 조치 | 결과 |
|:--:|------|------|
| **G-1** | 시나리오 #6 동시 취소 테스트 추가 | `EnrollmentFlowIntegrationTest:350~`. 취소 2건 동시 발사 → 둘 다 성공, **승격은 1건**, 좌석 2→1. 3중 단정 + 30초 데드락 감지. 1.26초 통과 |
| **G-2** | `EnrollmentQueryTest` 추가 (12건) | `INVALID_ENROLLMENT_PAGE_SIZE` 의 **유일한 도달 경로**를 검증. `KlassQueryTest` 와 대칭 |
| **G-2** | 설계 §7.1 정정 | 발생 지점을 "HTTP 로는 나가지 않는다 — 포트 직접 호출용 둘째 방어선"으로 (D-45) |
| **G-3** | 설계 §3.2.1 · §11.3 정정 | "`cancel()` 이 호출한다" → "호출하지 않고 각자 구현하되 동치성을 테스트가 못박는다" (D-42) |
| **G-4** | 설계 §2.0 정정 | 패키지 의존 서술 정정 + Option C 의 진짜 근거 명시 (D-43) |
| **G-4′** | **`WaitlistQuery` 신설 (D-46)** | 문서 정정만으로 끝낸 것이 얕은 판단이었다. `waitlist` 가 쓰지도 않는 `status` 때문에 `enrollment` 를 경유하던 것을 실제로 끊었다 — **역방향 참조 4곳 → 2곳.** 남은 2곳은 D-29 의 귀결 |
| **M-1** | 설계 §2.2 정정 | `KlassCommandPort` 제거 — 카운터는 변경 감지가 flush 한다 (D-44) |
| **M-2·M-3** | 설계 §10.1 정정 | `WaitlistResult` 위치 근거 + 누락 파일 4종 추가 |
| **M-4** | 설계 §2.1 도면 정정 | 메서드명 7건을 실제와 일치 |

**해소 후 테스트 408 → 431건, 실패 0.**

남은 Minor 3건(M-5 `@Query` 2곳 · M-6 상태 검사 위치 · M-7 시나리오 #5 보강)은
**규약 위반이 아니거나 근거가 코드에 적혀 있어** 조치하지 않는다. M-7 만 다음 사이클
과제로 넘긴다.

### 갱신된 Match Rate

```
구조적 100%  ·  기능적 99%  ·  계약 100%  ·  런타임 100% (421/421)
Overall 99.75% → 100%
```

> 문서 정정으로 구조·계약 편차가 사라졌고, 기능적 깊이의 유일한 감점(시나리오 #6)도
> 해소됐다. 남은 1%는 M-7(시나리오 #5 의 대기자 수)이다.

---

## 9. 다음 단계

- [x] G-1 · G-2 수정
- [x] 설계 문서 정정 + D-42~D-45 등재
- [ ] 완료 보고서 작성 (`/pdca report`)

---

## Version History

| 버전 | 날짜 | 변경 | 작성자 |
|------|------|------|--------|
| 0.1 | 2026-09-03 | 최초 분석. Match Rate 98%, Critical 0 / Important 4 / Minor 7. Success Criteria 10/11 (SC-2 부분) | Chals85 |
| 0.2 | 2026-09-04 | Act 조치 반영. Important 4 + Minor 3 해소 → **Match Rate 100%, SC 11/11**. 테스트 408→421 | Chals85 |
