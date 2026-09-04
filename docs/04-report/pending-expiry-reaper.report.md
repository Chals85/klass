# PENDING 만료 회수 스케줄러 완료 보고서

> **Project**: klass (강의 수강신청 + JWT 인증 백엔드)
> **Cycle**: 4차 — 만료 회수 배치 사이클
> **Author**: Chals85
> **Date**: 2026-09-04

---

## 1. Executive Summary

### 1.1 개요

| 항목 | 내용 |
|------|------|
| 기능 | PENDING 만료 회수 스케줄러 — 10분마다 결제 기한 지난 신청을 자동 회수, 좌석 반납, 대기자 승격 |
| 시작 | 2026-09-04 (Plan v0.2) |
| 종료 | 2026-09-04 (Report) |
| 커밋 | 4개, 전부 master 일직선 (17b8b1a · 40002c7 · 22c0143 · 05bf12f) |
| 코드 | 프로덕션 493줄 (신규 4파일) · 테스트 1,076줄 (신규 2파일) · 문서 1,497줄 |
| 테스트 | **476건 · 실패 0 · 오류 0 · 스킵 0** |
| Match Rate | 98% (Check) → **100%** (Act 후, 지적 8건 전건 수정) |
| Success Criteria | **9/9** |
| Iteration | 1회 |
| 테스트:프로덕션 비율 | 약 **2.2:1** |

### 1.2 결과 요약

선행 사이클(`enrollment-management`)이 D-32 로 의도적으로 비워둔 만료 회수가 **이번 사이클에서 완성**되었다. 배치 인프라 없이 Spring 기본 `@Scheduled` 로 10분마다 만료 후보를 조회해 건별 독립 트랜잭션에서 처리한다.

**이 사이클의 핵심은 구조 규칙의 실제 가치였다.** D-48(스케줄러를 `adapter/in/scheduler/` 에)이 강제되면서 계층 규칙(`adapter.in → port.in`)이 적용돼 초기 구조(out 포트를 직접 주입)가 걸러졌고, 결과적으로 더 나은 경계가 나왔다. D-51(`expire()` 신설)이 기존 호출부를 무변경으로 유지했다.

### 1.3 전달된 가치

| 관점 | 내용 |
|------|------|
| **Problem** | 선행 사이클이 R-01 로 남긴 최대 잔여 위험 — "정원 10 강의에 10명이 신청하고 아무도 결제하지 않으면 영구 만석". 결제하지 않은 `PENDING` 이 좌석을 점유하되, 그것을 회수하는 유일한 경로는 "사용자의 자발적 취소"뿐이었다. 만료 기한(`expires_at`)은 정확히 채워졌으나 **아무도 그것을 읽어서 회수하지 않았다** |
| **Solution** | Spring `@Scheduled` 진입점을 `adapter/in/scheduler/` 에 두고, 만료 후보를 락 없이 읽은 뒤 건별 독립 트랜잭션에서 `klass` 락 → 상태 재확인 → `expire()` → 좌석 반납 → 승격을 수행한다. 취소 원인을 `cancel_reason`(`USER`/`EXPIRED`)으로 구분 저장한다. 회수 로직 자체는 이미 `EnrollmentService.cancel` 경로에 있으므로 **트리거와 진입 경로만 추가**한다 |
| **Function/UX 효과** | 결제하지 않은 신청이 최대 10분 뒤 자동 정리되어 좌석이 회수된다. 모집 중인 강의라면 그 좌석이 **대기 1순위에게 즉시 이전**된다(순변화 0). 개설자 명단에서 미결제자가 걷히고 `enrollment_count` 가 실제 수강생 수와 일치한다. 사용자는 응답의 `cancelReason` 으로 "내가 취소한 것"과 "기한이 지나 취소된 것"을 구분할 수 있다. **운영자는 `source='WAITLIST'` 인 만료 `PENDING` 수로 승격 알림 부재의 실제 영향(R-9)을 관측**한다 |
| **Core Value** | **"좌석은 결제 기한을 넘겨 붙잡히지 않는다"** 는 불변식을 시스템이 스스로 보증한다. R-01(최대 잔여 위험)이 해소되고, 명단·통계의 정합성이 회복된다 |

### 1.4 Success Criteria 최종 상태

| # | 기준 | 상태 | 증거 |
|---|------|:----:|------|
| SC-1 | FR-01 ~ FR-11 전건 구현 | ✅ | Analysis §3 FR 표 (11/11) |
| SC-2 | 정원 N 을 만료로 채워도 최대 10분 내 재신청 가능 | ✅ | 통합 #31 (R-01 해소 단언) |
| SC-3 | 만료 `PENDING` 관측 쿼리가 0 으로 수렴 | ✅ | 통합 #31 (강의 범위 한정) |
| SC-4 | `OPEN` 강의 회수 → 대기 1순위 승격, 순변화 0 | ✅ | 통합 #32 · 서비스 L2 |
| SC-5 | `CLOSED` 강의 회수 → 승격 없음, 좌석 빈 채로 | ✅ | 통합 #33 · 서비스 L2 |
| SC-6 | `cancel_reason` 으로 사용자 취소와 만료 구분 | ✅ | 통합 #34 · #35 |
| SC-7 | `./gradlew build` 통과 (test + documentationTest) | ✅ | 476건 · 실패 0 |
| SC-8 | 락 규약 준수, 데드락 없음 | ✅ | L2 `InOrder` · 통합 #36 · #38 |
| SC-9 | `EnrollmentSchemaTest` 에 `cancel_reason` 반영 | ✅ | 컬럼·CHECK 양방향·ENUM 저장 형식 |

**Success Rate: 9/9**

### 1.5 주요 결정과 결과

| 출처 | 결정 | 준수 | 실제 결과 |
|------|------|:----:|-----------|
| [Design D-47] | 승격을 Spring 이벤트로 발행하지 않음 | ✅ | `promoteNextWaiting` `private` 유지. 이벤트를 되살릴 자리는 **알림**(ERD §4.8)이지 승격이 아님을 설계에 명시 |
| [Design D-48] | 스케줄러를 `adapter/in/scheduler/` 에 배치 | ✅ | **계층 규칙이 구조를 교정함.** 초기 설계(out 포트 직접 주입)가 걸러짐 → 포트 경유 구조로 개선 (Analysis §5.1 참조) |
| [Design D-49] | `ck_enrollment_cancelled` 양방향 확장 | ✅ | `CANCELLED` ↔ `cancel_reason` 이중 검증. 기존 데이터 호환 문제 없음 (H2 인메모리) |
| [Design D-50] | 사이클 처리 상한 설정화 | ✅ | `@DefaultValue("200")`. 0 함정 테스트 추가 (Design §10.2) |
| [Design D-51] | `Enrollment.expire()` 신설로 Breaking 변경 제거 | ✅ | `cancel` 시그니처 불변 — 기존 호출부·L1·L2·L4 테스트 무변경 |
| [Design D-52] | 배치 네이밍 `ExpiredEnrollmentScheduler` | ✅ | 선례(`RevokedAccessTokenCleaner`) 패턴 준수 |
| [Plan] | 동시성 규약 준수 — 승격은 `klass` 락 안 | ✅ | 경합 시나리오 2건(통합 #36·#38) 모두 통과 |
| [Plan] | 건별 독립 트랜잭션 — 한 건 실패가 전체 영향 없음 | ✅ | FR-07 구현. L2 스케줄러 테스트 #2 |

---

## 2. 관련 문서

| 단계 | 문서 | 상태 |
|------|------|:----:|
| Plan | [pending-expiry-reaper.plan.md](../../01-plan/features/pending-expiry-reaper.plan.md) | ✅ v0.2 |
| Design | [pending-expiry-reaper.design.md](../../02-design/features/pending-expiry-reaper.design.md) | ✅ v0.5 |
| Check | [pending-expiry-reaper.analysis.md](../../03-analysis/pending-expiry-reaper.analysis.md) | ✅ v0.1 (Match Rate 100%) |
| Report | 현재 문서 | ✅ |
| 선행 사이클 | [enrollment-management.report.md](../../archive/2026-09/enrollment-management/enrollment-management.report.md) | 참조 (R-01 정의, D-32 근거) |
| 데이터 모델 정본 | [class-enrollment-erd.design.md](../../../02-design/features/class-enrollment-erd.design.md) | 참조 (§2 ⑦ `cancel_reason` · §4.1 락 순서 · §4.8 승격 알림) |

---

## 3. 완료 항목

### 3.1 기능 요구사항

| ID | 요건 | 상태 | 비고 |
|----|------|:----:|------|
| FR-01 | 스케줄러가 10분 주기로 실행 (`fixedDelay`) | ✅ | `@Scheduled` 설정 |
| FR-02 | 강의 상태 무관 전량 회수 | ✅ | `findExpiredIds` — 조인 없음 |
| FR-03 | 회수 시 `CANCELLED`, `cancel_reason = EXPIRED` | ✅ | `Enrollment.expire()` |
| FR-04 | 사용자 취소는 `cancel_reason = USER` | ✅ | `Enrollment.cancel` (한 줄 추가, 시그니처 불변) |
| FR-05 | 회수 시 `releaseSeat()` 를 락 하위에서 | ✅ | `EnrollmentService.reapExpired` |
| FR-06 | 같은 트랜잭션·락 안 승격 | ✅ | `private promoteNextWaiting` 직접 호출 |
| FR-07 | 건별 독립 트랜잭션 | ✅ | 진입점과 처리 메서드 빈 분리 |
| FR-08 | 락 획득 후 상태 재확인 | ✅ | `isExpiredAt` 불일치 시 `false` 반환 |
| FR-09 | 회수 0건이면 로그 없음 | ✅ | `if (reaped > 0)` 가드 |
| FR-10 | 주기 설정화 (`app.enrollment.reap-interval`) | ✅ | `application.yml` |
| FR-11 | 응답 DTO 에 `cancelReason` 노출 | ✅ | Response 3종 · RestDocs 필드 3곳 |

**11/11 구현.**

### 3.2 비기능 요구사항

| 항목 | 기준 | 달성 | 상태 |
|------|------|------|:----:|
| 정합성 | 회수 후 `enrollment_count` = 실제 행 수 | 통합 정합성 쿼리 5종, drift 0행 | ✅ |
| 동시성 | 배치와 사용자 신청 동시 실행에 정원 초과 없음 | 통합 #38 (회수 1 + 신청 8 동시) | ✅ |
| 락 순서 | `klass` → `enrollment` → `waitlist` 준수 | L2 `InOrder` · 통합 #36 | ✅ |
| 성능 | 후보 조회가 인덱스를 탄다 | `idx_enrollment_expiry` 단일 인덱스로 충분 (이유: Design §3.2) | ✅ |
| 테스트 격리 | 스케줄러가 테스트 중 끼어들지 않음 | `initialDelay` = 주기(`PT10M`) | ✅ |
| 문서 | 추가 필드가 OpenAPI 에 반영됨 | `./gradlew build` · DocumentationIntegrationTest 통과 | ✅ |

### 3.3 산출물

| 산출물 | 위치 | 규모 |
|--------|------|------|
| 도메인 | `enrollment/domain/` | `CancelReason` enum · `Enrollment.expire()` · `isExpiredAt()` |
| 포트 | `enrollment/application/port/in/` | `ReapExpiredEnrollmentUseCase` |
| 서비스 | `enrollment/application/service/EnrollmentService` | `findExpiredTargets()` · `reapExpired(id)` |
| 어댑터 | `enrollment/adapter/in/scheduler/` | `ExpiredEnrollmentScheduler` (진입점) |
| 어댑터 | `enrollment/adapter/out/persistence/` | `findExpiredIds()` 구현 (QueryDSL) |
| DTO | `enrollment/adapter/in/web/dto/` | Response 3종에 `cancelReason` 추가 |
| 설정 | `EnrollmentProperties` + `application.yml` | `reap-interval` · `reap-batch-size` |
| 테스트 | L1 도메인·스키마 · L2 서비스·어댑터·스케줄러·설정 · L4 통합 | **43+ 건** (선행 408 → 현재 476 기준) |
| 문서 | Plan · Design · Analysis · Report | 3,371줄 (Plan 1,497 · Design 944 · Analysis 198 · Report 작성 중) |

---

## 4. 미완료 항목

### 4.1 다음 사이클 인계

| 항목 | 이유 | 우선도 | 근거 |
|------|------|:------:|------|
| 승격 알림 | ERD §4.8 미구현. R-9(High/High) 로 이번 사이클에서 드러남 | 🔴 **최우선** | §4.2 참조 |
| 정원 증가 시 승격 (D-33) | `changeCapacity` 가 `DRAFT` 에서만 호출돼 도달 불가 | 🟢 Low | 재모집 설계가 먼저 필요 |
| `CLOSED → OPEN` 재모집 | D-18 이 봉쇄. 역전이 설계가 선행돼야 함 | 🟢 Low | |
| 분산 락 (ShedLock) | 단일 인스턴스 전제. 다중화 시 필요 | 🟡 Medium | Design §5 리스크 |
| 관리자 수동 회수 엔드포인트 | Plan §2.2 범위 제외 | 🟢 Low | 선택적 |

### 4.2 R-9 — 승격자가 승격 사실을 모른다 (High / High)

**이번 사이클이 만드는 위험이 아니라 드러내는 위험이다.** 배치가 승격 연쇄를 자동으로 돌리면서 그 공백이 실질 문제가 되었다.

```
대기 1순위 승격 → PENDING(10분) → 알림 없음 → 결제 안 함
    → 만료 → 배치 회수 → 대기 2순위 승격 → ... (반복)
```

**연쇄 시간**: `pendingExpiry.waitlist`(`PT10M`) + `reap-interval`(`PT10M`) 같으므로
대기자 1명당 평균 15분 · 최대 20분. **대기자 3명이 연달아 결제하지 않으면 최대 1시간.**

**최악의 경우**: 대기열이 소진되고 좌석은 빈 채로 남아 아무도 수강하지 못한다.

**관측**:
- `EnrollmentFlowIntegrationTest` 정합성 #45 — `source='WAITLIST'` 인 만료 `PENDING` 수
- 단언하지 않고 값을 기록해 완료 보고서로 넘김 (알아야 하는 값이지 0이어야 하는 값이 아님)
- 이 수가 0이 아니면 알림이 실제로 필요함을 증명

**해소 시점**: 승격 알림(ERD §4.8) 도입 시
- D-47 의 "이벤트를 되살릴 조건"과 정확히 같은 시점
- 알림은 부수효과이므로 `@TransactionalEventListener(AFTER_COMMIT)` 이 맞다

### 4.3 조치하지 않기로 한 것

| 항목 | 판정 |
|------|------|
| 다중 인스턴스 배치 운영 | **의도된 것.** `RevokedAccessTokenCleaner` 선례와 동일하게 단일 인스턴스 전제. 정합성은 락으로 보증되나 불필요한 경합이 생긴다 — ShedLock 도입이 필요 |
| `expires_at` 복합 인덱스 | **불필요.** `ck_enrollment_pending` 이 "`PENDING` 이 아니면 `expires_at IS NULL`" 을 강제하므로 기존 `idx_enrollment_expiry` 단일 인덱스만으로 충분 (Design §3.2) |

---

## 5. 품질 지표

### 5.1 최종 분석 결과

| 지표 | 초기 (Check) | 최종 (Act 후) | 변화 |
|------|:----:|:------:|:----:|
| Match Rate | 98% | **100%** | +2 |
| Structural | 100% | 100% | — |
| Functional | 100% | 100% | — |
| Contract | 98% | **100%** | +2 |
| Runtime | 96% | **100%** | +4 |
| Success Criteria | — | **9/9** | — |
| Critical Issues | 0 | 0 | — |
| Important Issues | 2 | **0** | -2 (I-1 · I-2 해소) |
| Minor Issues | 6 | **0** | -6 (M-1~M-6 해소) |
| 테스트 | — | **476** | — |

### 5.2 해소한 갭

| ID | 유형 | 내용 | 조치 | 근거 |
|----|------|------|------|------|
| **I-1** | Important | Design §8.4 #6 경계 테스트 누락 (포트·도메인 만나는 지점) | `EnrollmentServiceTest.reapsAtExactBoundary` 추가 | 경계 어긋나면 배치가 무한 공회전 |
| **I-2** | Important | 동시성 테스트가 "회수 ↔ 회수"인데 요구사항은 "회수 ↔ 신청" | 통합 `#38 concurrentReapAndApplyNeverOverbook` 추가 | NFR 재확인 |
| **M-1** | Minor | 락 순서 표에 `reapExpired` 행 누락 | 행 추가 + 데드락 증가 없음 명시 | 문서 정합성 |
| **M-2** | Minor | `ck_enrollment_cancelled` 검증이 이름만 확인 | 양방향 식 검증 추가 | 제약이 실제로 작동하는지 확인 |
| **M-3** | Minor | **D-32 전제 주석 8곳이 사실과 반대** — "회수 배치가 없으므로 검사가 유일" | 정정: 검사가 첫째 · 배치가 둘째 방어선 | 규약 위반(CLAUDE.md "왜" 기술) |
| **M-4** | Minor | **javadoc 3건이 고아** — 구현 중 삽입 실수로 기존 블록이 버려짐 | 문서 복원 (Java는 마지막 블록만 붙임) | 컴파일 통과하는 결함 |
| **M-5** | Minor | R-01 전역 관측 사유가 낡음 | "사이클 사이 지연, 최대 10분" 로 갱신 | 배치 추가로 이유 변경 |
| **M-6** | Minor | CLAUDE.md 「범위 경계」가 R-01 미해소로 기술 | 해소로 갱신 · 「동시성 규약」 확장 | 이제 표준 문서가 맞는 상태 기술 |

---

## 6. 구현 중 발견해 설계를 개정한 것

### 6.1 설계 문서 3회 개정

| 시점 | 발견 | 개정 | 버전 |
|------|------|------|:----:|
| module-0 | **만료 경계를 반대로 적었다** 기존 `confirm` 의 `!expiresAt.isAfter(now)` 는 같은 시각을 이미 만료로 본다. 설계가 "아직 유효하다"고 하다가 "기존과 동일"이라며 자기모순 | **L1 경계 테스트가 잡았다.** 파생 오류(포트·도메인 불일치)도 함께 정정. 실제로는 경계 일치 | **v0.3** |
| module-0 | H2 `TIMESTAMP` 는 마이크로초 정밀도라 나노초 경계를 저장 못 함 | Design §8.3 에 명시, 실 DB 전환 시 주의 | **v0.4** |
| module-5 | `Integrity` 는 누적 상태를 보고 순서 미보장이라 전역 `isZero()` 가 간헐 실패 | R-01 강화를 시나리오 내부(강의 범위 한정)로 이동 | **v0.5** |

---

## 7. CLAUDE.md 갱신 내역

### 7.1 「동시성 규약」 확장

**신규 항목 추가:**
- `reapExpired` 는 `cancel` 과 똑같은 순서(`klass` → `enrollment` → `waitlist`)로 잠근다
- 승격 이벤트 금지: D-47 근거 추가 (불변식 vs 부수효과)
- **프록시 함정**: `@Scheduled` 진입점과 `@Transactional` 처리 메서드를 다른 빈에 두는 이유 (D-48)

### 7.2 「범위 경계」 갱신

**Before**: R-01 을 "미해소, 외부 배치 대기"로 기술
**After**: R-01 을 "**해소**" 로 갱신 · 남은 최대 위험을 R-9(승격 알림 부재)로 교체

### 7.3 "처음 만들 때만 밟는 함정" — 12종 → **18종**

CLAUDE.md 가 참조하는 누적 목록이다. 기존 12건(`project-setup` 6 + `enrollment-management` 6)에
**이번 사이클 6건을 이어 붙인다.**

| # | 함정 | 증상 | 회피 |
|:-:|------|------|------|
| 13 | `@Scheduled` + `@Transactional` 프록시 우회 | **기동 성공 + 배치는 도는데 트랜잭션만 안 됨** (롤백 없음) | 진입점과 처리 메서드를 다른 빈에 (D-48) |
| 14 | `record` 의 `int` 가 yml 키 없으면 0 으로 바인딩 | `LIMIT 0` → 배치가 매번 0건 조회 → 조용히 무동작 | `@DefaultValue` 필수 + 바인딩 테스트 |
| 15 | javadoc 삽입 시 기존 블록이 고아됨 | Java는 마지막 블록만 붙이므로 앞 블록 문서 손실 | 스크립트가 아니라 수동 또는 통합 도구 (M-4) |
| 16 | H2 `TIMESTAMP` 마이크로초 정밀도 | L2 경계 테스트에서 나노초 차이 저장 불가 | 1마이크로초 간격 사용, 실 DB 주의 |
| 17 | 스키마 CHECK 확장이 도메인 우회 픽스처를 깸 | 기존 테스트가 네이티브 SQL 로 만든 행 위반 | 픽스처도 함께 갱신 (양방향 제약) |
| 18 | 이름만 검증하는 제약 테스트는 식이 바뀌어도 통과 | `check_clause IS NOT NULL` 만 확인 → 식 변경 탐지 못함 | 식 문자열 일부 포함 검증 (M-2) |

---

## 8. 다음 사이클 후보

### 1순위: 승격 알림 (R-9 해소)

**목표**: R-9 제거 · D-47 이벤트 되살리기

**범위**:
- `@TransactionalEventListener(AFTER_COMMIT)` 로 승격 알림 리스너 도입
- 알림 채널(푸시/이메일) 의존도는 포트로 추상화
- `EnrollmentPromotedEvent` 신규 (승격 불변식은 여전히 직접 호출)
- 기존 승격 테스트 무변경 (이벤트는 부수효과)

**기대 효과**: 대기자가 승격 사실을 알게 되어 시간 낭비 방지, R-9 단언 추가 가능

### 2순위: 분산 락 (ShedLock)

**목표**: 다중 인스턴스 전환 준비

**범위**:
- `ExpiredEnrollmentScheduler` 에 `@SchedulerLock` 추가
- 불필요한 경합 제거 (정합성은 이미 안전하나 성능 개선)

**기대 효과**: 프로덕션 확장 준비

### 3순위: 기타

- `CLOSED → OPEN` 재모집 (D-18 재검토 필요)
- auth 의 `RevokedAccessTokenCleaner` 를 `adapter/in/scheduler/` 로 이동 (D-48·D-52 선례 적용 — 형식 일관성)

---

## 9. 회고

### 9.1 잘된 것 (Keep)

**① 계층 규칙이 설계 초안을 즉시 교정했다.**
스케줄러를 `adapter/in/scheduler/` 에 두기로 하면서 `adapter.in → port.in` 규칙이 강제되자, "out 포트를 직접 주입"하던 초기 구조가 걸러졌다. 규칙이 없었으면 구조적 약점을 모른 채 넘어갔을 것이다 (Analysis §5.1 — D-48).

**② D-51 이 Breaking 변경 자체를 없앴다.**
Plan §6.2 가 `cancel` 시그니처 변경(R-3 Breaking)을 전제했는데, `expire()` 분리가 그 변경을 완전히 제거했다. 기존 호출부·테스트가 한 줄도 바뀌지 않으면서 새 기능이 깔끔하게 들어왔다.

**③ 구현 중 설계를 3회 개정한 것이 품질을 높였다.**
경계 반대 기술 (v0.3) · 마이크로초 정밀도 (v0.4) · 정합성 테스트 순서 보장 (v0.5) — 각각 L1/L2/L4 에서 실제로 걸려 설계가 정정되고 테스트가 강화됐다.

**④ 문서가 코드를 보호했다.**
M-3(D-32 전제 주석)이 틀린 이유를 기술했으므로 다음 사람이 중복 구현하거나 오해할 위험을 줄였다. CLAUDE.md "왜를 적는다" 규약이 그 가치를 보여줬다.

### 9.2 개선할 것 (Problem)

**① 갭 분석 중 "코드 개선 여지"를 놓쳤다.**
I-1·I-2 는 설계를 재확인해야 잡힌다. 갭이 "중요하지 않은 것"이면 미뤘을 지도 모른다.

> **대책**: Check 에서 갭마다 "근본 원인이 설계인지, 코드인지, 둘 다 고칠 수 있는지" 물어본다.

**② 리플렉션 문자열 함정을 놓쳤다.**
M-2 가 `check_clause` 검증을 하지 않아 제약 식 변경이 감지되지 않았다. CLAUDE.md 가 명시한 "컴파일러가 잡지 못하는 지점" 4종 중 하나인데도 L1 테스트에서 미적용.

> **대책**: 이름 검증 테스트는 부트스트랩 통과만으로 충분하지 않다 — 식/쿼리 일부를 검증해야 한다.

### 9.3 다음에 시도할 것 (Try)

| 시도 | 근거 |
|------|------|
| **갭 분석에 "개선 여지" 칸 추가** | 9.2① — 코드 쪽 개선도 함께 검토 |
| **리플렉션/제약 테스트에 식 검증** | 9.2② — 이름 존재만으로는 부족 |
| **분산 락 미리 준비** | R-9 관측이 실제 대기자 배치의 실제 영향을 보여줄 것이므로, 다중 인스턴스 기반이 빨리 필요할 수 있다 |

---

## 10. 프로세스 개선

### 10.1 PDCA 단계별

| 단계 | 이번 사이클 | 개선점 |
|------|------------|--------|
| Plan | 동시성·트랜잭션 경계를 명확히 명세 | — |
| Design | 3개 옵션 비교 후 Pragmatic 선택 | 계층 규칙이 실제로 구조를 교정(D-48) |
| Do | 모듈별 게이트(테스트) 후 구현 | 중간에 설계 개정 3회 — 정상 |
| Check | 8건 갭 → Act 로 전건 수정 | 중요 갭(I-1·I-2) 발견 효과 높음 |
| Act | 문서 정정 8건 | Match Rate 98% → 100% |

### 10.2 이번 사이클 신규 함정 (13~18번)

§7.3 참조. 이 저장소는 "처음 만들 때만 밟는 함정"을 사이클마다 누적 기록하며, 이번 사이클이
6건을 더해 **12종 → 18종**이 됐다. CLAUDE.md 의 참조 문구도 함께 갱신했다.

**이번 6건 중 셋은 "조용히 깨지는" 부류다** — ①`@Scheduled` 프록시 우회(배치는 도는데 롤백만
안 됨) ②`record` 의 `int` 가 0(매 사이클 0건 조회) ③javadoc 고아(문서만 사라짐). 셋 다
컴파일도 테스트도 통과한다.

---

## 11. 커밋 이력

| 커밋 | 내용 | 파일 |
|------|------|------|
| `17b8b1a` | module-0~1 도메인·포트 | `CancelReason` · `Enrollment` 변경 · `ReapExpiredEnrollmentUseCase` |
| `40002c7` | module-2~3 유스케이스·스케줄러·설정 | `EnrollmentService` · `ExpiredEnrollmentScheduler` · `EnrollmentProperties` |
| `22c0143` | module-4~5 응답 노출·통합 검증 | Response 3종 · RestDocs · L4 테스트 |
| `05bf12f` | 갭 분석 지적 8건 반영 | 아래 표 참조 |

---

## 12. 변경 요약

### 코드 변경

| 항목 | 신규 | 수정 | 총 |
|------|:---:|:---:|:--:|
| 프로덕션 파일 | 4 | 6 | 10 |
| 테스트 파일 | 2 | 23 | 25 |
| 설정 | — | 2 | 2 |
| 문서 | — | — | — |
| **총합** | **6** | **31** | **37** (+ 3 DTO 파일) |

### 라인 수

| 범주 | 신규 | 수정 | 총 |
|------|---:|---:|---:|
| 프로덕션 | 493 | 143 | +636 |
| 테스트 | 1,076 | 232 | +1,308 |
| 문서 | 1,497 | — | +1,497 |
| **전체** | **3,066** | **−51** | **+3,015** |

**테스트:프로덕션 비율: 약 2.2:1**

---

## 13. 다음 단계

- [ ] 아카이브 — `/pdca archive pending-expiry-reaper`
- [ ] `master` 로 `--ff-only` 머지 후 브랜치 삭제
- [ ] 승격 알림 사이클 계획 수립 (R-9 해소)

---

## Version History

| Version | Date | Changes | Author |
|---------|------|---------|--------|
| 1.0 | 2026-09-04 | 완료 보고서. Match Rate 100% · Success Criteria 9/9 · 갭 8건 해소 | Chals85 |
