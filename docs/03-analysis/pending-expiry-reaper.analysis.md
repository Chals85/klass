# PENDING 만료 회수 스케줄러 갭 분석

> **Match Rate**: **98%** (수정 후 **100%**)
> **Project**: klass
> **Date**: 2026-09-04
> **Plan**: [pending-expiry-reaper.plan.md](../01-plan/features/pending-expiry-reaper.plan.md)
> **Design**: [pending-expiry-reaper.design.md](../02-design/features/pending-expiry-reaper.design.md) (v0.5)
> **구현 커밋**: `17b8b1a` · `40002c7` · `22c0143` (브랜치 `feat/pending-expiry-reaper`)

---

## Context Anchor

| Key | Value |
|-----|-------|
| **WHY** | 결제하지 않은 `PENDING` 이 좌석을 영구 점유해 강의가 영구 만석이 되는 R-01 을 해소한다 |
| **WHO** | 수강생(대기자) · 개설자 · 운영자 |
| **RISK** | 배치가 `klass` 락 순서를 어기거나 락 밖에서 승격해 신규 신청자와 좌석을 두고 경합하는 것 |
| **SUCCESS** | 만료 후 최대 10분 내 `enrollment_count` 감소 · 대기 1순위 승격 · R-01 관측 쿼리 0 수렴 |
| **SCOPE** | module-0 스키마·도메인 → module-5 L4 통합 (전 모듈 완료) |

---

## 1. 종합 결과

| 축 | 가중 | 초기 | 수정 후 |
|---|---:|---:|---:|
| 구조적 일치 (Structural) | 0.15 | 100% | 100% |
| 기능적 깊이 (Functional) | 0.25 | 100% | 100% |
| 설계 명세 대조 (Contract) | 0.25 | 98% | 100% |
| 런타임 검증 (Runtime) | 0.35 | 96% | 100% |
| **종합** | | **98%** | **100%** |

**부가 축** — 규약 준수(CLAUDE.md) 100% · Divergence(D-47~D-52) 6/6 준수.

### 런타임 검증 실측

| 항목 | 결과 |
|------|------|
| `./gradlew build` | ✅ BUILD SUCCESSFUL (test + documentationTest) |
| 전체 테스트 | **476건 · 실패 0 · 오류 0 · 스킵 0** |
| `openapi3.json` | `cancelReason` 6곳 반영, 엔드포인트 **16개 유지** |

---

## 2. 전략적 정합성

### 2.1 원 문제(WHY)를 풀었는가 — ✅

`EnrollmentFlowIntegrationTest#31` 이 R-01 해소를 직접 단언한다. 정원 1 강의를 만료 `PENDING` 으로 채워 409 를 확인한 뒤, 회수 후 같은 요청이 **201** 을 받는다. 배치가 없던 시절 이 신청은 영원히 409 였다.

### 2.2 Plan Success Criteria

| 기준 | 판정 | 근거 |
|------|:----:|------|
| FR-01 ~ FR-11 전건 구현 | ✅ | 아래 §3 |
| 정원 N 을 만료로 채워도 최대 10분 내 재신청 가능 | ✅ | 통합 #31 |
| 만료 `PENDING` 관측 쿼리가 0 으로 수렴 | ✅ | 통합 #31 (강의 범위 한정 — Design §8.8.3) |
| `OPEN` 강의 회수 → 대기 1순위 승격, 순변화 0 | ✅ | 통합 #32 · 서비스 L2 #7 |
| `CLOSED` 강의 회수 → 승격 없음, 좌석 빈 채로 | ✅ | 통합 #33 · 서비스 L2 #8 |
| `cancel_reason` 으로 사용자 취소와 만료 구분 | ✅ | 통합 #34 · #35 |
| `./gradlew build` 통과 | ✅ | 실측 |
| `EnrollmentSchemaTest` 에 `cancel_reason` 반영 | ✅ | 컬럼·CHECK 식·양방향 거부·ENUM 저장 형식 |
| 락 규약 위반 없음 | ✅ | L2 `InOrder` + 통합 #36 · #38 |

**9/9 충족.**

### 2.3 Decision Record 준수

| ID | 결정 | 준수 |
|----|------|:----:|
| D-47 | 승격을 이벤트로 발행하지 않음 | ✅ `promoteNextWaiting` `private` 유지, `ApplicationEventPublisher` 사용처 없음 |
| D-48 | 스케줄러를 `adapter/in/scheduler/` 에 | ✅ 계층 규칙이 실제로 구조를 교정 (아래 §5.1) |
| D-49 | `ck_enrollment_cancelled` 양방향 | ✅ 식 검증 + 양방향 거부 테스트 |
| D-50 | 사이클 처리 상한 | ✅ `@DefaultValue("200")` + 0 함정 테스트 |
| D-51 | `expire()` 신설 | ✅ `cancel` 시그니처 불변 — 기존 호출부·테스트 무변경 |
| D-52 | `{대상}Scheduler` 네이밍 | ✅ |

---

## 3. 기능 요구사항 대조

| FR | 판정 | 근거 |
|----|:----:|------|
| FR-01 10분 주기 `fixedDelay` | ✅ | `ExpiredEnrollmentScheduler:72` |
| FR-02 강의 상태 무관 전량 | ✅ | `EnrollmentQueryDslRepository` — `where` 절이 `status` · `expiresAt` **둘뿐**, `klass` 조인 없음 |
| FR-03 `EXPIRED` 로 회수 | ✅ | `Enrollment.expire` |
| FR-04 사용자 취소는 `USER` | ✅ | `Enrollment.cancel` (한 줄 추가, 시그니처 불변) |
| FR-05 `releaseSeat()` 를 락 하위에서 | ✅ | `EnrollmentService.reapExpired` |
| FR-06 같은 트랜잭션·락 안 승격 | ✅ | `private promoteNextWaiting` 직접 호출, 전파 미명시 |
| FR-07 건별 독립 트랜잭션 | ✅ | 진입점에 `@Transactional` 없음 + 처리 메서드가 다른 빈 |
| FR-08 락 획득 후 재확인 | ✅ | `isExpiredAt` 불일치 시 `false`(예외 아님) |
| FR-09 0건이면 로그 없음 | ✅ | `if (reaped > 0)` 가드 |
| FR-10 주기 설정화 | ✅ | `application.yml` |
| FR-11 응답 노출 | ✅ | Response 3종 + RestDocs 3곳 |

**11/11 구현.**

---

## 4. 갭 목록과 조치

**Critical — 없음.** 동시성 규약·계층 규칙·트랜잭션 경계 위반이 없었다.

### 4.1 Important (2건, 전건 수정)

| ID | 내용 | 조치 |
|----|------|------|
| **I-1** | Design §8.4 #6 경계 테스트(`expires_at == now` 가 재확인을 통과)가 **다른 테스트로 대체**돼 있었다. 경계 자체는 L1·L2어댑터가 덮지만 **둘이 만나는 지점**이 미검증 | `EnrollmentServiceTest.reapsAtExactBoundary` 추가 |
| **I-2** | Plan NFR·Design §8.8.2 #5 는 "**회수 ↔ 신규 신청**" 동시 실행인데 구현은 "회수 ↔ 회수"였다 | 통합 `#38 concurrentReapAndApplyNeverOverbook` 추가 (기존 #36 은 유지 — 겨냥점이 다르다) |

**I-1 이 중요한 이유**: 포트는 `expires_at <= now`, 도메인은 `!expiresAt.isAfter(now)` — 같은 조건이다. 한쪽만 고쳐 어긋나면 배치가 **매 사이클 같은 대상을 집었다가 전부 걸러내는 무한 공회전**이 되는데, 회수 0건이면 로그도 안 남으므로 드러나지 않는다.

### 4.2 Minor (6건, 전건 수정)

| ID | 내용 | 조치 |
|----|------|------|
| **M-1** | `EnrollmentService` 락 순서 표에 `reapExpired` 행 없음 | 행 추가 + "`cancel` 과 같은 순서라 데드락이 늘지 않는다" 명시 |
| **M-2** | `ck_enrollment_cancelled` 검증이 **이름 존재**에 그침 — 확장 전 단방향 식이 남아도 통과 | `check_clause` 문자열에 `cancel_reason` 포함을 검증 |
| **M-3** | **`D-32` 전제 주석 8곳이 이제 틀린 이유를 말한다** — "회수 배치를 만들지 않으므로 이 검사가 **유일한** 방어선" | 전건 정정: `confirm` 검사가 **첫째**, 배치가 **둘째** 방어선 |
| **M-4** | **javadoc 3건이 고아가 됨** (구현 중 삽입 실수) — Java 는 마지막 블록만 붙이므로 앞 블록이 버려진다 | `isSeatOccupying` · `INVALID_ENROLLMENT_PAGE_SIZE` · R-01 관측 테스트의 문서 복원 |
| **M-5** | R-01 전역 관측의 사유가 낡음 | "배치가 없으므로 영구 점유" → "사이클 사이의 지연, 최대 10분" |
| **M-6** | CLAUDE.md 「범위 경계」가 R-01 을 미해소로 기술 | 해소로 갱신 + 「동시성 규약」에 `reapExpired`·이벤트 금지·프록시 함정 반영 |

**M-4 는 구현이 만든 결함**이다. 스크립트로 javadoc 을 삽입하면서 기존 블록 앞에 새 블록을 넣어, 두 메서드가 문서를 잃고 한 테스트가 엉뚱한 문서를 갖게 됐다. 컴파일도 테스트도 통과하므로 정적 분석 없이는 드러나지 않는다.

**M-3 이 규약 위반인 이유**: CLAUDE.md 가 "주석에 **왜 그렇게 했는지**를 적는다"를 규약으로 두는데, 8곳이 사실과 반대되는 이유를 말하고 있었다. 다음 사람이 "만료 회수가 없구나"로 읽고 중복 구현하거나, `confirm` 검사를 유일한 방어선으로 오해할 수 있다.

---

## 5. 설계가 실제로 값을 한 지점

### 5.1 D-48 이 구조를 교정했다

스케줄러를 `adapter/in/scheduler/` 에 두기로 하면서 `adapter.in → port.in` 규칙이 적용됐고, **후보 조회를 위해 `EnrollmentQueryPort`(out 포트)를 직접 주입하려던 초기 구조가 걸러졌다.** 대신 `ReapExpiredEnrollmentUseCase.findExpiredTargets()` 를 경유한다. `ExpiredEnrollmentSchedulerTest$LayerRule` 이 생성자 시그니처로 이를 못박는다.

### 5.2 D-51 이 Breaking 변경을 없앴다

Plan §6.2 는 `cancel(now, today, policy)` 에 인자를 추가하는 것을 전제하고 **R-3(Breaking)** 으로 기록했다. `expire()` 를 신설해 그 변경 자체가 사라졌고, 기존 호출부·L1·L2·L4 테스트가 한 줄도 바뀌지 않았다.

### 5.3 인덱스가 공짜였다

`ck_enrollment_pending` 이 "`PENDING` 이 아니면 `expires_at IS NULL`" 을 강제하므로 **`expires_at IS NOT NULL` 인 행은 정의상 전부 `PENDING`** 이다. 기존 `idx_enrollment_expiry` 단일 인덱스만으로 후보가 정확히 걸러지며, 이 사이클 전까지 사용처가 없던 인덱스가 처음 제값을 했다.

---

## 6. 구현 중 발견해 설계를 고친 것

| 시점 | 발견 | 설계 반영 |
|------|------|-----------|
| module-0 | **만료 경계를 반대로 적었다.** 기존 `confirm` 의 `!expiresAt.isAfter(now)` 는 같은 시각을 **이미 만료**로 본다. "아직 유효하다"고 쓴 §3.2 가 같은 문단에서 "기존 confirm 과 동일한 경계"라고도 해 자기모순이었다. **L1 경계 테스트가 잡았다** | v0.3 — 파생 오류(포트·도메인 "의도된 불일치")도 함께 정정. 실제로는 **경계가 일치**한다 |
| module-0 | H2 `TIMESTAMP` 는 마이크로초 정밀도라 L2 에서 나노초 경계를 저장하지 못한다 | v0.4 — §8.3 에 명시, 실 DB 전환 시 주의점 포함 |
| module-5 | `Integrity` 는 누적 상태를 보고 순서를 보장하지 않아 전역 `isZero()` 가 간헐 실패를 만든다 | v0.5 — R-01 강화를 시나리오 내부(강의 범위 한정)로 이동 |

---

## 7. 잔여 위험

### R-9 — 승격자가 승격 사실을 모른다 (High / High) — **미해소**

이 사이클이 **만드는** 위험이 아니라 **드러내는** 위험이다. 배치가 승격 연쇄를 자동으로 돌리면서 전면에 나왔다.

```
대기 1순위 승격 → PENDING(10분) → 알림 없음 → 미결제 → 만료
    → 배치 회수 → 대기 2순위 승격 → ...
```

`pendingExpiry.waitlist`(`PT10M`)와 `reap-interval`(`PT10M`)이 같아 **대기자 1명당 평균 15분·최대 20분**. 대기자 3명이 연달아 결제하지 않으면 최대 1시간이며, **대기열은 소진되고 좌석은 빈 채로 남는다.**

- **관측**: `EnrollmentFlowIntegrationTest` 정합성 #45 — `source='WAITLIST'` 인 만료 `PENDING` 수. 단언하지 않고 값을 기록한다
- **해소 시점**: 승격 알림(ERD §4.8) 도입 시. D-47 의 "이벤트를 되살릴 조건"과 같은 시점 — 알림은 부수효과이므로 `AFTER_COMMIT` 리스너가 맞다

### 그 밖에 유지되는 공백

| 항목 | 근거 |
|------|------|
| 정원 증가 시 대기열 승격 (D-33) | `changeCapacity` 가 `DRAFT` 에서만 호출돼 도달 불가 |
| `CLOSED → OPEN` 재모집 | D-18 이 봉쇄. 역전이를 여는 설계가 선행돼야 한다 |
| 다중 인스턴스 배치 | 단일 인스턴스 전제. 정합성은 안전하나 경합이 낭비된다 (ShedLock 필요) |
| 관리자 수동 회수 엔드포인트 | Plan §2.2 에서 제외 |
| 외부 결제 연동 | ERD §1.3 |

---

## 8. 다음 단계

1. [ ] 완료 보고서 — `/pdca report pending-expiry-reaper`
2. [ ] 아카이브 — `/pdca archive pending-expiry-reaper`
3. [ ] `master` 로 `--ff-only` 머지 후 브랜치 삭제

---

## Version History

| Version | Date | Changes |
|---------|------|---------|
| 0.1 | 2026-09-04 | 최초 분석. Match Rate 98% → 지적 8건 전건 수정 후 100% |
