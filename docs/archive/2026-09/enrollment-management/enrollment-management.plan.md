# 수강신청 · 취소 관리 계획서

> **Summary**: 비어 있는 `enrollment`/`waitlist` 도메인에 신청 → 결제 확정 → 취소의 전 계층을
> 붙이고, klass-management 에서 걷어냈던 비관적 락(D-21)과 대기열 승격(D-16)을 되살린다.
>
> **Project**: klass (강의 수강신청 + JWT 인증 백엔드)
> **Version**: 2차 — 수강신청 사이클
> **Author**: Chals85
> **Date**: 2026-09-02
> **Status**: Draft

---

## Executive Summary

| 관점 | 내용 |
|------|------|
| **Problem** | 강의는 등록·공개까지 완결됐지만 **아무도 신청할 수 없다.** `enrollment`/`waitlist` 는 엔티티와 빈 Repository 까지만 있고 상태 전이 메서드·포트·서비스·컨트롤러가 전부 없다. `klass.enrollment_count` 는 읽는 코드만 있고 쓰는 코드가 없어 **항상 0** 이다. 정원 관리라는 이 프로젝트의 핵심 난제(마지막 한 자리에 동시 신청)가 아직 손도 대지 않은 상태다 |
| **Solution** | ERD 정본 §4 의 동시성 규약을 **그대로 구현한다.** 정원과 관련된 모든 트랜잭션이 `klass` 행을 `SELECT ... FOR UPDATE` 로 가장 먼저 잡아(§4.1) 경합을 한 행에서 직렬화하고, 그 락 아래에서 검사·INSERT·카운터 갱신을 한 트랜잭션으로 끝낸다. 결제는 외부 연동 없이 상태 전이 API(`PENDING → CONFIRMED`)로 대체한다. 취소는 `confirmed_at + 취소 가능 기간` 과 `klass.ends_on` 두 관문을 통과해야 한다 |
| **Function/UX효과** | 신규 API 9개(신청·결제확정·취소·상세·내 신청목록·수강생목록·대기등록·대기포기·내 대기목록). 정원이 찬 강의에 신청하면 거부되고, 사용자가 원할 때만 대기열에 들어가며, 앞자리가 비면 **신규 신청자보다 먼저** 승격된다 |
| **Core Value** | **"마지막 한 자리에 100명이 동시에 신청해도 정확히 1명만 성공한다"** 를 DB 제약과 락으로 이중 보증한다. 카운터가 실제 좌석 점유 행 수와 어긋나지 않는다는 것을 정합성 검증 쿼리로 증명한다 |

---

## Context Anchor

> Plan 의 Executive Summary · Requirements · Risks 에서 추출. Design/Do 문서로 전파된다.

| Key | Value |
|-----|-------|
| **WHY** | 강의는 만들 수 있지만 신청할 수 없다. 좌석 점유·정원 관리·취소 정책이 전부 미구현이고, `enrollment_count` 를 쓰는 코드가 없어 정원 검사가 무의미하다 |
| **WHO** | **수강생**(ROLE_USER) — 신청·결제확정·취소·대기. **크리에이터**(ROLE_CREATOR) — 자기 강의의 수강생 명단 조회. 권한 겸용 사용자는 **자기가 개설한 강의에 신청할 수 없다** |
| **RISK** | ① **PENDING 만료 회수를 이번에 만들지 않는다**(사용자 결정) → 미결제 신청이 좌석을 영구 점유해 정원이 조용히 줄어든다. ② 락 획득 순서 위반 시 데드락. ③ H2 의 비관적 락 거동이 실 DB 와 달라 동시성 테스트가 위양성을 낼 수 있다 |
| **SUCCESS** | 잔여 1석에 100건 동시 신청 → 정확히 1건 성공·99건 거부, `enrollment_count == capacity`. 정합성 검증 쿼리(ERD §5.1) 결과가 비어 있다. 신규 엔드포인트 9개 전부 RestDocs 문서에 실린다 |
| **SCOPE** | M1 도메인 행위 → M2 신청·정원(락) → M3 결제확정·취소 → M4 대기열 → M5 조회 → M6 klass 연동(D-16/D-21 해소) |

---

## 1. Overview

### 1.1 목적

`enrollment` 와 `waitlist` 를 **엔티티만 있는 상태에서 전 계층 완결**로 끌어올린다. 구체적으로
지금 없는 것은 다음과 같다.

| 없는 것 | 현재 상태 |
|---------|-----------|
| `Enrollment.confirm()` / `cancel()` | `apply()` 팩토리만 있다. 상태 전이 메서드가 없다 |
| `Waitlist` 의 승격·포기 메서드 | 엔티티 필드만 있다 |
| `Klass.increaseEnrollmentCount()` / `decrease...()` | **없다.** `changeCapacity` 가 `enrollmentCount` 를 **읽지만** 쓰는 코드가 없어 값이 항상 0 이다 |
| 포트 (`EnrollmentCommandPort` / `QueryPort` 등) | `EnrollmentJpaRepository` 가 빈 인터페이스다 |
| 서비스 · 컨트롤러 · DTO | 전무 |
| `KlassQueryPort.findByIdForUpdate` | **의도적으로 걷어냈다** (D-21). 이번에 되살린다 |
| `app.enrollment.*` 프로퍼티 | `application.yml` 에 `app.enrollment` 블록 자체가 없다 |

### 1.2 배경

klass-management 사이클은 비관적 락을 **일부러 걷어냈다.** 락이 직렬화하려던 상대가
수강신청 트랜잭션(ERD 정본 §4.2)인데 그것이 존재하지 않았기 때문이다. 되돌아올 좌표가
세 곳에 근거와 함께 남아 있다 — `KlassQueryPort` · `KlassJpaRepository` · `KlassService.loadForCommand`
javadoc (Design D-21).

같은 이유로 대기열 승격도 미뤄져 있다. `Klass.close()` 와 `Klass.changeCapacity()` 의 javadoc 에
**"2차에서 여기에 붙는다"** 가 각각 적혀 있다 (D-16). 이번 사이클이 그 두 부채를 정리하는데,
**둘의 결말이 다르다** — `close` 쪽은 실제 구현으로 갚고, `changeCapacity` 쪽은 그 조건이
성립하지 않음을 확인해 **"필요 없음"으로 닫는다** (근거 §9.1). 어느 쪽이든 javadoc 이
부채를 계속 가리키고 있으면 다음 사람이 잘못 읽으므로 함께 고친다.

### 1.3 관련 문서

| 문서 | 역할 |
|------|------|
| `docs/02-design/features/class-enrollment-erd.design.md` | **데이터 모델·동시성 규약의 정본.** §4 전체가 이 사이클의 구현 명세다 |
| `docs/archive/2026-09/klass-management/klass-management.design.md` §12 | D-16(대기열 미구현) · **D-21(락 제거)** 의 근거. 되살릴 좌표가 여기 있다 |
| `docs/archive/2026-09/klass-management/klass-management.report.md` §7.2 | 기능을 처음 만들 때만 밟는 함정 6종 (Security 설정 · RestDocs · 파라미터 검증) |
| `CLAUDE.md` | 컨벤션 · 문서 파이프라인 · 컴파일러가 잡지 못하는 지점 4종 |

---

## 2. Scope

### 2.1 In Scope

**M1 — 도메인 행위**

- [ ] `Enrollment.confirm(now)` — `PENDING → CONFIRMED`, `confirmed_at` 설정, `expires_at` NULL 화
- [ ] `Enrollment.cancel(now)` — `→ CANCELLED`, `cancelled_at` 설정, `expires_at` NULL 화
- [ ] `Enrollment` 판별 메서드 — `isOwnedBy(userId)` · `isSeatOccupying()` · `isExpiredAt(now)` ·
      `isCancellableAt(now, ends_on, periodDays)`
- [ ] `Waitlist.promote(now)` / `Waitlist.giveUp()` / `Waitlist.rejectAsIneligible()`
- [ ] `Klass.increaseEnrollmentCount()` / `decreaseEnrollmentCount()` — **`enrollment_count` 를 쓰는 최초의 코드**
- [ ] L1 도메인 단위 테스트 (전이 규칙 · 종착 상태 · 취소 기간 경계)

**M2 — 수강 신청 + 정원 관리 (ERD §4.2)**

- [ ] `KlassQueryPort.findByIdForUpdate` 복원 — `@Lock(PESSIMISTIC_WRITE)` +
      **`@EntityGraph` 를 붙이지 않는다** (락 대상은 `klass` 단일 행, D-21)
- [ ] 신청 트랜잭션 — 락 → `OPEN` 검사 → 중복 검사 → 정원 검사 → INSERT → 카운터 증가
- [ ] **개설자 본인 신청 차단** (FR-19, 신규 요건)
- [ ] `POST /v1/klasses/{klassId}/enrollments`
- [ ] 동시성 테스트 — 잔여 1석 100건 동시 신청

**M3 — 결제 확정 + 취소 (ERD §4.3 · §4.4)**

- [ ] 결제 확정 트랜잭션 — `enrollment` 단독 행 락. **`klass` 락을 잡지 않는다**
      (PENDING 이 이미 좌석을 점유해 카운터가 변하지 않으므로, §4.1 의 명시된 예외)
- [ ] 만료 시각이 지난 PENDING 의 결제 거부 (§4.3 4번)
- [ ] 취소 트랜잭션 — `klass` 락 → `enrollment` 락 → 소유권 → 취소 가능 판정 → 카운터 감소
- [ ] **취소 가능 판정 2관문**: `now <= confirmed_at + COALESCE(klass.cancellation_period_days, 7)`
      **AND** `LocalDate.now(clock) <= klass.ends_on` (FR-20, 후자가 신규 요건)
- [ ] `POST /v1/enrollments/{id}/confirm` · `POST /v1/enrollments/{id}/cancel`

**M4 — 대기열 (ERD §4.4 8~9번 · §4.5 · §4.9)**

- [ ] 대기열 등록 — `klass` 락 하위, `MAX(position)+1`
- [ ] **개설자 본인 대기 등록 차단** (FR-19)
- [ ] 승격 루프 — 좌석 반납 트랜잭션 **같은 락 아래에서** 1건만 승격. 부적격 대기자는 건너뛴다
- [ ] 대기 포기 — `waitlist` 단독 행 락 (§4.1 의 두 번째 예외)
- [ ] `POST /v1/klasses/{klassId}/waitlist` · `POST /v1/waitlist/{id}/cancel` · `GET /v1/waitlist/me`

**M5 — 조회**

- [ ] `GET /v1/enrollments/me` — 내 신청 목록 (커서 페이지네이션)
- [ ] `GET /v1/enrollments/{id}` — 신청 상세 (본인만)
- [ ] `GET /v1/klasses/{klassId}/enrollments` — 강의별 수강생 목록 (`ROLE_CREATOR` **AND** 소유권)
- [ ] **fetch join 정책 결정** — `Enrollment.klass`/`user` 가 `@ManyToOne(LAZY)` 라 목록 조회에서
      N+1 이 나는 자리다 (CLAUDE.md 범위 경계에 예고돼 있다)

**M6 — klass 연동 (부채 해소)**

- [ ] `CLOSED` 전이 시 잔여 `WAITING` 일괄 `CANCELLED` (§4.8 5번, D-16 해소).
      **`Klass` 도메인이 아니라 `port.in` 위임으로 처리한다** — 근거 §9.1
- [ ] `Klass.changeCapacity()` 의 정원 증가 시 승격 (§4.8 capacity 5번) —
      **구현하지 않고 근거를 등재한다.** `changeCapacity` 는 `DRAFT` 에서만 호출되고
      `DRAFT` 는 신청·대기가 불가능해 **승격 대상이 구조적으로 항상 0** 이다 (§9.1).
      Design §12 divergence 로 등재하고 javadoc 을 "미래에 필요해지는 조건"으로 바꿔 쓴다
- [ ] `KlassService.loadForCommand` — 명령 경로를 락 조회로 전환 (D-21 해소)

**M7 — 설정 · 문서 · 검증**

- [ ] `app.enrollment.*` 프로퍼티 3종 + `@ConfigurationProperties` 클래스
- [ ] RestDocs 테스트 9개 — **엔드포인트보다 먼저 쓴다** (안 쓰면 빌드가 깨진다)
- [ ] `DocumentationIntegrationTest` 의 path/operation 목록 갱신
- [ ] `EnrollmentSchemaTest` 갱신 — 이번 사이클이 `waitlist` 를 실제로 쓰는 최초의 코드다
- [ ] 정합성 검증 쿼리(ERD §5.1)를 통합 테스트로 — **시나리오 전부 수행 후 결과가 비어 있어야 한다**

### 2.2 Out of Scope

| 제외 항목 | 이유 |
|-----------|------|
| **PENDING 만료 회수 (ERD §4.6)** | **사용자 결정.** 외부 배치 서버가 도는 것을 전제하며 이번엔 만료 처리 자체를 만들지 않는다. 새 `@Scheduled` 컴포넌트도 추가하지 않는다 (기존 `SchedulingConfig` 는 인증 도메인의 `RevokedAccessTokenCleaner` 가 쓰므로 건드리지 않는다). `expires_at` 은 `ck_enrollment_pending` 이 강제하므로 **채우기는 한다** — 결제 시 §4.3 4번이 만료를 거부하는 데 쓰인다. **잔여 리스크는 §5 R-01** |
| `app.enrollment.pending-expiry-scan-interval` | 위와 함께 제외. 프로퍼티 4종 중 3종만 추가한다 |
| 외부 결제 연동 | 요건상 상태 변경 API 로 대체 (ERD §1.3) |
| 승격 알림 발송 | 채널 미정 (ERD §1.3) |
| `enrollment.cancel_reason` (사용자 취소 vs 만료 구분) | ERD §2 ⑦ 의 열린 미결. 만료 처리를 만들지 않으므로 이번엔 구분할 원인이 하나뿐이다 |
| `EnrollmentStatusHistory` 감사 로그 | ERD §1.3 에서 YAGNI 로 미채택 확정 |
| `CLOSED → OPEN` 재모집 전이 | ERD §4.8 에서 초기 구현 차단 확정. 대기열이 붙어도 정책 판단이 먼저 필요하다 |
| 회원가입 API | ERD §2 ④ — 시딩으로 검증 가능 |

---

## 3. Requirements

### 3.1 기능 요구사항

> ID 는 ERD 정본 §6 요건 추적표와 맞춘다. **FR-19 · FR-20 은 이번에 새로 도출된 요건**으로
> ERD 정본에 대응 항목이 없다 — Design §12 divergence 로 등재한다.

| ID | 요건 | 정본 위치 | 우선도 | 상태 |
|----|------|-----------|--------|------|
| FR-05 | `Enrollment` 상태 전이 메서드 (`confirm`/`cancel`) | §3.4 | High | Pending |
| FR-06 | `PENDING → CONFIRMED → CANCELLED` 전이 규칙 준수 | §3.4 | High | Pending |
| FR-07 | 동일 사용자 활성 중복 신청 차단 (앱 검사 + `uq_enrollment_active` 이중 방어) | §4.2 3번 | High | Pending |
| FR-08 | 정원 초과 신청 거부 + **마지막 자리 동시 신청 직렬화** | §4.1, §4.2 | **High** | Pending |
| FR-09 | `enrollment_count` 정합성 보증 (`ck_klass_count` + 검증 쿼리) | §3.5.2, §5 | High | Pending |
| FR-10 | 내 수강 신청 목록 조회 | §3.6, §7 | High | Pending |
| FR-11 | 취소 가능 기간 제한 — **결제일(`confirmed_at`) 기준** | §4.4 5-b | High | Pending |
| FR-12 | 대기열 등록 (사용자 명시 요청, 자동 등록 아님) | §4.5 | Medium | Pending |
| FR-13 | 강의별 수강생 목록 (크리에이터 전용, 소유권 검사 동반) | §7 | Medium | Pending |
| FR-14 | 신청 내역 커서 페이지네이션 | §3.6 | Medium | Pending |
| FR-18 | 대기열 승격 — 좌석 반납과 **같은 트랜잭션·같은 락** | §4.4 8~9번 | Medium | Pending |
| **FR-19** | **개설자는 자기 강의에 신청·대기 등록할 수 없다** | **정본에 없음 (신규)** | **High** | Pending |
| **FR-20** | **`klass.ends_on` 경과 후에는 취소 가능 기간 내여도 취소 불가** | **정본에 없음 (신규)** | **High** | Pending |
| FR-21 | 신청 상세 단건 조회 (본인만) | 신규 (조회 편의) | Low | Pending |
| FR-16 | PENDING 좌석 점유 만료 정책 | §4.6 | — | **부분 충족** — `expires_at` 을 채우고 결제 시 거부하되 **회수하지 않는다** (§2.2) |

#### FR-19 의 설계 함의 — 왜 대기열까지 막아야 하는가

신청만 막으면 우회로가 남는다. 개설자가 자기 강의 대기열에 등록하면 자리가 나는 순간
승격 루프가 `PENDING` 행을 만들어 준다 — 신청 API 를 거치지 않고 좌석을 점유하게 된다.
따라서 차단은 **세 지점**에 들어간다.

| 지점 | 검사 |
|------|------|
| 신청 (§4.2) | `klass.isOwnedBy(userId)` → REJECT |
| 대기열 등록 (§4.5) | 동일 |
| **승격 루프 적격성 검사 (§4.4 9-d)** | 기존 조건(`is_enabled` AND 활성 신청 부재)에 `NOT isOwnedBy` 추가 |

세 번째가 없으면 FR-19 이전에 등록된 대기 행이나 미래의 경로 추가에서 구멍이 다시 열린다.

#### FR-20 의 설계 함의 — 판정식이 두 갈래인 이유

취소 판정은 **두 관문의 AND** 다. 하나로 합칠 수 없다.

```
취소 가능 ⟺ (status = 'PENDING')                                          -- 관문 면제
         ∨ (status = 'CONFIRMED'
            ∧ now       <= confirmed_at + COALESCE(k.cancellation_period_days, 7)  -- 기간
            ∧ today     <= k.ends_on)                                              -- 종료일
```

- **`PENDING` 은 두 관문 모두 면제된다** (사용자 결정). 결제 전이라 환불할 돈이 없고,
  무엇보다 기간 기산점인 `confirmed_at` 이 아직 `NULL` 이다
- 기간 비교는 `LocalDateTime`(분 단위), 종료일 비교는 `LocalDate` 다. **타입이 다르다** —
  ERD §2.2 의 "`DATE` 와 현재 시각을 비교하는 지점" 경계가 여기서 실제로 발현한다.
  `LocalDate.now(clock)` 으로 얻은 값과만 비교하고 **무인자 `LocalDate.now()` 는 금지**다

### 3.2 비기능 요구사항

| 범주 | 기준 | 측정 방법 |
|------|------|-----------|
| **정합성** | `enrollment_count` == 실제 `PENDING`+`CONFIRMED` 행 수. 어떤 순서로 무엇을 해도 어긋나지 않는다 | ERD §5.1 검증 쿼리를 통합 테스트 마지막에 실행 → 결과 0행 |
| **동시성** | 잔여 1석 100건 동시 신청 → 1건 성공, 99건 거부 | `ExecutorService` + `CountDownLatch` 로 동시 발사, 성공 카운트 단정 |
| **동시성** | 데드락이 발생하지 않는다 | 락 획득 순서 `klass → enrollment → waitlist` 를 서비스 javadoc 에 명문화. 취소·승격을 뒤섞어 동시 실행하는 테스트 |
| **결정성** | 시각 의존 로직을 고정 시각으로 테스트할 수 있다 | 주입된 `Clock` 만 사용. **무인자 `now()` 금지** — 취소 기간 경계 테스트가 이것에 전적으로 의존한다 |
| **문서** | 신규 엔드포인트 9개가 `openapi3.json` 에 빠짐없이 실린다 | `DocumentationIntegrationTest` 의 path/operation 검증 |
| **성능** | 목록 조회에서 N+1 이 발생하지 않는다 | fetch join 명시 + 쿼리 카운트 검증 |
| **보안** | 타인의 신청을 확정·취소·조회할 수 없다 | 403 시나리오 테스트 (ERD §8 32·33번) |

### 3.3 API 목록 (신규 9개)

| # | Method | Path | 권한 | 정본 |
|:-:|--------|------|------|------|
| 1 | `POST` | `/v1/klasses/{klassId}/enrollments` | 인증. `ROLE_USER`. **개설자 아님** | §4.2 |
| 2 | `GET` | `/v1/klasses/{klassId}/enrollments` | `ROLE_CREATOR` **AND** `creator_id == sub` | §7 |
| 3 | `POST` | `/v1/enrollments/{id}/confirm` | 인증 **AND** `enrollment.user_id == sub` | §4.3 |
| 4 | `POST` | `/v1/enrollments/{id}/cancel` | 인증 **AND** `enrollment.user_id == sub` | §4.4 |
| 5 | `GET` | `/v1/enrollments/me` | 인증. `user_id := sub` | §3.6, §7 |
| 6 | `GET` | `/v1/enrollments/{id}` | 인증 **AND** `enrollment.user_id == sub` | 신규 |
| 7 | `POST` | `/v1/klasses/{klassId}/waitlist` | 인증. **개설자 아님** | §4.5 |
| 8 | `POST` | `/v1/waitlist/{id}/cancel` | 인증 **AND** `waitlist.user_id == sub` | §4.9 |
| 9 | `GET` | `/v1/waitlist/me` | 인증. `user_id := sub` | 신규 |

> **9번이 필요한 이유**: 8번을 호출하려면 `waitlistId` 를 알아야 하는데, 등록 응답을 놓치면
> 다시 알아낼 경로가 없어진다. 같은 이유로 5번이 3·4번의 전제다.
>
> **전이별 엔드포인트로 나눈 이유** (3·4번을 `PATCH /status` 하나로 합치지 않은 것):
> 강의는 `PATCH /v1/klasses/{id}/status` 하나로 `publish`/`close` 를 받는다. 그쪽은 전이별
> 검증이 "화이트리스트 조회" 한 가지로 균질했다. 반면 `confirm` 은 **만료 시각**을,
> `cancel` 은 **취소 기간·종료일·카운터·승격**을 다루고 락 범위마저 다르다(`enrollment` 단독 vs
> `klass`+`enrollment`+`waitlist`). 한 엔드포인트에 담으면 그 분기가 컨트롤러로 새어나온다.
> **Design 에서 최종 확정한다.**

경로 수 영향: 기존 8 path / 10 operation → **16 path / 19 operation.**

---

## 4. Success Criteria

### 4.1 Definition of Done

- [ ] FR-05 ~ FR-14, FR-18 ~ FR-21 전부 구현 (FR-16 은 §2.2 의 부분 충족 범위까지)
- [ ] ERD §8 검증 시나리오 중 **이번 범위 해당분 전부** 테스트로 존재 —
      1·2·3·4·5·6·9·10·11·12·13·14·17·18·19·20·21·22·23·28·32·33·34·35·36·37·38·40·41
      (7·8 은 만료 처리 제외로 범위 밖. **29번**은 §4.3 4번 검사가 살아 있으므로 유지한다.
      **39번**(정원 증가 시 승격)은 현 정책에서 도달 불가 — §9.1)
- [ ] `./gradlew build` 통과 — `test` → 스니펫 → `openapi3` → `documentationTest` 전 구간
- [ ] `EnrollmentSchemaTest` 가 `waitlist` 의 FK·CHECK·UNIQUE·인덱스까지 확인
- [ ] klass-management 의 부채 해소 확인 — D-16 (`close` 는 구현으로, `changeCapacity` 는
      "필요 없음" 결론으로 닫혔는지) · D-21 (락 복원)
- [ ] **빈 의존 그래프에 순환이 없다** — 컨텍스트가 `@Lazy` 없이 기동한다 (§9.1)
- [ ] `KlassQueryPort`·`KlassJpaRepository`·`KlassService` 의 **"2차에서 추가된다" javadoc 갱신** —
      부채가 갚혔는데 문서가 부채를 가리키고 있으면 다음 사람이 잘못 읽는다

### 4.2 품질 기준

- [ ] 정합성 검증 쿼리(ERD §5.1)가 전 시나리오 수행 후 **0행**
- [ ] 동시 신청 100건 테스트가 **반복 실행에도** 안정적으로 1건만 성공
- [ ] Lombok × Java 25 경고 외 신규 컴파일 경고 없음
- [ ] 무인자 `LocalDateTime.now()` / `LocalDate.now()` 가 `src/` 전체에 0건
      (`grep -rn 'LocalDate\(Time\)\?\.now()' src/`)
- [ ] 신규 도메인 코드의 주석이 **왜 그렇게 했는지**를 담고, 주요 결정에 `Design Ref: §n` 부착

---

## 5. Risks and Mitigation

| ID | 리스크 | 영향 | 확률 | 완화 |
|----|--------|------|------|------|
| **R-01** | **PENDING 만료 회수가 없어 미결제 신청이 좌석을 영구 점유한다** — 정원 10 강의에서 10명이 신청하고 아무도 결제하지 않으면 그 강의는 **영구히 만석**이 된다. 사용자가 취소하지 않으면 회수 경로가 전무하다 | **High** | **High** | 사용자가 명시적으로 선택한 범위 제외이고, 외부 배치 서버가 만료를 처리한다는 전제다. 이번 사이클은 **관측 가능성만 확보**한다 — ERD §5.1 검증 쿼리와 함께 "만료 시각이 지난 `PENDING` 행 수" 를 세는 쿼리를 §5 검증 세트에 추가한다. `expires_at` 은 정확히 채우므로 외부 배치가 붙는 즉시 동작한다. **다음 사이클 최우선 항목으로 완료 보고서에 등재** |
| R-02 | 락 획득 순서 위반 → 데드락 | High | Medium | ERD §4.1 규약(`klass → enrollment → waitlist`)을 서비스 클래스 javadoc 에 명문화. **§4.1 이 명시한 두 예외**(결제 확정 = `enrollment` 단독, 대기 포기 = `waitlist` 단독)를 예외로만 유지하고 그 안에서 추가 락을 잡지 않는다. 취소·승격·정원수정을 교차 동시 실행하는 테스트 |
| R-03 | `enrollment_count` drift — 카운터와 실제 행 수가 어긋난다 | High | Medium | 카운터 증감을 **도메인 메서드로만** 노출하고 락 트랜잭션 밖에서 호출할 경로를 만들지 않는다. `ck_klass_count` 가 최종 방어. 검증 쿼리를 통합 테스트에 상주 |
| R-04 | **H2 의 비관적 락 거동이 실 DB 와 달라 동시성 테스트가 위양성을 낸다** | High | Medium | H2 는 `MODE=MySQL` 이지만 락 구현이 같지 않다. 따라서 "성공 1건" 만 세지 말고 **`enrollment_count == capacity` 와 실제 행 수까지** 단정한다. 락이 실제로 걸리는지는 lock timeout 유발 케이스로 별도 확인. 인메모리 H2 에서 동시 트랜잭션 테스트가 불안정하면 그 사실 자체를 완료 보고서에 남긴다 |
| R-05 | 엔드포인트 9개를 추가하는데 RestDocs 테스트를 빠뜨려 **문서에서 조용히 누락**된다 | Medium | **High** | CLAUDE.md 가 지목한 구조적 함정이다. 모듈별로 **RestDocs 테스트를 컨트롤러보다 먼저** 쓴다. `DocumentationIntegrationTest` 의 operation 맵이 최종 방어선이며, **깨지면 고칠 것은 개수가 아니라 테스트다** |
| R-06 | `waitlist` 를 실제로 쓰는 최초의 코드라 **선언만 되고 생성되지 않은 제약**이 드러난다 | Medium | Medium | 과거에 FK 5개가 없는 채로 빌드가 통과한 적이 있다. `EnrollmentSchemaTest` 를 `waitlist` 6종(테이블/FK/CHECK/UNIQUE/인덱스/ENUM)까지 확장하고 **구현 전에 먼저 돌린다** |
| R-07 | 목록 조회 N+1 — `Enrollment.klass`/`user` 가 `LAZY` | Medium | **High** | CLAUDE.md 가 이미 예고한 자리다. 조회 3종(FR-10·13·21)마다 fetch join 을 명시하고 쿼리 카운트를 검증. `open-in-view: false` 라 **지연 로딩이 컨트롤러에서 터진다** — 위양성이 아니라 즉시 실패로 드러나는 것이 오히려 유리하다 |
| R-08 | 대기열 포함으로 구현·테스트 분량이 약 2배 | Medium | High | M1~M7 모듈 분할 + `--scope` 기반 다중 세션 구현. **M4(대기열)를 M2·M3 뒤에 두어** 좌석 반납 경로가 먼저 검증된 뒤 승격을 얹는다 |
| R-09 | 이름 변경 시 **컴파일은 통과하고 런타임에 실패**하는 자리 4종 | Medium | Medium | 파생 쿼리(`existsByKlassIdAndUserIdAndStatusIn`)·리플렉션·RestDocs 경로. 이름을 바꾸기 전 단어 경계 grep. 이번 사이클은 JPQL 문자열을 쓰지 않는 쪽을 우선한다 (`KlassJpaRepository` 의 선례) |
| R-10 | `Klass` 락 복원이 **klass-management 의 기존 테스트를 깨뜨린다** | Low | Medium | `loadForCommand` 가 락 조회로 바뀌면 `@EntityGraph` 가 빠져 개설자 로딩 경로가 달라진다. `KlassServiceTest`·`KlassControllerTest`·`KlassFlowIntegrationTest` 를 영향 목록(§6.2)에 올려 함께 확인 |

---

## 6. Impact Analysis

> 이번 사이클은 **비어 있는 도메인을 채우는 일**이라 신규 추가가 대부분이지만,
> `klass` 도메인에는 **기존 동작을 바꾸는 변경**이 들어간다. 아래가 그 전수다.

### 6.1 변경되는 자원

| 자원 | 종류 | 변경 내용 |
|------|------|-----------|
| `Klass` (도메인) | 엔티티 | **추가** — `increaseEnrollmentCount()` / `decreaseEnrollmentCount()`. **javadoc 수정** — `close`·`changeCapacity` 의 "2차에서 붙는다" 를 실제 결론으로 교체 (§9.1). 대기자 정리는 서비스 계층 위임이므로 **엔티티는 바뀌지 않는다** |
| `KlassQueryPort` | 포트 (out) | **추가** — `findByIdForUpdate(klassId)` (D-21 해소) |
| `KlassJpaRepository` | 어댑터 | **추가** — `findWithLockById` + `@Lock(PESSIMISTIC_WRITE)`. **`@EntityGraph` 를 붙이지 않는다** |
| `KlassService.loadForCommand` | 서비스 | **수정** — 명령 경로를 락 조회로 전환 |
| `Enrollment` / `Waitlist` | 엔티티 | **추가** — 상태 전이·판별 메서드 |
| `EnrollmentJpaRepository` / `WaitlistJpaRepository` | 어댑터 | **추가** — 파생 쿼리, 락 조회 |
| `enrollment` / `waitlist` 패키지 | 신규 계층 | 포트·서비스·DTO·컨트롤러 전부 신규 |
| `application.yml` | 설정 | **추가** — `app.enrollment.*` 3종 |
| `SecurityConfig` | 설정 | **수정** — 신규 경로 9개의 인증·권한 규칙 |
| `openapi3.json` | 산출물 | path 8 → 16, operation 10 → 19 |

### 6.2 기존 소비자

`klass` 자원의 변경에 영향을 받는 **모든** 기존 코드 경로다.

| 자원 | 작업 | 코드 경로 | 영향 |
|------|------|-----------|------|
| `KlassQueryPort.findById` | READ | `KlassService.loadForCommand` (수정·상태전이) | **Breaking 아님, 동작 변화** — 락 조회로 대체된다. 락 조회는 개설자를 함께 읽지 않으므로 **응답 DTO 가 개설자명을 필요로 하면 추가 조회가 발생**한다. 확인 필요 |
| `KlassQueryPort.findById` | READ | `KlassService.findKlass` (상세 조회) | **None** — 조회 경로는 락을 잡지 않는다. 그대로 유지 |
| `KlassJpaRepository.findWithCreatorById` | READ | `KlassRepositoryAdapter` | **None** — 락 조회를 **별 메서드로** 추가하므로 기존 경로가 그대로다 (`findById` 를 오버라이드하지 않은 원래 판단이 여기서 값을 한다) |
| `Klass.close()` | UPDATE | `KlassService.changeStatus` → `ChangeKlassStatusUseCase` → `KlassController` PATCH `/status` | **Needs verification** — 엔티티는 그대로지만 **`KlassService.changeStatus` 가 대기자 정리를 위임 호출**한다. `@Transactional` 전파가 `REQUIRED` 인지 확인 (§9.1 함정). `KlassServiceTest`·`KlassControllerTest` 의 `close` 케이스에 위임 검증 추가 |
| `Klass.changeCapacity()` | UPDATE | `KlassService.updateKlass` (`isFullyEditable()` 분기 안) | **None** — 승격을 넣지 않기로 했으므로(§9.1) 코드가 바뀌지 않는다. javadoc 만 갱신한다 |
| `Klass.enrollmentCount` | READ | `Klass.changeCapacity` 의 `capacity < enrollmentCount` 검사 | **동작이 처음으로 유효해진다** — 지금까지 항상 0 이라 이 검사가 무의미했다. `KlassTest` 의 해당 케이스가 이제 진짜 값으로 돌아간다 |
| `Klass.enrollmentCount` | READ | `KlassResponse` / `KlassSummaryResponse` (`data.enrollmentCount`) | **None (표시값이 실제로 변한다)** — 기존 RestDocs 스니펫의 예시값이 0 이었다면 갱신 필요 |
| `klass` 테이블 | 스키마 | `EnrollmentSchemaTest` | **Needs verification** — `waitlist` 검증 확장. 스키마 자체는 변경 없다 |
| 문서 파이프라인 | BUILD | `DocumentationIntegrationTest.DOCUMENTED_OPERATIONS` | **Breaking** — 신규 9 operation 을 추가하지 않으면 실패한다. **의도된 실패다** |

### 6.3 검증

- [ ] 위 소비자 전부가 변경 후에도 동작함을 기존 테스트로 확인
- [ ] 락 조회 전환이 `KlassFlowIntegrationTest` 의 수정·상태전이 흐름을 깨지 않음
- [ ] `enrollment_count` 가 0 이 아닌 값을 갖게 되면서 기존 RestDocs 예시값·단정이 어긋나지 않음
- [ ] 신규 경로 9개가 `SecurityConfig` 에서 의도한 권한으로 보호됨 (열어둔 경로가 없는지)
- [ ] **부채 해소 후 javadoc 갱신** — "2차에서 추가된다" 를 가리키는 주석 5곳
      (`KlassQueryPort` · `KlassJpaRepository` · `Klass.close` · `Klass.changeCapacity` ·
      `Enrollment` 클래스 주석)

---

## 7. Architecture Considerations

### 7.1 프로젝트 레벨

| 레벨 | 특징 | 선택 |
|------|------|:----:|
| Starter | 단순 구조 | ☐ |
| Dynamic | 기능별 모듈, BaaS 연동 | ☐ |
| **Enterprise** | **엄격한 계층 분리, 포트↔어댑터** | **☑** |

이 저장소는 이미 **헥사고날 + 도메인별 수직 분할**로 확정돼 있다. 새 레벨을 고르는 단계가
아니라 **기존 배치를 그대로 따르는** 단계다. `enrollment`/`waitlist` 패키지가 `klass` 와 동일한
구조를 갖는다.

### 7.2 주요 아키텍처 결정

> 상세 근거는 Design 에서 다룬다. Plan 단계에서는 **선택지가 갈리는 지점**만 등재한다.

| 결정 | 선택지 | 잠정 선택 | 근거 |
|------|--------|-----------|------|
| **동시성 제어** | 비관적 락 / 낙관적 락(`@Version`) / DB 제약만 | **비관적 락** | 사용자 지정. ERD §4 전체가 이 전제로 쓰였다. 낙관적 락은 인기 강의에서 재시도 폭풍이 나고, 재시도 로직 자체가 새 복잡도다 |
| **락 획득 순서** | `klass` 선행 / 자원별 자유 | **`klass` 선행 고정** | ERD §4.1. 모든 경합을 `klass` 한 행에서 직렬화하면 그 아래 순서는 무해해진다 |
| **락 조회 메서드 이름** | `findWithLockById` / `findByIdForUpdate` | **`findWithLockById`** | Spring Data 는 `find`~`By` 사이를 무시한다. `ForUpdate` 를 `By` **뒤**에 두면 `id.forUpdate` 속성을 찾다가 **부트스트랩에서 깨진다** (`KlassJpaRepository` javadoc 이 이미 경고) |
| **서비스 분리 단위** | 좌석 유스케이스 **단일 서비스** / 도메인별 분리 / 명령·조회 분리 | **단일 서비스 (잠정)** | **도메인별로 쪼개면 빈 의존이 순환해 기동이 실패한다** — 근거는 O-1. ERD §4.1 이 `klass` 행을 트랜잭션 경계의 루트로 지정했으므로 세 테이블은 논리적으로 하나의 애그리거트다 |
| **`confirm`/`cancel` 엔드포인트** | 전이별 분리 / `PATCH /status` 통합 | **전이별 분리** | §3.3 각주 참조. Design 에서 최종 확정 |
| **승격 루프 위치** | 좌석 단일 서비스 / 도메인별 배치 / 도메인 이벤트 | **좌석 단일 서비스 (잠정)** | 승격은 `waitlist`·`enrollment`·`klass` 를 **모두 읽고 쓴다.** 어느 도메인 서비스에 두든 순환이 생긴다 — O-1 참조 |
| **`enrollment_count` 갱신 주체** | `Klass` 도메인 메서드 / 서비스에서 JPQL UPDATE | **`Klass` 도메인 메서드** | 상태 변경은 의도가 드러나는 메서드로만 (컨벤션). JPQL 문자열은 CLAUDE.md 가 지목한 "컴파일러가 잡지 못하는 지점" 1번이다 |
| **테스트 프레임워크** | 기존 유지 | JUnit 5 + AssertJ + Mockito + RestDocs | 변경 없음 |

### 7.3 계층 배치

```
Selected Level: Enterprise (헥사고날, 도메인별 수직 분할)

enrollment/
├── adapter/
│   ├── in/web/
│   │   ├── controller/  EnrollmentController
│   │   └── dto/         (Request/Response)
│   └── out/persistence/ EnrollmentJpaRepository (기존, 확장)
│                        EnrollmentRepositoryAdapter (신규)
├── application/
│   ├── dto/             (Command/Result)
│   ├── port/in/         ApplyEnrollmentUseCase, ConfirmEnrollmentUseCase,
│   │                    CancelEnrollmentUseCase, FindEnrollmentUseCase, ListEnrollmentUseCase
│   ├── port/out/        EnrollmentCommandPort, EnrollmentQueryPort
│   └── service/         EnrollmentService (분리 여부는 Design)
└── domain/
    ├── Enrollment (기존, 행위 추가)
    ├── EnrollmentStatus / EnrollmentSource (기존)
    └── error/           EnrollmentError (신규)

waitlist/  — 동일 구조
```

**의존 규칙 준수 확인점** (위반 시 설계 위반):

| 위치 | 이번 사이클에서 걸릴 수 있는 지점 |
|------|-----------------------------------|
| `domain` | `Enrollment.isCancellableAt(...)` 이 `Klass` 의 `ends_on`·`cancellation_period_days` 를 봐야 한다. **`Enrollment.klass` 로 접근하면 LAZY 프록시 초기화가 도메인에서 일어난다.** 파라미터로 받는 쪽이 안전하다 — Design 에서 확정 |
| `application.service` | 승격 루프가 `waitlist` 와 `enrollment` 양 도메인의 포트를 함께 쓴다. 도메인 간 의존 방향을 Design 에서 못박는다 |
| `adapter.out` | `EnrollmentRepositoryAdapter` 가 `klass` 락을 잡을 일이 없어야 한다 — 락은 `KlassQueryPort` 소관 |
| `adapter.in` | 엔티티 직접 노출 금지. `data.isEnabled` 처럼 **boolean `is` 접두어** 유지 |

---

## 8. 컨벤션 전제

### 8.1 기존 프로젝트 컨벤션

- [x] `CLAUDE.md` 에 코딩 컨벤션 절이 있다
- [x] `docs/02-design/features/class-enrollment-erd.design.md` — **ERD 정본**
- [x] `docs/archive/2026-09/project-setup/project-setup.design.md` §10 — 규약 정본
- [x] Gradle 빌드 · Checkstyle 성격의 규약은 CLAUDE.md 표로 관리
- [ ] `CONVENTIONS.md` (별 파일 없음 — CLAUDE.md 가 그 역할)

### 8.2 이 사이클에서 특히 걸리는 규약

| 항목 | 규칙 | 이번 사이클의 적용 지점 |
|------|------|-------------------------|
| **시각** | 주입된 `Clock` 만. **무인자 `now()` 금지** | 취소 기간 판정(`LocalDateTime`)과 종료일 판정(`LocalDate`) **양쪽 모두**. `LocalDate.now(clock)` 을 써야 시간대 결정이 `ClockConfig` 한 곳에 모인다 |
| **boolean** | 전 계층 `is` 접두어 | `isSeatOccupying` · `data.isCancellable` 등 |
| 컬럼 명명 | 시각 `_at`, **날짜 `_on`**, 기간 `_days` | 신규 컬럼 없음. 기존 명명을 읽는 쪽에서 혼동 주의 (`ends_on` 은 날짜, `confirmed_at` 은 시각) |
| ENUM | `@Enumerated(STRING)`. **ordinal 금지** | 기존 3종 유지 |
| 상태 변경 | public setter 없음. 의도가 드러나는 메서드로만 | `confirm()` · `cancel()` · `promote()` · `giveUp()` |
| 주석 | 한국어. 주요 결정에 `Design Ref: §n`. **왜 그렇게 했는지** | 특히 락 순서·트랜잭션 경계·§4.1 예외 2건 |
| 사용자 참조 | 만든 것 `creator_id` / 자신의 기록 `user_id` | `enrollment.user_id` · `waitlist.user_id` (기존 확정) |

### 8.3 신규 프로퍼티

| 프로퍼티 | 용도 | 값 | 근거 |
|----------|------|-----|------|
| `app.enrollment.default-cancellation-period-days` | `klass.cancellation_period_days` 가 NULL 일 때의 전역 기본값 | **7** | 사용자 결정. 전자상거래법 단순변심 기간과 같은 값이라 직관적이고, 테스트에서 경계 전후를 만들기 쉽다 |
| `app.enrollment.pending-expiry.direct` | 직접 신청 `PENDING` 만료 기한 | **`PT30M`** | ERD §2 ⑥ 확정. 결제 수단 준비 시간 |
| `app.enrollment.pending-expiry.waitlist` | 승격 `PENDING` 만료 기한 | **`PT10M`** | ERD §2 ⑥ 확정. 뒷 순번을 오래 붙잡지 않는다 |
| ~~`app.enrollment.pending-expiry-scan-interval`~~ | ~~만료 스캔 주기~~ | — | **추가하지 않는다** (§2.2 — 만료 처리 제외) |

> **`@ConfigurationPropertiesScan` 이 이미 켜져 있다** (`KlassApplication`). 새 프로퍼티 클래스는
> 그것으로 자동 등록되지만, **이 어노테이션이 없으면 기동이 통째로 실패**한다는 사실은
> CLAUDE.md 가 "건드리면 안 되는 지점" 으로 지목해 뒀다.

---

## 9. 열린 미결 (Design 으로 넘김)

| # | 항목 | 판단이 필요한 이유 |
|:-:|------|--------------------|
| **O-1** | **좌석 관련 서비스를 어떻게 배치할 것인가 (승격 루프 포함)** | 도메인별로 쪼개면 **빈 의존이 순환해 기동이 실패한다.** 아래 §9.1 에 분석과 잠정 결론이 있다 — **이 사이클의 가장 어려운 배치 문제** |
| **O-2** | **`Enrollment` 가 `Klass` 의 취소 정책을 어떻게 읽을 것인가** | `isCancellableAt` 이 `ends_on`·`cancellation_period_days` 를 필요로 한다. ① `Enrollment.klass` 프록시 초기화 ② 파라미터로 주입 ③ 정책을 값 객체(`CancellationPolicy`)로 추출 — 셋의 대가가 다르다 |
| **O-3** | `confirm`/`cancel` 을 전이별 엔드포인트로 나눌지 `PATCH /status` 로 합칠지 | §3.3 각주. 강의 쪽 선례와 갈린다 |
| ~~O-4~~ | ~~서비스 분리 단위~~ | **O-1 에 흡수.** 같은 문제의 다른 표현이었다 |
| **O-5** | 커서 페이지네이션의 커서 규약 | `CursorPageResult` 가 이미 있다. `enrollment` 목록에 그대로 쓸 수 있는지 확인 |
| **O-6** | 대기열 경로 명명 (`/v1/waitlist` vs `/v1/waitlists`) | 기존 경로는 복수형(`/v1/klasses`)이지만 "waitlist" 자체가 집합명이라 복수화가 어색하다 |

### 9.1 O-1 분석 — 순환 의존과 애그리거트 경계

#### 승격 루프가 만지는 것

교차 지점을 정확히 세면 **승격 루프 호출 2곳 + 성격이 다른 대기열 정리 1곳**이다.
(초안은 이 셋을 "승격 3곳"으로 뭉뚱그렸는데, 해법이 달라지므로 구분한다.)

| 지점 | 하는 일 | 소관 |
|------|---------|------|
| **A** 취소 (§4.4 6~9번) | 카운터 감소 → **승격 루프** | `enrollment` |
| **B** 정원 증가 (§4.8 capacity 5번) | 늘어난 좌석만큼 **승격 루프 반복** | `klass` |
| **C** `CLOSED` 전환 (§4.8 5번) | 잔여 `WAITING` 일괄 `CANCELLED`. **승격이 아니다** — `waitlist` UPDATE 하나 | `klass` |

승격 루프 한 번이 만지는 범위 (§4.4 9번):

```
a. SELECT waitlist ... FOR UPDATE          ← waitlist 읽기
d. 적격성: users.is_enabled
        AND 활성 enrollment 부재            ← enrollment 읽기
        AND NOT klass.isOwnedBy(userId)     ← klass 읽기 (FR-19)
e. UPDATE waitlist SET status='PROMOTED'   ← waitlist 쓰기
f. INSERT enrollment (source='WAITLIST')   ← enrollment 쓰기
g. UPDATE klass SET enrollment_count + 1   ← klass 쓰기
```

**세 도메인을 모두 읽고 쓴다.**

#### 순환이 실재한다

각 유스케이스가 불가피하게 갖는 의존:

| 유스케이스 | 의존 | 근거 |
|-----------|------|------|
| 신청·취소 | `enrollment → klass` | `FOR UPDATE` 락 (§4.1) |
| 승격 | `enrollment → waitlist` | §4.4 9번 |
| 대기 등록 | `waitlist → klass` | `FOR UPDATE` 락 (§4.5 1번) |
| 대기 등록 | `waitlist → enrollment` | 활성 신청 중복 검사 (§4.5 3번) |
| `CLOSED`·정원증가 | `klass → waitlist` | §4.8 5번 · capacity 5번 |

```
        ┌──────────────┐
        │    klass     │◀───────┐
        └──┬───────────┘        │ 락 (§4.1)
           │ B, C               │
           ▼                    │
     ┌───────────┐  §4.5 3번  ┌─┴──────────┐
     │ waitlist  │───────────▶│ enrollment │
     └───────────┘◀───────────└────────────┘
                    §4.4 9번 승격
```

**순환 2개** — `waitlist ⇄ enrollment`, `klass ⇄ waitlist`. 문서상 우려가 아니라
**생성자 주입 순환으로 컨텍스트 기동이 실패한다.** `port.in` 인터페이스를 끼워도 빈 그래프의
순환은 그대로다. `@Lazy`·setter 주입으로 뚫는 것은 설계 문제를 도구로 덮는 것이다.

| 시도 | 어디서 깨지는가 |
|------|-----------------|
| 승격을 `enrollment` 서비스에 | `klass → enrollment`(B)가 생겨 `enrollment → klass`(락)와 순환 |
| 승격을 `waitlist` 서비스에 | 취소가 호출해야 하므로 `enrollment → waitlist`, 그런데 §4.5 3번이 `waitlist → enrollment` |
| 승격을 `klass` 서비스에 | 강의가 신청을 만든다. 개념적 역방향이고 순환도 그대로 |
| 도메인 이벤트로 끊기 | `domain` 은 Spring 금지라 서비스에서 발행해야 하고, 같은 트랜잭션·같은 락을 유지하려면 `@TransactionalEventListener(BEFORE_COMMIT)` 가 필요하다. 선례가 없고, **잘못 걸면 락 밖에서 승격이 일어나 그 틈에 신규 신청자가 좌석을 채간다** (§4.4 핵심 성질 2번 위반). 컴파일도 테스트도 통과한다 |

#### 원인 — 애그리거트 경계를 잘못 그었다

ERD §4.1:

> 정원과 관련된 **모든** 트랜잭션이 `klass` 행 락을 **가장 먼저** 잡는다. (…)
> 모든 경합이 이미 `klass` 한 행에서 직렬화되기 때문이다.

**트랜잭션 경계가 곧 애그리거트 경계다.** §4.1 은 세 테이블이 하나의 트랜잭션 경계를
공유한다고 이미 선언했다 — 논리적으로 **좌석 점유는 하나의 애그리거트이고 `klass` 행이
그 루트**다. 테이블이 셋인 것은 물리 모델일 뿐이다.

순환은 이 **하나의 애그리거트를 서비스 계층에서 셋으로 쪼개려 했기 때문에** 생긴다.
패키지가 도메인별 수직 분할이라 서비스도 그래야 한다고 가정한 것이 원인이다.

#### 잠정 결론

**좌석을 건드리는 유스케이스 전부를 하나의 서비스에 모은다.**

```
enrollment/application/service/
└── EnrollmentService    ← 신청 · 결제확정 · 취소 · 승격 · 대기등록 · 대기포기
     의존: KlassQueryPort(락) / KlassCommandPort
           EnrollmentCommandPort / EnrollmentQueryPort
           WaitlistCommandPort / WaitlistQueryPort
```

`waitlist` 패키지에는 **도메인 엔티티 + 어댑터만** 남는다 (서비스 없음). 한 서비스 안에서는
순환이 정의상 불가능하고, §4.4 의 "한 트랜잭션 안에서 끝낸다"가 **메서드 하나로 표현**되어
트랜잭션 경계가 코드 구조와 일치한다.

`klass` 측 잔여 책임 두 건:

- **B (정원 증가 승격) — 구현하지 않는다.** `changeCapacity` 는 `isFullyEditable()` 분기
  안에서만 호출되고 그건 `DRAFT` 에서만 참이다(D-28). `DRAFT` 는 신청·대기가 모두 불가하므로
  (§4.2 2번, §4.5 2번) **승격 대상이 구조적으로 항상 0** 이다. `klass → waitlist` 화살표가
  여기서 사라진다. 지우는 것이 아니라 **왜 불필요해졌는지**를 `Klass.changeCapacity` javadoc 과
  divergence 에 남긴다 — D-21 이 "막을 상대가 없어 락을 걷어냈다"를 남긴 것과 같은 방식이다.
  `OPEN` 에서도 정원 수정을 허용하는 정책으로 바뀌면 구멍이 열리므로 그 조건을 명시한다
- **C (`CLOSED` 시 대기자 정리) — `port.in` 한 개로 위임한다.**

```
KlassService ──▶ CancelRemainingWaitlistUseCase (port.in)
                        △ implements
                 EnrollmentService ──▶ WaitlistCommandPort
```

`KlassService` 는 인터페이스만 안다. `EnrollmentService` 는 `KlassService` 를 참조하지 않고
`KlassQueryPort`/`KlassCommandPort`(포트)만 참조한다 — **서비스와 포트는 서로 다른 빈이므로
순환이 아니다.** 최종 의존 그래프가 DAG 가 된다. 새 패키지도 새 계층 개념도 없다.

#### 이 안의 대가

| 대가 | 내용 |
|------|------|
| **ERD §4.8 capacity 5번 미구현** | 현 호출 경로에서 무효이므로 실동작 영향은 없으나 **정본과 코드가 어긋난다.** Design §12 divergence 등재 + 검증 시나리오 39번을 "현 정책에서 도달 불가"로 표시 |
| 서비스 클래스가 커진다 | 유스케이스 6종이 한 클래스에 들어간다(`KlassService` 는 5종). 완화는 클래스 분할이 아니라 **`private` 메서드로 트랜잭션 단위를 분리**하는 것이다 — 클래스를 쪼개면 순환이 돌아온다 |

#### 구현 시 확인할 함정

위임 호출(`KlassService → CancelRemainingWaitlistUseCase`)의 **`@Transactional` 전파가
`REQUIRED`(기본값)여야 한다.** `REQUIRES_NEW` 로 걸면 부모가 이미 잡은 `klass` 행 락이 남은 채
자식이 새 트랜잭션을 열고, 자식이 같은 행을 만지면 **자기 자신과 락 경합해 타임아웃까지
멈춘다.** 컴파일도 단일 스레드 테스트도 통과하고 부하가 걸릴 때만 드러난다 —
`DomainAuthenticationProvider` 의 검사 순서와 같은 종류의 결합이다.

---

## 10. Next Steps

1. [ ] **Design 문서 작성** — `/pdca design enrollment-management`.
       위 O-1 ~ O-6 을 확정하고, FR-19 · FR-20 을 §12 divergence 로 등재한다
2. [ ] `EnrollmentSchemaTest` 를 `waitlist` 까지 확장해 **구현 전에 먼저 돌린다** (R-06)
3. [ ] M1(도메인 행위) → M2(신청·락) → M3(확정·취소) → M4(대기열) → M5(조회) → M6(klass 연동) → M7(설정·문서)
       순으로 구현. 각 모듈에서 **RestDocs 테스트를 컨트롤러보다 먼저** 쓴다 (R-05)
4. [ ] 완료 보고서에 **R-01(만료 회수 부재)을 다음 사이클 최우선 항목으로 등재**

---

## Version History

| 버전 | 날짜 | 변경 | 작성자 |
|------|------|------|--------|
| 0.1 | 2026-09-02 | 최초 작성. 사용자 확인 사항 8건 반영 (대기열 포함 / 만료 처리 제외 / `ends_on` 기준 취소 차단 / 조회 API 3종 / 개설자 차단 2경로 / PENDING 취소 무제한 / 기본 취소기간 7일) | Chals85 |
| 0.2 | 2026-09-03 | **O-1 분석 추가 (§9.1).** 승격 호출 지점을 "3곳"으로 뭉뚱그린 것을 승격 2곳 + 대기열 정리 1곳으로 정정하고, 빈 의존 순환 2개를 식별. 원인을 애그리거트 경계 오설정으로 규명하고 잠정 결론(좌석 단일 서비스 / capacity 승격 미구현 / `port.in` 위임)을 등재. O-4 를 O-1 에 흡수. §2.1·§4.1·§6.1·§6.2·§7.2 를 결론에 맞춰 정정 | Chals85 |
