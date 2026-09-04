# PENDING 만료 회수 스케줄러 계획서

> **Summary**: 결제 기한이 지난 `PENDING` 수강신청을 10분 주기 스케줄러가 취소 처리하고 좌석을 반납한다. 취소 원인을 `cancel_reason` 으로 구분해 저장하며, 모집 중인 강의라면 대기자 승격까지 같은 트랜잭션에서 끝낸다.
>
> **Project**: klass
> **Version**: 0.9.0
> **Author**: developer2@lulumedic.com
> **Date**: 2026-09-04
> **Status**: Draft

---

## Executive Summary

| 관점 | 내용 |
|------|------|
| **Problem** | `PENDING` 은 결제 전인데 좌석을 점유한다(홀드 패턴). `expires_at` 은 채워지지만 **아무도 읽어서 회수하지 않아**, 결제하지 않은 신청이 좌석을 영구히 붙잡는다. 정원 10 강의에 10명이 신청하고 아무도 결제하지 않으면 **영구 만석**이 되고, 신규 신청·대기 등록·승격이 모두 막힌다. 홀드 패턴에서 TTL 회수는 선택이 아니라 필수 짝인데 그 짝이 비어 있다 (D-32, 현재 최대 잔여 위험 R-01). |
| **Solution** | Spring 기본 `@Scheduled` 로 10분마다 만료 후보를 훑고, **건별 독립 트랜잭션**으로 `klass` 락 → 상태 재확인 → 취소 → 좌석 반납 → 승격을 수행한다. 회수 로직 자체는 `EnrollmentService.cancel` 경로에 이미 있으므로 **트리거와 진입 경로만 추가**한다. 취소 원인은 `cancel_reason` ENUM(`USER` / `EXPIRED`)으로 구분 저장해 ERD §2 ⑦ 의 열린 미결을 함께 닫는다. |
| **Function/UX효과** | 결제하지 않은 신청이 최대 10분 뒤 자동 정리되어 좌석이 회수된다. 모집 중인 강의라면 그 좌석이 **대기 1순위에게 즉시 이전**된다. 개설자 명단에서 미결제자가 걷히고 `enrollment_count` 가 실제 수강생 수와 일치한다. 사용자는 응답의 `cancelReason` 으로 "내가 취소한 것"과 "기한이 지나 취소된 것"을 구분할 수 있다. |
| **Core Value** | **"좌석은 결제 기한을 넘겨 붙잡히지 않는다"** 는 불변식을 시스템이 스스로 보증한다. 지금까지 유일한 회수 경로였던 "사용자의 자발적 취소"에 의존하지 않는다. |

---

## Context Anchor

| Key | Value |
|-----|-------|
| **WHY** | 결제하지 않은 `PENDING` 이 좌석을 영구 점유해 강의가 영구 만석이 되는 R-01 을 해소한다 |
| **WHO** | 수강생(대기자) — 회수된 좌석으로 승격 / 개설자 — 정확한 수강생 명단과 정원 활용 / 운영자 — 만료율 관측 |
| **RISK** | 배치가 `klass` 락 순서를 어기거나 락 밖에서 승격해 신규 신청자와 좌석을 두고 경합하는 것 |
| **SUCCESS** | 만료 후 최대 10분 내 `enrollment_count` 감소 · 대기 1순위 승격 · 만료 `PENDING` 관측 쿼리(R-01)가 0 으로 수렴 |
| **SCOPE** | module-0 스키마(`cancel_reason`) → module-1 도메인 전이 → module-2 포트·어댑터 조회 → module-3 회수 유스케이스 → module-4 스케줄러 → module-5 응답 노출·문서 |

---

## 1. Overview

### 1.1 Purpose

`expires_at` 이 지난 `PENDING` 수강신청을 주기적으로 회수해 좌석을 반납하고, 모집 중인 강의라면 대기자를 승격한다. 취소 원인을 데이터에 남겨 사용자 취소와 만료를 구분한다.

### 1.2 Background

**이 기능은 원래 있어야 했다.** `enrollment-management` 사이클이 D-32 로 "외부 배치 서버 전제"라며 의도적으로 비워둔 자리이며, 그 대가가 완료 보고서에 R-01(최대 잔여 위험)로 기록돼 있다.

현재 상태를 정확히 하면 — **재료는 전부 갖춰져 있고 트리거만 없다.**

| 재료 | 현재 상태 |
|------|-----------|
| `expires_at` | ✅ 출처별로 정확히 채워짐 (`DIRECT` 30분 / `WAITLIST` 10분) |
| `idx_enrollment_expiry` | ✅ `expires_at` 단일 인덱스가 이미 존재 |
| 회수 로직 | ✅ `EnrollmentService.cancel` 에 취소 → `releaseSeat()` → `promoteNextWaiting()` 이 있음 |
| 만료 방어 | ⚠️ `Enrollment.confirm` 의 기한 검사가 **유일한 방어선** — 만료된 것을 확정하지 못하게만 하고 좌석은 못 되찾음 |
| 트리거 | ❌ **없음** |
| 스케줄 인프라 | ✅ `SchedulingConfig`(`@EnableScheduling`) + `RevokedAccessTokenCleaner` 선례 |

`@Scheduled` 인프라가 이미 auth 도메인에서 돌고 있으므로 **새로 도입할 기술이 없다.** 같은 패턴을 enrollment 도메인에 한 번 더 적용하는 작업이다.

`cancel_reason` 은 ERD 정본 §2 ⑦ 이 "만료율 측정이나 환불 정책 분기가 필요하면 감사 테이블보다 싸다"며 **만료 회수가 생기는 시점을 도입 조건으로 명시**해 둔 항목이다. 지금이 그 시점이다.

### 1.3 Related Documents

- 데이터 모델 정본: `docs/02-design/features/class-enrollment-erd.design.md` — §2 ⑥(만료 기한) · §2 ⑦(`cancel_reason` 미결) · §4.1(락 순서) · §4.4(취소+승격)
- 선행 사이클: `docs/archive/2026-09/enrollment-management/` — D-32(만료 회수 제외 근거) · 완료 보고서 §7.2 · R-01
- 스케줄러 선례: `src/main/java/com/toby/klass/auth/application/service/RevokedAccessTokenCleaner.java`
- 관측 쿼리: `EnrollmentFlowIntegrationTest` 정합성 절 (R-01 완화책)
- 다음 산출물: `docs/02-design/features/pending-expiry-reaper.design.md`

---

## 2. Scope

### 2.1 In Scope

**스키마**
- [ ] `enrollment.cancel_reason` ENUM 컬럼 추가 (`USER` / `EXPIRED`)
- [ ] `ck_enrollment_cancelled` 확장 — `CANCELLED` 이면 `cancelled_at` 과 `cancel_reason` 이 모두 있어야 한다
- [ ] `EnrollmentSchemaTest` 갱신 (컬럼 · CHECK · ENUM 저장 형식)

**도메인**
- [ ] `CancelReason` enum 신설 (`enrollment/domain/`)
- [ ] `Enrollment` 상태 전이에 취소 원인 반영 — 기존 `cancel(...)` 호출부 영향 포함
- [ ] 만료 판정을 도메인이 소유 (`isExpiredAt(now)`)

**애플리케이션**
- [ ] 만료 후보 조회 포트 메서드 (`EnrollmentQueryPort`)
- [ ] 만료 회수 유스케이스 — 건별 트랜잭션, `klass` 락 하위, 락 획득 후 상태 재확인
- [ ] 승격은 기존 `promoteNextWaiting` 직접 호출 (D-47)

**스케줄러**
- [ ] `@Scheduled` 진입점 — 10분 주기, `fixedDelay`, 설정으로 주기 조정 가능
- [ ] 건별 예외 격리 — 한 건 실패가 사이클 전체를 멈추지 않는다
- [ ] 회수 결과 로깅

**API·문서**
- [ ] 응답 DTO 에 `cancelReason` 노출 (`EnrollmentResponse` · `KlassEnrollmentResponse`)
- [ ] 해당 L3 RestDocs 테스트 필드 추가

**테스트**
- [ ] L1 도메인 — 취소 원인 · 만료 판정 경계
- [ ] L2 서비스 — 회수 · 승격 · 재확인 · 예외 격리
- [ ] L4 통합 — 만료 → 회수 → 승격 전 구간, `CLOSED` 강의 회수, 정합성 재검증

### 2.2 Out of Scope

| 제외 항목 | 근거 |
|-----------|------|
| 관리자 수동 회수 엔드포인트 | 사용자 결정. 엔드포인트가 없으면 `SecurityConfig` 매처 · ROLE 설계 · RestDocs 에러 케이스가 함께 따라오지 않아 이번 사이클 범위가 명확해진다 |
| 승격 알림 | ERD §4.8 미구현. **다만 이 사이클이 그 부재를 실질적 문제로 만든다** — 배치가 승격 연쇄를 자동으로 돌리는데 승격자가 그 사실을 알 수 없다(R-9). 범위 유지는 사용자 결정이며, 이벤트 도입의 적기이므로 §7.2 D-47 에 되살릴 조건을 함께 기록한다 |
| `Enrollment` 승격을 Spring 이벤트로 발행 | D-47. 요건 3 에서 이탈하며 근거는 §7.2 에 상세히 기록한다 |
| 분산 락(ShedLock 등) | 선례(`RevokedAccessTokenCleaner`)와 동일하게 **단일 인스턴스 전제**. 다중화 시 필요하며 §5 리스크에 기록한다 |
| `CLOSED → OPEN` 재모집 · 개강 시점 승격 | 별개 사안. D-18 이 봉쇄한 역전이를 여는 설계가 선행돼야 한다 |
| 외부 결제 연동 | ERD §1.3. `confirm` 은 여전히 결제 성공을 가정한다 |

---

## 3. Requirements

### 3.1 Functional Requirements

| ID | 요구사항 | 우선순위 | Status |
|----|----------|----------|--------|
| FR-01 | 스케줄러가 **10분 주기**로 실행된다. 이전 실행이 끝난 뒤부터 간격을 재어(`fixedDelay`) 작업이 겹쳐 쌓이지 않는다 | High | Pending |
| FR-02 | `status = 'PENDING'` 이고 `expires_at <= now` 인 신청을 **강의 상태와 무관하게 전량** 회수 대상으로 삼는다 | High | Pending |
| FR-03 | 회수 대상을 `CANCELLED` 로 전이하고 `cancelled_at` 을 기록하며, `cancel_reason = EXPIRED` 로 원인을 남긴다 | High | Pending |
| FR-04 | 사용자의 자발적 취소는 `cancel_reason = USER` 로 저장한다. 두 원인이 데이터에서 구분된다 | High | Pending |
| FR-05 | 회수 시 `klass.enrollment_count` 를 1 감소시킨다(`releaseSeat()`). 이 갱신은 `klass` 배타 락 하위에서 일어난다 | High | Pending |
| FR-06 | 강의가 `OPEN` 이면 회수 직후 **같은 트랜잭션·같은 락 안에서** 대기 1순위를 승격한다. `OPEN` 이 아니면 좌석은 빈 채로 남는다 | High | Pending |
| FR-07 | 회수는 **건별 독립 트랜잭션**으로 처리한다. 한 건의 실패가 같은 사이클의 다른 건을 롤백하지 않는다 | High | Pending |
| FR-08 | 락 획득 후 대상의 상태와 만료 여부를 **재확인**한다. 후보 조회 시점과 락 획득 시점 사이에 사용자가 결제·취소했을 수 있다 | High | Pending |
| FR-09 | 회수 결과(건수)를 로그로 남긴다. 0건이면 로그를 남기지 않는다 (선례와 동일) | Medium | Pending |
| FR-10 | 스케줄 주기를 설정으로 조정할 수 있다 (`app.enrollment.reap-interval`, 기본 `PT10M`) | Medium | Pending |
| FR-11 | 취소 원인을 API 응답에 노출한다 — 사용자가 "왜 취소됐는지" 알 수 있어야 한다 | Medium | Pending |

#### 승격 연쇄의 타이밍

배치가 붙으면 승격이 **연쇄**한다. 지금까지는 승격이 사용자 취소 시에만 일어나 드물었다.

```
대기 1순위 승격 -> PENDING(10분) -> 미결제 -> 만료
    -> 배치 회수 -> 좌석 반납 -> 대기 2순위 승격 -> PENDING(10분) -> ...
```

`pendingExpiry.waitlist`(`PT10M`)와 `reap-interval`(`PT10M`)이 같은 값이므로 **대기자 1명당
평균 15분 · 최대 20분**이 걸린다(만료 10분 + 배치 발견 0~10분). 대기자 3명이 연달아 결제하지
않으면 마지막 순번까지 최대 1시간이다.

이 연쇄 자체는 의도된 동작이다. **문제는 승격자가 승격 사실을 모른다는 것**이며 R-9 로 기록한다.

### 3.2 Non-Functional Requirements

| 범주 | 기준 | 측정 방법 |
|------|------|-----------|
| **정합성** | 회수 후에도 `klass.enrollment_count` = `PENDING`+`CONFIRMED` 행 수 | L4 통합 테스트의 기존 정합성 쿼리 재사용 |
| **동시성** | 배치와 사용자 신청이 동시에 실행돼도 정원 초과·중복 승격이 없다 | L4 동시성 테스트 — 만료 회수와 신규 신청을 동시 실행 |
| **락 순서** | 배치도 `klass` → `enrollment` 순서를 지킨다 (ERD §4.1) | 코드 리뷰 + L2 테스트에서 락 조회 호출 순서 검증 |
| **성능** | 후보 조회가 인덱스를 탄다. 사이클당 `klass` 락 보유 시간은 1건 처리 시간으로 제한된다 | `idx_enrollment_expiry` 활용 — `ck_enrollment_pending` 이 "PENDING 이 아니면 `expires_at IS NULL`" 을 강제하므로 **`expires_at IS NOT NULL` 인 행은 전부 `PENDING`** 이다. 단일 인덱스만으로 후보를 정확히 걸러낸다 |
| **테스트 격리** | 스케줄러가 테스트 실행 중에 끼어들지 않는다 | `initialDelay` 를 주기와 동일하게(`PT10M`) 설정 — 선례와 동일 |
| **문서** | 추가·변경된 응답 필드가 OpenAPI 산출물에 반영된다 | `./gradlew build` — `DocumentationIntegrationTest` 통과 |

---

## 4. Success Criteria

### 4.1 Definition of Done

- [ ] FR-01 ~ FR-11 전건 구현
- [ ] **정원 10 강의에 10명이 신청하고 아무도 결제하지 않아도, 만료 후 최대 10분 내에 다시 신청 가능해진다** (R-01 해소)
- [ ] 만료된 `PENDING` 을 세는 관측 쿼리 값이 회수 사이클 후 **0 으로 수렴**한다
- [ ] 대기자가 있는 `OPEN` 강의에서 만료 회수 → 대기 1순위가 `PENDING(source=WAITLIST)` 으로 승격되고 `enrollment_count` 순변화가 0
- [ ] `CLOSED` 강의의 만료 `PENDING` 도 회수되며, 승격은 일어나지 않고 좌석이 빈 채로 남는다
- [ ] `cancel_reason` 으로 사용자 취소와 만료 취소가 구분 조회된다
- [ ] `./gradlew build` 통과 (test + documentationTest)

### 4.2 Quality Criteria

- [ ] L1~L4 테스트 추가, 기존 테스트 무회귀
- [ ] `EnrollmentSchemaTest` 6종 검증에 `cancel_reason` 반영 (컬럼 · CHECK · ENUM 저장 형식)
- [ ] 락 규약 위반 없음 — `klass` 를 첫 락으로 잡고, 승격이 락 안에서 실행됨
- [ ] 주요 결정에 `Design Ref: §n` 주석 부착, divergence 는 커밋 메시지에 ID 명시

---

## 5. Risks and Mitigation

| # | 리스크 | 영향 | 발생 가능성 | 완화 |
|---|--------|------|-------------|------|
| R-1 | **배치가 락 밖에서 승격**해 신규 신청자와 좌석 경합 | High | Medium | 회수 유스케이스가 `klass` 락을 첫 번째로 잡고 기존 `private promoteNextWaiting` 을 같은 트랜잭션에서 호출. 별 빈 분리·`REQUIRES_NEW` 금지 |
| R-2 | 후보 조회와 락 획득 사이에 사용자가 **결제 완료** → 확정된 신청을 배치가 취소 | High | Medium | FR-08. 락 획득 후 `PENDING` 여부와 `expires_at` 을 재확인하고, 어긋나면 조용히 건너뛴다 |
| R-3 | `cancel_reason` 추가로 **기존 `cancel(...)` 호출부가 깨짐** | Medium | High | §6 영향 분석에서 호출부를 전수 나열. 컴파일러가 잡는 변경이라 런타임 함정은 아니다 |
| R-4 | `ck_enrollment_cancelled` 확장이 **기존 데이터와 충돌** | Medium | Low | H2 인메모리라 기존 데이터 없음. 실 DB 전환 시 백필이 필요하며 그 사실을 Design 에 기록 |
| R-5 | **다중 인스턴스**에서 같은 대상을 동시에 회수 | Medium | Low | `klass` 행 락이 직렬화하고 FR-08 재확인이 중복 처리를 막는다. 다만 불필요한 경합이 생기므로 **단일 인스턴스 전제**를 javadoc 에 명시 (선례와 동일) |
| R-6 | 통합 테스트 중 스케줄러가 끼어들어 **위양성** | Medium | Medium | `initialDelay` = 주기(`PT10M`). 테스트는 회수 메서드를 직접 호출해 검증한다 |
| R-7 | 만료 건이 많을 때 사이클이 길어져 다음 실행과 겹침 | Low | Low | `fixedDelay` 사용 — 이전 실행 종료 후부터 간격을 잰다. 건별 트랜잭션이라 락 보유 시간도 짧다 |
| R-9 | **승격 연쇄는 도는데 승격자가 그 사실을 모른다.** 승격 알림 미구현(ERD §4.8). 승격으로 생긴 `PENDING` 은 10분 만료이고 배치가 그것을 회수해 다음 순번을 승격한다 — 대기자 전원이 승격 사실을 모른 채 순차 만료되면 **대기열은 소진되고 좌석은 빈 채로 남아 아무도 수강하지 못한다** | High | High | **이번 사이클 범위 밖**(사용자 결정). 배치가 없을 때는 승격이 드물어 드러나지 않던 공백이며, 배치 도입이 이 문제를 전면으로 끌어올린다. 관측 쿼리로 `source = 'WAITLIST'` 인 만료 `PENDING` 수를 세어 실제 발생 규모를 확인하고, 완료 보고서에 잔여 위험으로 남긴다. 해소 시점은 승격 알림 도입 시 — §7.2 D-47 의 "이벤트를 되살릴 조건"과 정확히 같은 시점이다 |
| R-8 | `cancel_reason` 노출로 **RestDocs 스니펫 누락** | Low | Medium | 필드 추가는 `fieldWithPath` 누락 시 문서 생성에서 실패한다(CLAUDE.md "컴파일러가 잡지 못하는 지점" 4번). 응답 DTO 를 건드리는 L3 테스트를 전수 확인 |

---

## 6. Impact Analysis

### 6.1 Changed Resources

| 리소스 | 유형 | 변경 내용 |
|--------|------|-----------|
| `enrollment` 테이블 | DB Schema | `cancel_reason` 컬럼 추가, `ck_enrollment_cancelled` 확장 |
| `Enrollment` | Domain Entity | 필드 추가, `cancel(...)` 시그니처에 취소 원인 반영, 만료 판정 메서드 |
| `CancelReason` | Domain ENUM | **신규** |
| `EnrollmentQueryPort` | Out Port | 만료 후보 조회 메서드 추가 |
| `EnrollmentService` | Service | 회수 유스케이스 추가, 기존 `cancel` 이 `USER` 원인을 넘기도록 수정 |
| `EnrollmentResult` · `KlassEnrollmentResult` | Application DTO | `cancelReason` 필드 추가 |
| `EnrollmentResponse` · `KlassEnrollmentResponse` | Web DTO | `cancelReason` 필드 추가 |
| `SchedulingConfig` | Config | javadoc 갱신 (등록 작업이 둘이 됨) |
| `application.yml` | Config | `app.enrollment.reap-interval` 추가 |
| `EnrollmentProperties` | Config Properties | 주기 설정 필드 추가 |

### 6.2 Current Consumers

| 리소스 | 동작 | 코드 경로 | 영향 |
|--------|------|-----------|------|
| `Enrollment.cancel(now, today, policy)` | UPDATE | `EnrollmentService.cancel` (유일한 호출부) | **Breaking** — 취소 원인 인자 추가. 컴파일러가 잡는다 |
| `Enrollment.cancel` | TEST | `EnrollmentTest`(L1) · `EnrollmentServiceTest`(L2) · `EnrollmentFlowIntegrationTest`(L4) | Needs verification — 시그니처 변경에 따른 갱신 |
| `ck_enrollment_cancelled` | SCHEMA | `EnrollmentSchemaTest` CHECK 검증 | **Breaking** — 제약 문자열 변경. 스키마 테스트 갱신 필수 |
| `enrollment` ENUM 저장 형식 | SCHEMA | `EnrollmentSchemaTest` ENUM 검증 | Needs verification — `cancel_reason` 이 `VARCHAR` 로 저장되는지 확인 |
| `EnrollmentResponse` | READ | `EnrollmentController.apply` · `confirm` · `cancel` · `findById` | Needs verification — 필드 추가. 각 L3 RestDocs 테스트에 `fieldWithPath` 추가 |
| `KlassEnrollmentResponse` | READ | `EnrollmentController.listByKlass` | Needs verification — 동일 |
| `EnrollmentSummaryResult` | READ | `EnrollmentController.listMine` | 확인 필요 — 요약 DTO 에도 노출할지 Design 에서 확정 |
| `promoteNextWaiting` | UPDATE | `EnrollmentService.cancel` (기존) → 회수 경로 추가 | None — `private` 유지, 호출부만 하나 늘어난다 |
| `klass.enrollment_count` | UPDATE | `apply` · `cancel` · 승격 → 회수 경로 추가 | None — 기존 `releaseSeat()` 를 그대로 쓴다 |
| `idx_enrollment_expiry` | READ | 지금까지 **사용처 없음** | None — 이 사이클에서 처음 실제로 쓰인다 |
| `SchedulingConfig` | CONFIG | `RevokedAccessTokenCleaner` | None — 작업이 하나 늘어날 뿐 |
| 관측 쿼리 (R-01) | READ | `EnrollmentFlowIntegrationTest` 정합성 절 | Needs verification — 회수가 생기면 이 값이 0 으로 수렴해야 한다. 단언을 강화할 기회 |

### 6.3 Verification

- [ ] 위 소비처 전건이 변경 후에도 동작함을 확인
- [ ] `cancel_reason` 추가가 기존 조회 쿼리(QueryDSL 프로젝션 포함)를 깨지 않음
- [ ] 응답 필드 추가가 모든 RestDocs 스니펫에 반영됨 — 누락 시 문서 생성에서 실패
- [ ] `EnrollmentSchemaTest` 6종 전건 통과
- [ ] 이름 변경이 아니므로 CLAUDE.md "컴파일러가 잡지 못하는 지점" 4종 중 JPQL·파생 쿼리는 해당 없음. **리플렉션 문자열과 RestDocs 경로**는 해당된다

---

## 7. Architecture Considerations

### 7.1 Project Level

| Level | Selected |
|-------|:--------:|
| Starter | ☐ |
| Dynamic | ☐ |
| **Enterprise** (헥사고날 · 계층 분리 · DI) | ☑ |

기존 프로젝트 아키텍처(헥사고날 Ports & Adapters, 도메인별 수직 분할)를 그대로 따른다. **새 도메인 패키지를 만들지 않는다** — 회수는 `enrollment` 도메인의 유스케이스다.

### 7.2 Key Architectural Decisions

| Decision | Options | Selected | Rationale |
|----------|---------|----------|-----------|
| 스케줄 기술 | Spring `@Scheduled` / Quartz / 외부 배치 | **Spring `@Scheduled`** | `SchedulingConfig` + `RevokedAccessTokenCleaner` 선례가 이미 있다. 새 기술 도입 없음 |
| 실행 간격 방식 | `fixedRate` / `fixedDelay` / `cron` | **`fixedDelay`** | 이전 실행 종료 후부터 간격을 재어 작업이 겹쳐 쌓이지 않는다 (선례와 동일) |
| 트랜잭션 경계 | 사이클 전체 / 강의별 / **건별** | **건별** | 한 건 실패가 전체를 롤백하지 않고 `klass` 락 보유 시간이 짧다. 진입점(무트랜잭션)과 처리 메서드(별 빈, `@Transactional`)를 나눠 프록시를 태운다 |
| 회수 범위 | 전량 / `OPEN` 강의 한정 | **전량** | 마감 강의에서도 좌석 회복은 못 하지만 **명단·통계 정확성**을 얻는다. 마감 강의에서 사용자 직접 취소가 이미 허용되므로 배치만 막으면 규칙이 갈라진다 |
| 취소 원인 저장 | `cancel_reason` ENUM / 감사 테이블 / 미저장 | **`cancel_reason` ENUM** | ERD §2 ⑦ 이 "감사 테이블보다 싸다"며 도입 조건을 만료 회수로 명시해 두었다 |
| **승격 트리거** | **직접 호출** / 동기 `@EventListener` / `AFTER_COMMIT` | **직접 호출 (D-47)** | 아래 참조 |
| 관리자 수동 트리거 | 있음 / 없음 | **없음** | 엔드포인트가 없으면 ROLE 설계·`SecurityConfig` 매처·RestDocs 에러 케이스가 따라오지 않아 범위가 명확해진다 |

#### D-47 — 승격을 Spring 이벤트로 발행하지 않는다

**요건 3 에서 의도적으로 이탈한다.** 원 요건은 "승격 처리를 Spring 이벤트로 해서 나중에 이벤트 기반 아키텍처 전환 시 유연하게"였다.

**이탈 근거 세 가지:**

1. **동기 이벤트는 지금 실익이 없다.** 승격 트리거 둘(`cancel` · 만료 회수)이 **모두 `EnrollmentService` 안**에 있다. 같은 클래스에서 `private` 메서드를 부를 수 있는데 이벤트를 끼우면 간접 계층만 한 겹 늘어난다.

2. **"나중 전환의 유연성"이 실제로는 크지 않다.** 비동기 전환의 걸림돌은 이벤트 타입의 부재가 아니라 **승격이 `klass` 락 안에서 일어나야 한다는 사실**이다. `AFTER_COMMIT` 으로 옮기는 순간 outbox · 재시도 · 멱등성 · 재락킹을 새로 설계해야 하며, 이벤트 record 를 미리 만들어 둔다고 그 작업이 줄지 않는다.

3. **`private` 이 물리적 방어다.** CLAUDE.md 동시성 규약이 `promoteNextWaiting` 을 `private` 으로 못박은 이유는 "별 빈으로 빼면 `@Transactional` 전파 하나로 락 밖에서 실행돼 그 틈에 신규 신청자가 좌석을 채간다"이다. 이벤트로 빼면 그 방어가 사라지고 규약으로만 남는다 — 누군가 `@TransactionalEventListener` 로 한 글자 바꾸면 **컴파일도 테스트도 통과하면서 조용히 깨진다.**

**되살릴 조건 — 승격 알림이 도입될 때, 알림 리스너에 한정.**

승격과 알림은 성격이 다르다.

| | 승격 | 알림 |
|---|---|---|
| 성격 | **불변식** — 좌석 이전이 원자적이어야 함 | **부수효과** — 실패해도 좌석은 이미 이전됨 |
| 실행 위치 | `klass` 락 안, 같은 트랜잭션 | 커밋 후 (락 안에서 메일·푸시 금지) |
| 적합한 방식 | 직접 호출 | `@TransactionalEventListener(AFTER_COMMIT)` |

즉 **이벤트로 빼야 할 것은 승격이 아니라 알림**이다. 불변식은 트랜잭션 안, 부수효과는 커밋 후 — 이 분리가 이벤트 도입의 올바른 지점이다. ERD §4.8 의 승격 알림이 구현될 때 이 결정을 다시 연다.

### 7.3 계층 배치

```
enrollment/
├── domain/
│   ├── CancelReason.java                    ← 신규 ENUM
│   └── Enrollment.java                      ← 필드·전이 수정
├── application/
│   ├── port/in/ReapExpiredEnrollmentUseCase.java   ← 신규
│   ├── port/out/EnrollmentQueryPort.java           ← 후보 조회 추가
│   └── service/
│       ├── EnrollmentService.java                  ← cancel 수정
│       └── ExpiredEnrollmentReaper.java            ← 신규 (진입점 + 건별 처리)
└── adapter/
    ├── in/web/dto/                          ← cancelReason 노출
    └── out/persistence/                     ← 후보 조회 구현
```

**진입점과 처리 메서드의 빈 분리**가 설계 요점이다. `@Scheduled` 메서드에서 같은 클래스의 `@Transactional` 메서드를 부르면 프록시를 타지 않아 트랜잭션이 걸리지 않는다. 배치 자체가 이 함정의 단골이므로 Design 에서 명시적으로 다룬다.

---

## 8. Convention Prerequisites

### 8.1 기존 규약 (이 사이클에서 지켜야 할 것)

- [x] `CLAUDE.md` 코딩·네이밍 규약 — 시각은 주입된 `Clock` 만, 무인자 `now()` 금지
- [x] ENUM 은 `@Enumerated(EnumType.STRING)`, `{domain}/domain/` 에 배치
- [x] 컬럼 시각 `_at`, boolean `is` 접두어
- [x] 에러 코드에 도메인 접두어
- [x] 쿼리는 QueryDSL 지향, JPQL 문자열은 최후 수단
- [x] CHECK 제약은 표준 JPA `@Table(check = @CheckConstraint(...))` — 속성명 `constraint`(단수)

### 8.2 이 사이클에서 정할 것

| 항목 | 현재 | 정할 것 | 우선순위 |
|------|------|---------|:--------:|
| 배치 진입점 네이밍 | `{역할}Cleaner` (auth 선례) | `{역할}Reaper` vs `{역할}Cleaner` 통일 여부 | Medium |
| 배치 클래스 위치 | `application/service/` (선례) | 동일하게 유지 — 포트만 의존하므로 계층 규칙 안 | High |
| 설정 키 위치 | `jwt.*` (선례) | `app.enrollment.reap-interval` — 기존 `EnrollmentProperties` 확장 | High |

### 8.3 환경 변수 / 설정

| 키 | 목적 | 기본값 | 신규 |
|----|------|--------|:----:|
| `app.enrollment.reap-interval` | 만료 회수 주기 (ISO-8601 Duration) | `PT10M` | ☑ |

> ⚠️ `EnrollmentProperties` 는 **중첩 `record` 프로퍼티가 블록 누락 시 예외 없이 `null` 로 바인딩**되어 기동은 성공하고 첫 사용에서 NPE 가 난다 (Design §4.1.1 ⑤). 주기를 중첩 블록에 넣지 말고 평면 필드로 두거나, 넣는다면 `application.yml` 블록을 반드시 함께 채운다.

---

## 9. Next Steps

1. [ ] 설계 문서 작성 — `/pdca design pending-expiry-reaper`
2. [ ] 설계에서 확정할 것: `Enrollment.cancel` 시그니처 변경 방식(인자 추가 vs `expire()` 분리) · 요약 DTO 의 `cancelReason` 노출 여부 · 배치 진입점 빈 분리 구조
3. [ ] 구현 — module-0 스키마부터 순서대로
4. [ ] 갭 분석 · 완료 보고 · 아카이브

---

## Version History

| Version | Date | Changes | Author |
|---------|------|---------|--------|
| 0.1 | 2026-09-04 | 최초 작성. 요건 3(이벤트 기반 승격)을 D-47 로 이탈 확정 | developer2@lulumedic.com |
| 0.2 | 2026-09-04 | 승격 연쇄 타이밍(§3.1)과 알림 부재 리스크(R-9) 추가. 범위는 유지 | developer2@lulumedic.com |
