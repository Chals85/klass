# 수강신청 · 취소 설계서

> **Summary**: 좌석 점유를 하나의 애그리거트로 보고 유스케이스 10종(명령 6 · 조회 4)을 단일 서비스에 모은다.
> `klass` 행 배타 락이 모든 경합을 직렬화하고, klass-management 에서 걷어낸 락(D-21)이 되살아난다.
>
> **Project**: klass (강의 수강신청 + JWT 인증 백엔드)
> **Version**: 2차 — 수강신청 사이클
> **Author**: Chals85
> **Date**: 2026-09-03
> **Status**: Draft
> **Planning Doc**: [enrollment-management.plan.md](../../01-plan/features/enrollment-management.plan.md)
> **Data Model 정본**: [class-enrollment-erd.design.md](./class-enrollment-erd.design.md)

### 선행 문서 참조

| 문서 | 이 설계서와의 관계 |
|------|--------------------|
| `class-enrollment-erd.design.md` | **데이터 모델·동시성 규약의 정본.** §4 전체가 이 설계의 명세다. 스키마는 이미 확정돼 있고 이 사이클은 **DDL 을 바꾸지 않는다** |
| `archive/2026-09/klass-management/*.design.md` §12 | D-16(대기열 미구현)·**D-21(락 제거)** 의 근거. 되살릴 좌표 3곳 |
| `archive/2026-09/klass-management/*.report.md` §7.2 | 기능을 처음 만들 때만 밟는 함정 6종 |
| `CLAUDE.md` | 컨벤션 · 문서 파이프라인 · 컴파일러가 잡지 못하는 지점 4종 |

---

## Context Anchor

> Plan 문서에서 그대로 옮긴다. Design→Do 인계에서 전략 맥락이 유실되지 않게 한다.

| Key | Value |
|-----|-------|
| **WHY** | 강의는 만들 수 있지만 신청할 수 없다. 좌석 점유·정원 관리·취소 정책이 전부 미구현이고, `enrollment_count` 를 쓰는 코드가 없어 정원 검사가 무의미하다 |
| **WHO** | **수강생**(ROLE_USER) — 신청·결제확정·취소·대기. **크리에이터**(ROLE_CREATOR) — 자기 강의의 수강생 명단 조회. 권한 겸용 사용자는 **자기가 개설한 강의에 신청할 수 없다** |
| **RISK** | ① **PENDING 만료 회수를 만들지 않는다**(사용자 결정) → 미결제 신청이 좌석을 영구 점유. ② 락 획득 순서 위반 시 데드락. ③ H2 의 비관적 락 거동이 실 DB 와 달라 동시성 테스트가 위양성을 낼 수 있다 |
| **SUCCESS** | 잔여 1석에 100건 동시 신청 → 정확히 1건 성공·99건 거부, `enrollment_count == capacity`. 정합성 검증 쿼리(ERD §5.1) 결과가 비어 있다. 신규 엔드포인트 9개 전부 RestDocs 문서에 실린다 |
| **SCOPE** | M1 도메인 행위 → M2 포트·어댑터·락 복원 → M3 서비스 → M4 컨트롤러·문서 → M5 통합 검증 |

---

## 1. Overview

### 1.1 설계 목표

1. **ERD 정본 §4 를 코드로 그대로 옮긴다.** 이 사이클은 새 동시성 규약을 발명하지 않는다.
   정본이 이미 락 순서·검사 순서·상태 전이를 확정해 뒀으므로, 설계의 일은 **그것을 어느 계층에
   어떻게 배치할지**를 정하는 것이다.
2. **트랜잭션 경계를 코드 구조와 일치시킨다.** §4.4 의 "한 트랜잭션 안에서 끝낸다"가 여러
   클래스에 흩어지면 `@Transactional` 전파 하나로 조용히 깨진다.
3. **klass-management 의 부채를 닫는다.** D-21(락)은 구현으로, D-16 중 정원 증가 승격은
   "필요 없음"으로 닫는다.
4. **DDL 을 바꾸지 않는다.** 스키마는 project-setup 에서 확정됐고 이 사이클은 그것을 **처음으로
   실제 사용**할 뿐이다. 제약이 정말 생성됐는지는 `EnrollmentSchemaTest` 가 확인한다.

### 1.2 설계 원칙

- **앱 → DB 제약 순으로 방어를 겹친다.** 앱 검사가 사용자에게 이유를 설명하고, DB 제약이
  앱 버그를 최종 방어한다. 둘 중 하나만 두지 않는다 (ERD §1.2)
- **락 대상은 `klass` 단일 행.** 조인된 행까지 잠기면 §4.1 규약이 깨진다
- **상태 변경은 의도가 드러나는 도메인 메서드로만.** public setter 없음
- **시각은 주입된 `Clock` 만.** 무인자 `now()` 금지 — 취소 기간 경계 테스트가 전적으로 의존한다
- **읽히지 않는 정본 참조를 남기지 않는다.** 부채가 갚히면 그것을 가리키던 javadoc 도 함께 고친다

---

## 2. Architecture Options

### 2.0 아키텍처 비교

Plan §9.1 의 O-1(좌석 관련 서비스 배치)이 이 사이클의 핵심 결정이다. 세 안을 비교했다.

| 기준 | A: 도메인별 분리 | B: 좌석 조정 계층 신설 | **C: 좌석 단일 서비스** |
|------|:---:|:---:|:---:|
| **배치** | `EnrollmentService` + `WaitlistService` 둘로 나누고 교차는 전부 `port.out` | 새 `seat/` 패키지의 `SeatCoordinator` 만 세 도메인 포트를 안다 | `EnrollmentService` 하나가 좌석 유스케이스 10종 전부 |
| **신규 파일** | ~34 | ~42 | **~30** |
| **수정 파일** | 8 | 9 | **8** |
| **패키지 의존** | `enrollment ⇄ waitlist` 양방향 | `seat → 셋` 단방향 | `enrollment → waitlist` 단방향 |
| **복잡도** | 중 | 높음 | **낮음** |
| **유지보수성** | 중 | 높음 | 높음 |
| **§4.4 락 경계** | 승격은 안전. **`CLOSED` 정리가 `KlassService → WaitlistCommandPort` 직접** | 조정자 안에서 완결. 위임마다 전파 확인 필요 | **전부 한 클래스 안.** 전파 리스크가 위임 1건으로 축소 |
| **저장소 관례 부합** | 높음 | **낮음** — 선례 없는 계층, 도메인별 수직 분할과 충돌 | 중 |
| **가장 큰 약점** | `KlassService` 가 남의 도메인 `port.out` 을 직접 참조 — 계층표에 규정이 없는 회색지대 | 개념·분량 비용이 토이 프로젝트에 과함 | 서비스 클래스가 커진다 (6 유스케이스) |

**Selected: Option C** — **Rationale**:

ERD §4.1 이 이미 답을 담고 있다.

> 정원과 관련된 **모든** 트랜잭션이 `klass` 행 락을 **가장 먼저** 잡는다. (…)
> 모든 경합이 이미 `klass` 한 행에서 직렬화되기 때문이다.

**트랜잭션 경계가 곧 애그리거트 경계다.** 정본은 `klass`·`enrollment`·`waitlist` 가 하나의
트랜잭션 경계를 공유한다고 선언했으므로, 논리적으로 **좌석 점유는 하나의 애그리거트이고
`klass` 행이 그 루트**다. 테이블이 셋인 것은 물리 모델일 뿐이다.

B 는 그 사실을 새 계층으로 표현하는데 대가가 크고, A 는 계층표가 답을 주지 않는 자리를
하나 남긴다. C 는 애그리거트를 그대로 서비스 하나에 대응시켜 두 문제를 동시에 없앤다.

> **버려진 근거 하나** — Plan 초안은 "도메인별로 쪼개면 빈 의존이 순환해 **기동이 실패**한다"를
> C 의 근거로 들었다. **이는 과장이었다.** Spring 이 기동을 실패시키는 것은 빈↔빈 생성자 주입
> 순환뿐인데, 교차 지점 대부분은 서비스→서비스가 아니라 **서비스→포트(어댑터 빈)** 라 순환이
> 성립하지 않는다. C 를 택한 진짜 근거는 위의 애그리거트 논거와 락 경계 집중이다.
> (Plan §9.1 도 같은 취지로 정정돼 있다.)

### 2.1 컴포넌트 구조

```
                    adapter.in.web
  ┌──────────────────────┐   ┌────────────────────┐
  │ EnrollmentController │   │ WaitlistController │
  └──────────┬───────────┘   └─────────┬──────────┘
             │  port.in (UseCase 8종)  │
             ▼                         ▼
  ┌─────────────────────────────────────────────────┐
  │           EnrollmentService                     │   application.service
  │  apply / confirm / cancel                       │
  │  registerWaitlist / giveUpWaitlist              │
  │  listMyEnrollments / findEnrollment             │
  │  listKlassEnrollments / listMyWaitlists         │
  │  cancelRemainingWaitlist  ◀── KlassService      │
  │  └ promote()  private  (§4.4 8~9번)             │
  └───┬──────────┬───────────┬──────────┬───────────┘
      │          │           │          │  port.out
      ▼          ▼           ▼          ▼
  KlassQuery  KlassCommand  Enrollment  Waitlist
  Port        Port          *Port       *Port
      │          │           │          │
      ▼          ▼           ▼          ▼
  ┌─────────────────────────────────────────────────┐
  │  KlassRepositoryAdapter │ EnrollmentRepository   │   adapter.out
  │  (락 조회 복원)          │ Adapter │ Waitlist…    │
  └─────────────────────────────────────────────────┘
      │
      ▼
  ┌─────────────────────────────────────────────────┐
  │  Klass · Enrollment · Waitlist · CancellationPolicy │  domain
  └─────────────────────────────────────────────────┘
```

**`waitlist` 패키지에는 서비스가 없다.** 도메인 엔티티와 어댑터만 있다.

### 2.2 의존 방향

| From | To | 종류 | 비고 |
|------|----|----|------|
| `EnrollmentService` | `KlassQueryPort` / `KlassCommandPort` | 포트 | 락 조회 + 카운터 저장 |
| `EnrollmentService` | `Enrollment*Port` / `Waitlist*Port` | 포트 | |
| `EnrollmentService` | `UserQueryPort` | 포트 | 신청자 엔티티 로딩 |
| `KlassService` | `CancelRemainingWaitlistUseCase` | **port.in** | `CLOSED` 전이 시 위임. 유일한 서비스→서비스 의존 |
| `Enrollment` (domain) | `CancellationPolicy` (klass domain) | 도메인 | `Enrollment` 는 이미 `Klass` 를 `@ManyToOne` 으로 참조하므로 새 방향이 아니다 |

**서비스 간 의존은 단 하나**(`KlassService → CancelRemainingWaitlistUseCase`)이고 단방향이다.
`EnrollmentService` 는 `KlassService` 를 참조하지 않는다 — 포트만 참조한다.

### 2.3 데이터 흐름 (수강 신청)

```
POST /v1/klasses/{klassId}/enrollments
  │
  ▼ JwtAuthenticationFilter — typ==ACCESS, jti ∉ blacklist, sub → userId
  ▼ SecurityConfig — anyRequest().authenticated()
  ▼ EnrollmentController — @AuthenticationPrincipal → ApplyEnrollmentCommand
  ▼ EnrollmentService.apply()          @Transactional
        1. klassQueryPort.findWithLockById(klassId)   ← SELECT ... FOR UPDATE
        2. klass.status == OPEN ?
        3. klass.isOwnedBy(userId) ?                  ← FR-19 (신규)
        4. enrollmentQueryPort.existsActive(klassId, userId) ?
        5. klass.occupySeat()                         ← 정원 검사 + 카운터 +1
        6. enrollmentCommandPort.save(Enrollment.apply(...))
     COMMIT ──────────────────────────────────────────  락 해제
  ▼ EnrollmentResponse (201)
```

---

## 3. Data Model

### 3.1 스키마 변경 — **없다**

**이 사이클은 DDL 을 바꾸지 않는다.** 7개 테이블·제약·인덱스는 project-setup 에서 확정됐고
(ERD §3.7), 이번에 처음으로 **실제 쓰인다**. 설계의 일은 그 스키마 위에 도메인 행위를 얹는 것이다.

바뀌지 않지만 이번에 처음 작동하는 것들:

| 스키마 요소 | 지금까지 | 이번 사이클부터 |
|-------------|----------|-----------------|
| `klass.enrollment_count` | 항상 0 (쓰는 코드 없음) | 실제 좌석 점유 수 |
| `ck_klass_count` | 도달 불가 | 카운터 증감의 최종 방어선 |
| `uq_enrollment_active` | 행이 없어 무의미 | 활성 중복 신청 최종 방어선 |
| `enrollment.active_user_key` 생성 컬럼 | 미검증 | 취소 후 재신청 허용의 근거 |
| `waitlist` 전 컬럼 | 미사용 | 대기열 등록·승격·포기 |
| `uq_waitlist_position` | 미검증 | 순번 경합 최종 방어선 |

> **그래서 `EnrollmentSchemaTest` 확장이 구현보다 먼저다.** "선언했다"와 "생성됐다"는 다르고,
> 이 저장소는 과거에 FK 5개가 없는 채로 빌드가 통과한 적이 있다.

### 3.2 도메인 행위 (신규)

#### 3.2.1 `Enrollment`

```java
// 상태 전이
public void confirm(LocalDateTime now)                          // PENDING → CONFIRMED
public void cancel(LocalDateTime now, LocalDate today,
                   CancellationPolicy policy)                    // → CANCELLED

// 판별
public boolean isOwnedBy(Long userId)
public boolean isSeatOccupying()      // PENDING or CONFIRMED
public boolean isCancellableAt(LocalDateTime now, LocalDate today,
                               CancellationPolicy policy)
```

> **`isCancellableAt` 이 있어야 하는 이유**: 응답의 `isCancellable` 필드(D-39)를 채우려면
> 같은 판정이 필요하다. 이 메서드가 없으면 **`cancel()` 안의 2관문 판정과 응답용 판정이
> 서버 안에서 두 번 구현된다** — 클라이언트 복제를 막으려다 서버 복제를 만드는 셈이다.
> `cancel()` 은 이 메서드를 호출하고, 거부 시 어느 관문에 걸렸는지 판별해 예외를 고른다.
>
> **`isExpiredAt(now)` 은 두지 않는다.** 만료 판정은 `confirm(now)` 안에서만 쓰이고 응답에
> 실리지 않으므로, 공개하면 사용처 없는 API 가 된다.

**`confirm(now)`**

```
if (status != PENDING)          → INVALID_ENROLLMENT_STATUS_TRANSITION (409)
if (expiresAt <= now)           → ENROLLMENT_EXPIRED (409)      ← §4.3 4번
status = CONFIRMED; confirmedAt = now; expiresAt = null
```

`expires_at` 을 NULL 로 만드는 것이 **필수다** — `ck_enrollment_pending` 이
"`PENDING` 이 아니면 `expires_at IS NULL`" 을 강제한다. 빠뜨리면 CHECK 위반으로 500 이 난다.

> **`expiresAt <= now` 검사를 도메인에 두는 이유**: §4.3 4번은 "만료 배치가 아직 처리하지
> 않은 PENDING" 을 거부한다. 이 사이클은 만료 배치를 만들지 않으므로(§12 D-32) **이 검사가
> 유일한 만료 방어선**이 된다. 서비스에 두면 다른 호출 경로가 생길 때 빠뜨릴 수 있다.

**`cancel(now, today, policy)`**

```
if (!isSeatOccupying())         → INVALID_ENROLLMENT_STATUS_TRANSITION (409)
if (status == CONFIRMED):                                        ← PENDING 은 면제
    if (policy.isKlassFinished(today))  → KLASS_ALREADY_FINISHED (409)      ← FR-20
    if (!policy.isWithinPeriod(confirmedAt, now))
                                        → CANCELLATION_PERIOD_EXPIRED (409) ← FR-11
status = CANCELLED; cancelledAt = now; expiresAt = null

// isCancellableAt 은 같은 판정을 boolean 으로만 돌려준다
isCancellableAt(now, today, policy)
    = isSeatOccupying()
      && (status == PENDING
          || (!policy.isKlassFinished(today)
              && policy.isWithinPeriod(confirmedAt, now)))
```

**`PENDING` 이 두 관문을 모두 면제받는 이유** (사용자 확정): 결제 전이라 환불할 돈이 없고,
무엇보다 기간 기산점인 `confirmed_at` 이 아직 `NULL` 이다. 억지로 `created_at` 을 기산점으로
쓰면 ERD §4.4 5-b 에서 이탈한다.

**두 관문을 별도 예외로 나누는 이유**: 하나로 합치면 사용자가 "기간이 지난 건지 강의가 끝난
건지" 알 수 없다. 강의가 끝났다면 아무리 빨리 요청해도 성공하지 않으므로 안내가 달라야 한다.

#### 3.2.2 `CancellationPolicy` (신규, `klass/domain`)

O-2 의 해답이다. `Enrollment.cancel` 이 `ends_on` 과 `cancellation_period_days` 를 알아야 하는데,
셋 중 어느 방법을 쓸지가 미결이었다.

| 안 | 방법 | 대가 |
|----|------|------|
| ① | `Enrollment.klass` 프록시를 도메인 안에서 초기화 | 취소 트랜잭션은 `klass` 를 락으로 이미 로딩했으므로 추가 쿼리는 없다. 그러나 **도메인 메서드가 그 사실에 암묵 의존**한다. 다른 경로에서 부르면 조용히 쿼리가 나간다 |
| ② | 원시값 4개를 파라미터로 | `COALESCE(klass 값, 전역 기본)` 로직이 **호출자마다 복제**된다 |
| ③ | **값 객체로 추출** ✅ | 파일 하나가 는다 |

```java
// klass/domain/CancellationPolicy.java
public record CancellationPolicy(LocalDate klassEndsOn, int periodDays) {

    public boolean isKlassFinished(LocalDate today) {
        return today.isAfter(klassEndsOn);
    }

    public boolean isWithinPeriod(LocalDateTime confirmedAt, LocalDateTime now) {
        return !now.isAfter(confirmedAt.plusDays(periodDays));
    }
}

// Klass 에 추가
public CancellationPolicy cancellationPolicy(int defaultPeriodDays) {
    return new CancellationPolicy(
            this.endsOn,
            this.cancellationPeriodDays != null ? this.cancellationPeriodDays : defaultPeriodDays);
}
```

**③ 을 택한 이유**: `COALESCE` 가 `Klass` 안 한 곳에 모이고(정책은 강의의 속성이다),
`Enrollment` 는 프록시를 모르며, 파라미터가 2개로 줄어든다. 무엇보다 **정책 판정 자체를
단위 테스트할 수 있다** — 엔티티 두 개를 만들지 않고 record 하나로.

> **`today` 를 별도 파라미터로 받는 이유**: `now.toLocalDate()` 로 유도하면 편하지만, ERD §2.2 가
> 경고한 "`DATE` 와 현재 시각을 비교하는 지점"이 바로 여기다. 변환의 시간대를 `Clock` 이
> 결정해야 하므로 **서비스가 `LocalDate.now(clock)` 으로 얻은 값을 넘긴다.** 도메인이 스스로
> 변환하면 그 결정이 도메인으로 새어나간다.

#### 3.2.3 `Waitlist`

```java
public void promote(LocalDateTime now)   // WAITING → PROMOTED, promotedAt = now
public void cancel()                     // WAITING → CANCELLED
public boolean isOwnedBy(Long userId)
public boolean isWaiting()
```

`cancel()` 하나로 세 원인(자발적 포기 / 승격 시 부적격 / 강의 마감 정리)을 모두 처리한다 —
ERD §3.3 이 "세 원인은 구분해 저장하지 않는다"고 확정했으므로 메서드를 나눌 근거가 없다.
의미는 호출부가 갖는다.

`cancel()` 은 시각을 받지 않는다. `waitlist` 에 `cancelled_at` 컬럼이 없기 때문이다
(ERD §3.2.7). `promote()` 만 `promoted_at` 을 채운다.

#### 3.2.4 `Klass` — 카운터 증감 (`enrollment_count` 를 쓰는 최초의 코드)

```java
public void occupySeat() {
    if (this.enrollmentCount >= this.capacity) {
        throw KlassError.KLASS_CAPACITY_FULL.toException();     // 409
    }
    this.enrollmentCount++;
}

public void releaseSeat() {
    if (this.enrollmentCount <= 0) {
        throw new IllegalStateException("좌석 점유 수가 0인데 반납이 호출됐다 — 앱 버그");
    }
    this.enrollmentCount--;
}
```

**두 메서드가 `updatedAt` 을 건드리지 않는다.** `updated_at` 은 *크리에이터가 강의 내용을
고친 시각*이다. 남이 신청했다고 강의가 수정된 것은 아니므로, 갱신하면 "최종 수정" 표시가
신청이 들어올 때마다 흔들린다. 이 저장소의 다른 `change*` 메서드가 전부 `now` 를 받는 것과
대비되는 유일한 예외이므로 javadoc 에 명시한다.

**예외 종류가 다른 이유**: 정원 초과는 사용자에게 설명할 수 있으므로 `BusinessException`(409).
카운터가 0인데 감소는 **설명할 것이 없는 내부 불일치**이므로 `IllegalStateException` → 500.
`GlobalExceptionControllerAdvice` 가 `INTERNAL_ERROR` 로 잡고 원인은 로그에만 남는다.

### 3.3 상태 전이 (정본 §3.4 인용)

| From \ To | PENDING | CONFIRMED | CANCELLED |
|---|:-:|:-:|:-:|
| **PENDING** | — | ⚠️ `expires_at > now` | ✅ **무조건** (기간·종료일 면제) |
| **CONFIRMED** | ❌ | — | ⚠️ 기간 내 **AND** `today <= ends_on` |
| **CANCELLED** | ❌ 종착 | ❌ 종착 | — |

| From \ To | WAITING | PROMOTED | CANCELLED |
|---|:-:|:-:|:-:|
| **WAITING** | — | ✅ 승격 | ✅ 포기 / 부적격 / 강의 마감 |
| **PROMOTED** | ❌ 종착 | — | ❌ 종착 |
| **CANCELLED** | ❌ 종착 | ❌ | — |

`CONFIRMED → CANCELLED` 의 조건 두 개가 정본 대비 하나 늘었다 (`today <= ends_on`, §12 D-31).

---

## 4. 동시성 설계

### 4.1 락 규약 (정본 §4.1 인용)

```
klass → enrollment → waitlist
```

정원과 관련된 **모든** 트랜잭션이 `klass` 행 락을 가장 먼저 잡는다. 그 뒤 순서는 무해하다.

**정본이 명시한 예외 2건은 그대로 유지한다.**

| 유스케이스 | 잡는 락 | `klass` 락을 잡지 않는 근거 |
|-----------|---------|------------------------------|
| 결제 확정 (§4.3) | `enrollment` 단독 | PENDING 이 이미 좌석을 점유해 `enrollment_count` 가 변하지 않는다 |
| 대기 포기 (§4.9) | `waitlist` 단독 | 카운터를 건드리지 않는다. 인기 강의에서 신청 트랜잭션과 직렬화되는 비용을 피한다 |

두 경우 모두 **락을 하나 잡은 뒤 아무것도 더 잡지 않으므로** 순환 대기가 성립하지 않는다.

### 4.1.1 스파이크 판정 결과 (2026-09-03, 실측)

> 아래 5건은 설계서가 **주장만 하고 확인한 적 없던 것**이다. module-2 착수 전에
> `src/test/java/com/toby/klass/spike/` 에서 판정했다 — project-setup 사이클이
> QueryDSL·생성컬럼·`@Check` 3종을 같은 방식으로 판정하고 `STORED` 제거를 설계에 반영한
> 선례를 따른다. **13건 전부 통과.**

| # | 주장 | 판정 | 실측 |
|:-:|------|:----:|------|
| ① | `findWithLockById` 가 파생 쿼리로 해석되고 `FOR UPDATE` 를 만든다 | ✅ | 아래 SQL |
| ② | `@EntityGraph` 없이 선언하면 `users` 를 조인하지 않는다 | ✅ | `from klass k1_0 where k1_0.id=?` — **조인 없음** |
| ③ | H2 2.4.240 이 `ORDER BY … 1건 … FOR UPDATE` 를 거부하지 않는다 (R-04) | ✅ | 아래 SQL. **`LIMIT` 이 아니라 `FETCH FIRST ? ROWS ONLY`** 로 나간다 |
| ④ | `findByIdForUpdate` 는 속성 경로로 해석돼 깨진다 | ✅ | `PropertyReferenceException` |
| ⑤ | `record` + `@ConfigurationProperties` 중첩 바인딩이 된다 | ✅ | `PT30M` → `Duration.ofMinutes(30)` |

**실제로 생성된 SQL** (`SqlCapture` StatementInspector 로 가로챈 것)

```sql
-- ① klass 단건 락 조회
select k1_0.id, k1_0.cancellation_period_days, k1_0.capacity, k1_0.created_at,
       k1_0.creator_id, k1_0.description, k1_0.ends_on, k1_0.enrollment_count,
       k1_0.price, k1_0.starts_on, k1_0.status, k1_0.title, k1_0.updated_at
  from klass k1_0
 where k1_0.id=?
   for update

-- ② 승격 대상 1건 락 조회
select w1_0.id, w1_0.created_at, w1_0.klass_id, w1_0.position, w1_0.promoted_at,
       w1_0.status, w1_0.user_id, w1_0.waiting_user_key
  from waitlist w1_0
 where w1_0.klass_id=? and w1_0.status=? and w1_0.position>?
 order by w1_0.position
 fetch first ? rows only
   for update
```

**여기서 새로 알게 된 것 4가지**

1. **`FOR UPDATE` + `FETCH FIRST 1 ROW` 조합에는 잘 알려진 함정이 있다** — 대상 행이 다른
   트랜잭션에 잠겨 있으면 대기했다가 **낡은 행을 돌려줄 수 있다.** 큐 패턴에서 보통
   `FOR UPDATE SKIP LOCKED` 를 권하는 이유다. **이 설계에서는 문제가 되지 않는다** —
   §4.1 규약상 승격은 항상 `klass` 락 하위에서만 실행되므로 같은 강의에 대해 두 트랜잭션이
   동시에 승격 대상을 고르는 상황 자체가 성립하지 않는다. **`klass` 락을 빼면 이 함정이
   즉시 열린다**는 뜻이므로, 최적화를 이유로 락을 걷어내려 할 때 이 문단을 먼저 읽어야 한다.
2. **`position` 이 SQL 예약어인데 별칭(`w1_0.`)이 붙어 인용 없이도 안전하다.** `Waitlist`
   javadoc 은 DDL 생성만 확인했다고 적었는데, 파생 쿼리 경로도 통과한다.
3. **Spring Data 4 에서 `PropertyReferenceException` 이 이동했다** —
   `org.springframework.data.mapping` → **`org.springframework.data.core`**.
   CLAUDE.md 가 모아 둔 Boot 4 이동 목록과 같은 종류이며, 테스트에서 이 예외를 잡으려면
   새 경로를 써야 한다.
4. **프로퍼티 블록이 없으면 중첩 `record` 는 `null` 이다** — 예외가 아니라 `null` 이다.
   `app.enrollment.pending-expiry` 를 `application.yml` 에 빠뜨리면 **기동은 성공하고
   첫 신청에서 NPE 가 난다.** §5.1 의 값 3종을 반드시 넣어야 하는 이유이며, module-3
   완료 조건에 이 확인이 들어간다.

### 4.2 락 조회 복원 (D-21 해소)

```java
// KlassJpaRepository — 신규
@Lock(LockModeType.PESSIMISTIC_WRITE)
Optional<Klass> findWithLockById(Long id);
```

**세 가지를 지켜야 한다.**

1. **이름은 `findWithLockById`.** Spring Data 는 `find` 와 `By` 사이를 설명용으로 보고 무시하므로
   `WithLock` 은 사람에게 보내는 이름표로만 남는다. `findByIdForUpdate` 로 지으면 `ForUpdate` 가
   `By` **뒤**, 즉 속성 경로 자리에 놓여 `id.forUpdate` 를 찾다가 **부트스트랩에서 깨진다.**
2. **`@EntityGraph` 를 붙이지 않는다.** 조인된 `users` 행까지 잠겨 §4.1 의 "락 대상은 `klass`
   단일 행" 규약이 깨진다. `findWithCreatorById` 를 따로 둔 원래 판단이 여기서 값을 한다.
3. **`findById` 를 오버라이드하지 않는다.** 조회 경로까지 락이 걸리면 강의 목록 조회가
   신청 트랜잭션과 직렬화된다.

### 4.3 유스케이스별 트랜잭션

#### ① 수강 신청 (ERD §4.2 + FR-19)

```
@Transactional
  1. klass := klassQueryPort.findWithLockById(klassId)
        └ 없으면 KLASS_NOT_FOUND (404)
  2. IF klass.status != OPEN                → KLASS_NOT_OPEN (409)
  3. IF klass.isOwnedBy(userId)             → SELF_ENROLLMENT_FORBIDDEN (403)   ★ FR-19
  4. IF enrollmentQueryPort.existsActive(klassId, userId)
                                            → DUPLICATE_ENROLLMENT (409)
  5. klass.occupySeat()                     → 정원 초과면 KLASS_CAPACITY_FULL (409)
  6. enrollmentCommandPort.save(
         Enrollment.apply(klass, user, DIRECT, now, now + pendingExpiry.direct))
COMMIT
```

- **2번 이전에 가시성 검사가 없는 이유**: `DRAFT` 는 `status != OPEN` 에서 함께 걸러진다.
  강의 조회처럼 404 로 감출 필요가 없다 — 이 경로는 인증이 필수라 존재 자체를 숨기는 이득이
  없고, `DRAFT` 임을 알려도 개설자 외에는 아무것도 할 수 없다
- **3번의 위치**: 중복 검사(4번)보다 **앞**이다. 개설자는 애초에 신청 자격이 없으므로
  "중복 신청" 이라는 엉뚱한 메시지가 나가면 안 된다
- **5번이 검사와 갱신을 함께 하는 이유**: 정본 §4.2 는 4번(검사)과 6번(갱신)을 나눴지만,
  둘 사이에 다른 코드가 끼면 검사가 무의미해진다. 도메인 메서드 하나로 묶으면 그 틈이 없다
- **4번을 두는 이유**: `uq_enrollment_active` 가 최종 방어하지만, 제약 위반 예외를 잡아 409 로
  바꾸는 것보다 명시적 검사가 읽힌다 (ERD §1.2 이중 방어)

#### ② 결제 확정 (ERD §4.3)

```
@Transactional
  1. enrollment := enrollmentQueryPort.findWithLockById(id)
        └ 없으면 ENROLLMENT_NOT_FOUND (404)
  2. IF !enrollment.isOwnedBy(userId)       → NOT_ENROLLMENT_OWNER (403)
  3. enrollment.confirm(now)
        ├ PENDING 아니면 INVALID_ENROLLMENT_STATUS_TRANSITION (409)
        └ expires_at <= now 면 ENROLLMENT_EXPIRED (409)
COMMIT
```

`klass` 락을 잡지 않는다 (§4.1 예외 1). 카운터가 변하지 않기 때문이다.

#### ③ 수강 취소 + 승격 (ERD §4.4 + FR-20)

```
@Transactional
  0. klassId := enrollmentQueryPort.findKlassIdById(id)      -- 무락 조회
        └ §4.1 규약상 klass 를 먼저 락해야 하므로 락 전에 소속 강의를 알아낸다
  1. klass      := klassQueryPort.findWithLockById(klassId)
  2. enrollment := enrollmentQueryPort.findWithLockById(id)
  3. IF !enrollment.isOwnedBy(userId)       → NOT_ENROLLMENT_OWNER (403)
  4. enrollment.cancel(now, today, klass.cancellationPolicy(defaultDays))
        ├ 이미 종착이면 INVALID_ENROLLMENT_STATUS_TRANSITION (409)
        ├ today > ends_on 이면 KLASS_ALREADY_FINISHED (409)          ★ FR-20
        └ 기간 초과면 CANCELLATION_PERIOD_EXPIRED (409)
  5. klass.releaseSeat()
  6. promote(klass, now)                    -- 아래 ④
COMMIT
```

- **0번이 필요한 이유**: 락 순서가 `klass` 먼저인데, 어느 강의인지는 `enrollment` 를 봐야 안다.
  무락으로 `klass_id` 만 읽어 순서를 지킨다
- 정본 §4.4 3번(`enrollment.klass_id != klassId` 검사)은 **생략한다.** `klassId` 를 외부 입력으로
  받지 않고 0번에서 스스로 구하므로 어긋날 경로가 없다 (§12 D-34)
- **4번과 5번의 순서**: 취소가 거부되면 카운터를 건드리면 안 되므로 검사가 먼저다

#### ④ 승격 루프 — `private` (ERD §4.4 8~9번)

```
private void promote(Klass klass, LocalDateTime now):
  IF klass.status != OPEN → return                   -- §2.1 승격 중단
  lastPos := 0
  LOOP:
    w := waitlistQueryPort.findNextWaitingWithLock(klass.id, lastPos)
    IF 없음 → return                                  -- 좌석은 빈 채로 남는다
    lastPos := w.position
    IF !(w.user.isEnabled()
         && !enrollmentQueryPort.existsActive(klass.id, w.user.id)
         && !klass.isOwnedBy(w.user.id))              -- ★ FR-19 세 번째 지점
        w.cancel()                                    -- 부적격, 건너뛴다
        CONTINUE
    w.promote(now)
    enrollmentCommandPort.save(
        Enrollment.apply(klass, w.user, WAITLIST, now, now + pendingExpiry.waitlist))
    klass.occupySeat()
    return                                            -- 1건만 승격
```

**핵심 성질 (정본 §4.4)**

1. **승격 시 순변화 0** — `releaseSeat()` 와 `occupySeat()` 가 상쇄된다. 반납된 좌석이
   일반 신청자에게 노출되는 틈 없이 대기자에게 이전된다
2. **같은 트랜잭션·같은 락 안에서 끝낸다** — 락을 놓고 승격하면 그 틈에 일반 신청자가
   좌석을 채간다. **`private` 메서드로 둔 이유가 이것이다** — 별 빈으로 두면 `@Transactional`
   전파 하나로 이 성질이 조용히 깨진다
3. **`OPEN` 에서만 일어난다** — `CLOSED` 에서 반납된 좌석은 빈 채로 남는다 (정본 §2.1)

**FR-19 를 승격 적격성 검사에 넣는 이유**: 신청·대기 등록만 막으면 우회로가 남는다. 개설자가
자기 강의 대기열에 등록되어 있으면(과거 데이터 또는 미래의 경로 추가) 자리가 나는 순간
승격이 `PENDING` 을 만들어 준다 — 신청 API 를 거치지 않고 좌석을 점유하게 된다.

#### ⑤ 대기열 등록 (ERD §4.5 + FR-19)

```
@Transactional
  1. klass := klassQueryPort.findWithLockById(klassId)
        └ 없으면 KLASS_NOT_FOUND (404)
  2. IF klass.status != OPEN                → KLASS_NOT_OPEN (409)
  3. IF klass.isOwnedBy(userId)             → SELF_ENROLLMENT_FORBIDDEN (403)   ★ FR-19
  4. IF enrollmentQueryPort.existsActive(klassId, userId)
                                            → DUPLICATE_ENROLLMENT (409)
  5. IF waitlistQueryPort.existsWaiting(klassId, userId)
                                            → DUPLICATE_WAITLIST (409)
  6. IF klass.hasSeat()                     → WAITLIST_SEAT_AVAILABLE (409)
  7. next := waitlistQueryPort.maxPosition(klassId) + 1
  8. waitlistCommandPort.save(Waitlist.enqueue(klass, user, next, now))
COMMIT
```

- **4번이 필수인 이유** (정본 §4.5 주의): `uq_enrollment_active` 는 `enrollment` INSERT 에만
  작동하고 `uq_waitlist_waiting` 은 중복 *대기*만 막는다. 4번이 없으면 **이미 `CONFIRMED` 인
  사용자가 대기열에 등록**되어 순번을 차지하고, 승격 시 부적격으로 걸러진다
- **6번이 필요한 이유**: 자리가 있는데 대기열에 넣으면 좌석 반납이 일어날 때까지 승격되지
  않아 사용자가 영구히 기다린다. `Klass.hasSeat()` 판별 메서드를 추가한다
- **`MAX(position)+1` 이 안전한 이유**: `klass` 락 하위에서만 실행된다. 그래도
  `uq_waitlist_position` 이 최종 방어한다
- 취소된 대기 행의 순번은 **재사용하지 않고 gap 으로 남긴다** (정본 §4.5)

#### ⑥ 대기 포기 (ERD §4.9)

```
@Transactional
  1. w := waitlistQueryPort.findWithLockById(id)     -- waitlist 단독 락 (§4.1 예외 2)
        └ 없으면 WAITLIST_NOT_FOUND (404)
  2. IF !w.isOwnedBy(userId)                → NOT_WAITLIST_OWNER (403)
  3. IF !w.isWaiting()                      → WAITLIST_NOT_WAITING (409)
  4. w.cancel()
COMMIT
```

3번의 상태 재확인이 승격 트랜잭션과의 경합을 막는다. 승격이 먼저 커밋되면 이 트랜잭션은
`PROMOTED` 를 보고 거부하며, 사용자는 "이미 자리가 배정되었습니다"를 안내받는다.

#### ⑦ 강의 마감 시 대기자 정리 (ERD §4.8 5번) — `KlassService` 위임

```java
// enrollment/application/port/in/CancelRemainingWaitlistUseCase.java
public interface CancelRemainingWaitlistUseCase {
    void cancelRemainingWaitlist(Long klassId);
}
```

```
KlassService.changeStatus()  @Transactional
  ├ ... 기존 전이 로직 ...
  └ IF next == CLOSED:
        cancelRemainingWaitlistUseCase.cancelRemainingWaitlist(klassId)
              └ EnrollmentService  @Transactional(REQUIRED)   ← 같은 트랜잭션
                    waitlistQueryPort.findAllWaiting(klassId).forEach(Waitlist::cancel)
```

**`@Transactional` 전파가 `REQUIRED`(기본값)여야 한다.** `REQUIRES_NEW` 로 걸면 `KlassService`
가 이미 잡은 `klass` 행 락이 부모 트랜잭션에 남은 채 자식이 새 트랜잭션을 열고, 자식이 같은
행을 만지려 하면 **자기 자신과 락 경합해 타임아웃까지 멈춘다.** 컴파일도 단일 스레드 테스트도
통과하고 부하가 걸릴 때만 드러난다 — `DomainAuthenticationProvider` 의 검사 순서와 같은 종류의
결합이다.

**벌크 UPDATE(JPQL)를 쓰지 않는 이유**: JPQL 문자열은 CLAUDE.md 가 지목한 "컴파일러가 잡지
못하는 지점" 1번이고, 부트스트랩에서 앱이 통째로 안 뜬다. 대기자 수가 정원 규모를 넘지
않으므로 조회 후 도메인 메서드 반복이 충분하다.

### 4.3.1 트랜잭션 속성

`KlassService` 의 선례를 따른다 — **클래스 레벨 `@Transactional(readOnly = true)`, 쓰기
유스케이스에만 메서드 레벨 `@Transactional`**.

```java
@Service
@Transactional(readOnly = true)          // 조회 4종이 기본값을 받는다
public class EnrollmentService implements ... {

    @Transactional                        // 명령 6종 + 위임 1종에만 붙인다
    public EnrollmentResult apply(...) { ... }
```

**전파는 명시하지 않는다** — 기본값 `REQUIRED` 가 정답이고, 명시하면 나중에 누군가
"명시돼 있으니 바꿔도 되겠지"로 읽는다. 왜 `REQUIRES_NEW` 가 안 되는지는 §4.3 ⑦ 에 적혀 있다.

**`readOnly = true` 가 조회에서 갖는 의미**: Hibernate 가 flush 를 건너뛰고 더티 체킹을
하지 않는다. 조회 경로에서 실수로 도메인 메서드를 불러도 **DB 에 반영되지 않는다** — 승격
루프처럼 상태를 바꾸는 코드가 같은 클래스에 있으므로 이 방어가 값을 한다.

### 4.4 락 획득 순서 검증표

| 유스케이스 | 1번째 | 2번째 | 3번째 | 규약 준수 |
|-----------|-------|-------|-------|:---------:|
| 신청 | `klass` | — | — | ✅ |
| 결제 확정 | `enrollment` | — | — | ✅ 예외 1 |
| 취소 (+승격) | `klass` | `enrollment` | `waitlist` | ✅ |
| 대기 등록 | `klass` | — | — | ✅ |
| 대기 포기 | `waitlist` | — | — | ✅ 예외 2 |
| 강의 상태 전이 (+정리) | `klass` | `waitlist` | — | ✅ |
| 강의 수정 | `klass` | — | — | ✅ (D-21 복원) |

---

## 5. 설정

### 5.1 신규 프로퍼티

```yaml
app:
  enrollment:
    # klass.cancellation_period_days 가 NULL 일 때의 전역 기본값
    default-cancellation-period-days: 7
    pending-expiry:
      direct: PT30M      # 직접 신청 — 결제 수단 준비 시간
      waitlist: PT10M    # 승격 — 뒷 순번을 오래 붙잡지 않는다
```

`pending-expiry-scan-interval` 은 **추가하지 않는다** (만료 처리 미구현, §12 D-32).

### 5.2 배치 위치

```java
// enrollment/application/EnrollmentProperties.java
@ConfigurationProperties(prefix = "app.enrollment")
public record EnrollmentProperties(
        int defaultCancellationPeriodDays,
        PendingExpiry pendingExpiry) {

    public record PendingExpiry(Duration direct, Duration waitlist) { }
}
```

**`infrastructure` 가 아니라 `application` 에 두는 이유**: 서비스가 직접 소비한다.
`infrastructure/config` 에 두면 `application.service → infrastructure` 라는 계층 역행이 생긴다.
`application.service` 는 이미 `@Service`·`@Transactional` 로 Spring 을 알고 있으므로
`@ConfigurationProperties` 를 얹는 것이 새 위반은 아니다.

> `@ConfigurationPropertiesScan` 이 `KlassApplication` 에 이미 있어 자동 등록된다.
> **이 어노테이션이 없으면 기동이 통째로 실패한다** — `@SpringBootApplication` 은 이 스캔을
> 포함하지 않는다 (CLAUDE.md "건드리면 안 되는 지점").

---

## 6. API 명세

### 6.1 엔드포인트 목록 (신규 9개)

| # | Method | Path | 설명 | 권한 |
|:-:|--------|------|------|------|
| 1 | `POST` | `/v1/klasses/{klassId}/enrollments` | 수강 신청 | 인증. **개설자 아님** |
| 2 | `GET` | `/v1/klasses/{klassId}/enrollments` | 강의별 수강생 목록 | `ROLE_CREATOR` **AND** 소유권 |
| 3 | `GET` | `/v1/enrollments/me` | 내 신청 목록 | 인증 |
| 4 | `GET` | `/v1/enrollments/{id}` | 신청 상세 | 인증 **AND** 본인 |
| 5 | `POST` | `/v1/enrollments/{id}/confirm` | 결제 완료 처리 | 인증 **AND** 본인 |
| 6 | `POST` | `/v1/enrollments/{id}/cancel` | 수강 취소 | 인증 **AND** 본인 |
| 7 | `POST` | `/v1/klasses/{klassId}/waitlists` | 대기열 등록 | 인증. **개설자 아님** |
| 8 | `GET` | `/v1/waitlists/me` | 내 대기 목록 | 인증 |
| 9 | `POST` | `/v1/waitlists/{id}/cancel` | 대기 포기 | 인증 **AND** 본인 |

path 8개 / operation 9개 추가 → **기존 8 path·10 op → 16 path·19 op.**

### 6.2 설계 결정

**전이별 엔드포인트로 나눈다** (`POST /confirm`, `POST /cancel`) — O-3 해결.

강의는 `PATCH /v1/klasses/{id}/status` 하나로 `publish`/`close` 를 받았다. 그쪽은 전이별 검증이
"화이트리스트 조회" 한 가지로 균질했다. `enrollment` 는 다르다.

| | `confirm` | `cancel` |
|--|-----------|----------|
| 검증 | 만료 시각 | 취소 기간 + 종료일 |
| 락 범위 | `enrollment` 단독 | `klass` + `enrollment` + `waitlist` |
| 부수 효과 | 없음 | 카운터 감소 + 승격 |

한 엔드포인트에 담으면 이 분기가 컨트롤러로 새어나온다.

> **부수 근거**: `SecurityConfig` 가 이미 `PATCH /v1/klasses/**` 를 `ROLE_CREATOR` 로 잠가 뒀다.
> 상태 전이를 `PATCH` 로 통일하려다 경로를 `/v1/klasses/**` 아래로 두면 **수강생이 자기 신청을
> 확정하지 못한다.** 지금 설계는 `/v1/enrollments/**` 라 걸리지 않지만, 경로와 메서드 선택이
> 이미 걸려 있는 보안 규칙과 상호작용한다는 점은 기록해 둔다.

**대기열 경로는 복수형 `/v1/waitlists`** — O-6 해결. `/v1/klasses`·`/v1/enrollments` 와 맞춘다.
생성되는 자원이 "대기열"이 아니라 **대기 등록 항목**이므로 복수형이 어색하지 않다.

**중첩 경로의 path 변수는 `{klassId}`** — 기존 단일 경로는 `{id}` 지만, 중첩에서 `{id}` 를 쓰면
RestDocs `pathParameters` 문서에서 무엇의 id 인지 읽히지 않는다.

### 6.3 요청·응답

응답은 기존 `ApiResponse<T>` 규격을 그대로 쓴다. 목록은 `CursorPageResponse<T>`.

#### `POST /v1/klasses/{klassId}/enrollments`

요청 본문 없음.

```json
// 201 Created
{
  "data": {
    "id": 42,
    "klassId": 7,
    "klassTitle": "스프링 부트 입문",
    "status": "PENDING",
    "source": "DIRECT",
    "createdAt": "2026-09-03T10:00:00",
    "expiresAt": "2026-09-03T10:30:00",
    "confirmedAt": null,
    "cancelledAt": null,
    "isCancellable": true
  }
}
```

**`isCancellable` 를 응답에 담는 이유**: 클라이언트가 취소 버튼을 보일지 판단하려면 취소
가능 기간과 강의 종료일을 스스로 계산해야 하는데, 그러면 **판정 로직이 서버와 클라이언트
양쪽에 복제**된다. 서버가 이미 아는 답을 실어 보낸다. boolean 이므로 `is` 접두어를 붙인다.

#### `POST /v1/enrollments/{id}/confirm` / `cancel`

요청 본문 없음. 응답은 위와 같은 `EnrollmentResponse` (200).

#### `GET /v1/enrollments/me`

| 파라미터 | 타입 | 기본 | 설명 |
|----------|------|------|------|
| `cursor` | Long | — | 이전 응답의 `nextCursor` |
| `size` | int | 20 | 1~100 |
| `status` | enum | — | `PENDING`/`CONFIRMED`/`CANCELLED` 필터 (선택) |

```json
// 200 OK
{ "data": { "items": [ /* EnrollmentSummaryResponse */ ],
            "hasNext": true, "nextCursor": 31 } }
```

**`EnrollmentSummaryResponse`** — 목록 항목

| 필드 | 타입 | 비고 |
|------|------|------|
| `id` | Long | 커서 값이기도 하다 |
| `klassId` | Long | |
| `klassTitle` | String | **`klass` 조인 필요** |
| `status` | enum | |
| `source` | enum | |
| `createdAt` | LocalDateTime | |
| `expiresAt` | LocalDateTime | `PENDING` 이 아니면 null |
| `isCancellable` | boolean | **`klass.endsOn`·`cancellationPeriodDays` 필요** |

> ⚠️ **이 두 필드가 fetch join 을 강제한다.** `klassTitle` 과 `isCancellable` 이 모두
> `Enrollment.klass`(`LAZY`)를 필요로 하므로, 목록 조회에서 **반드시 `klass` 를 fetch join**
> 해야 한다. 하지 않으면 20건 페이지에서 21번의 쿼리가 나가고, `open-in-view: false` 라
> 컨트롤러 직렬화 시점에 `LazyInitializationException` 으로 **즉시 실패**한다.
> R-07 의 구체적 발현 지점이 여기다.

#### `GET /v1/enrollments/{id}`

`EnrollmentResponse` 와 동일 (200).

#### `GET /v1/klasses/{klassId}/enrollments`

같은 커서 규격. 파라미터는 `cursor`·`size`·`status`.

**`KlassEnrollmentResponse`** — 크리에이터가 보는 명단 항목

| 필드 | 타입 | 비고 |
|------|------|------|
| `id` | Long | 커서 값 |
| `userId` | Long | **크리에이터 전용이므로 수강생 정보가 실린다** |
| `username` | String | **`user` 조인 필요** |
| `status` | enum | |
| `source` | enum | |
| `createdAt` | LocalDateTime | |
| `confirmedAt` | LocalDateTime | 확정 전이면 null |

> `klassTitle` 을 넣지 않는다 — 경로에 `klassId` 가 이미 있어 어느 강의인지 자명하다.
> `isCancellable` 도 넣지 않는다 — 취소 권한은 수강생에게 있지 크리에이터에게 없다.
> 대신 `user` 를 fetch join 해야 한다.

#### `POST /v1/klasses/{klassId}/waitlists`

```json
// 201 Created
{ "data": { "id": 5, "klassId": 7, "position": 3,
            "status": "WAITING", "createdAt": "2026-09-03T10:00:00",
            "promotedAt": null } }
```

#### `GET /v1/waitlists/me`

같은 커서 규격. 파라미터는 `cursor`·`size`.

**`WaitlistResponse`** — 등록 응답과 목록 항목이 같은 DTO 를 쓴다

| 필드 | 타입 | 비고 |
|------|------|------|
| `id` | Long | 커서 값. **대기 포기 API 의 경로 변수** |
| `klassId` | Long | |
| `klassTitle` | String | **`klass` 조인 필요** |
| `position` | int | |
| `status` | enum | |
| `createdAt` | LocalDateTime | |
| `promotedAt` | LocalDateTime | `PROMOTED` 가 아니면 null |

> 등록 응답(201)과 목록 항목이 같은 필드를 갖는다. 나눌 근거가 없어 하나로 둔다.
> **`klassTitle` 때문에 목록에서 `klass` fetch join 이 필요**한 것은 위와 같다.

---

## 7. 에러 처리

### 7.0 배치 규칙

상수명이 그대로 API 응답의 `error.code` 가 되고 **응답에 enum 타입 정보가 실리지 않으므로**,
다른 `*Error` enum 과 이름이 겹치면 안 된다. 기존 4개 enum(`KlassError`·`CommonError`·
`UserError`·`AuthError`)과 대조해 **중복 0건**을 확인했다.

어느 enum 에 넣을지는 아래 한 문장으로 정한다.

> **판정의 주어가 무엇인가로 가른다.**
> `klass` 자신의 불변식(정원·기간·정원 축소)이면 `KlassError`,
> **신청·대기라는 행위**의 성립 여부이면 `EnrollmentError` / `WaitlistError`.

이 규칙으로 갈리는 경계 사례:

| 코드 | 배치 | 근거 |
|------|------|------|
| `KLASS_CAPACITY_FULL` | `KlassError` | `enrollment_count <= capacity` 는 **강의의 불변식**이다. `Klass.occupySeat()` 가 던진다 |
| `KLASS_NOT_OPEN` | `EnrollmentError` | 강의 상태는 정상이다. **"이 상태에서는 신청이 성립하지 않는다"** 는 신청 쪽 판정이다 |
| `KLASS_ALREADY_FINISHED` | `EnrollmentError` | 같음 — 강의가 끝난 것은 정상이고, 취소가 성립하지 않을 뿐이다 |

### 7.1 `EnrollmentError` (신규 10건)

| 코드 | 상태 | 메시지 | 발생 지점 |
|------|:----:|--------|-----------|
| `ENROLLMENT_NOT_FOUND` | 404 | 수강 신청을 찾을 수 없습니다 | ②③④ |
| `NOT_ENROLLMENT_OWNER` | 403 | 본인의 수강 신청만 관리할 수 있습니다 | ②③, 상세 조회 |
| `SELF_ENROLLMENT_FORBIDDEN` | 403 | 본인이 개설한 강의는 신청할 수 없습니다 | ①⑤ (FR-19) |
| `KLASS_NOT_OPEN` | 409 | 모집 중인 강의가 아닙니다 | ①⑤ |
| `DUPLICATE_ENROLLMENT` | 409 | 이미 신청한 강의입니다 | ①⑤ |
| `INVALID_ENROLLMENT_STATUS_TRANSITION` | 409 | 허용되지 않는 상태 변경입니다 | ②③ |
| `ENROLLMENT_EXPIRED` | 409 | 결제 기한이 지난 신청입니다 | ② |
| `CANCELLATION_PERIOD_EXPIRED` | 409 | 취소 가능 기간이 지났습니다 | ③ (FR-11) |
| `KLASS_ALREADY_FINISHED` | 409 | 종료된 강의는 취소할 수 없습니다 | ③ (FR-20) |
| `INVALID_ENROLLMENT_PAGE_SIZE` | 400 | 조회 개수는 1 이상 100 이하여야 합니다 | 목록 |

### 7.2 `WaitlistError` (신규 5건)

| 코드 | 상태 | 메시지 | 발생 지점 |
|------|:----:|--------|-----------|
| `WAITLIST_NOT_FOUND` | 404 | 대기 내역을 찾을 수 없습니다 | ⑥ |
| `NOT_WAITLIST_OWNER` | 403 | 본인의 대기 내역만 관리할 수 있습니다 | ⑥ |
| `DUPLICATE_WAITLIST` | 409 | 이미 대기 중인 강의입니다 | ⑤ |
| `WAITLIST_SEAT_AVAILABLE` | 409 | 자리가 있습니다. 바로 신청하세요 | ⑤ |
| `WAITLIST_NOT_WAITING` | 409 | 이미 자리가 배정되었거나 포기한 대기입니다 | ⑥ |

> `WAITLIST_SEAT_AVAILABLE` 의 접두어는 필수다. `SEAT_AVAILABLE` 로 두면 도메인 접두어
> 규약(`KlassError` 클래스 javadoc)에서 이탈하는 유일한 코드가 된다.

### 7.3 `KlassError` 추가 1건

| 코드 | 상태 | 메시지 | 근거 |
|------|:----:|--------|------|
| `KLASS_CAPACITY_FULL` | 409 | 정원이 모두 찼습니다 | §7.0 규칙 — 강의 자신의 불변식. 기존 `CAPACITY_BELOW_ENROLLMENT`(정원 축소 거부)와 성격이 다르다 |

### 7.3.1 기존 코드 재사용

신규가 아니지만 이 사이클의 새 경로에서 던져진다. 새로 만들지 않는다.

| 코드 | 발생 지점 |
|------|-----------|
| `KlassError.KLASS_NOT_FOUND` (404) | ①③⑤ — `klassId` 가 존재하지 않을 때 |
| `KlassError.NOT_KLASS_OWNER` (403) | `GET /v1/klasses/{klassId}/enrollments` — 남의 강의 명단 조회 |
| `CommonError.VALIDATION_ERROR` (400) | 쿼리 파라미터 검증 |

### 7.4 400 과 409 의 구분 (기존 규약 인용)

400 은 **요청 자체**가 잘못된 경우, 409 는 요청은 옳은데 **현재 리소스 상태**와 충돌하는 경우다.
409 는 입력을 아무리 고쳐도 상태가 바뀌기 전엔 성공하지 않는다 — 그래서 신규 코드 대부분이 409 다.

### 7.5 403 을 404 로 감추지 않는 이유

`NOT_ENROLLMENT_OWNER` 는 403 이다. 강의 상세의 `DRAFT` 처리(404 로 감춤)와 대비되는데,
근거가 다르다 — 신청 id 는 **연속된 정수**라 404/403 을 구분하든 안 하든 존재 여부가 추측
가능하고, 애초에 신청의 존재 자체는 비밀이 아니다. `DRAFT` 강의는 존재 자체가 비밀이었다.

---

## 8. 보안

| 항목 | 처리 |
|------|------|
| 인증 | 전 엔드포인트 `JwtAuthenticationFilter` 통과. `typ == ACCESS`, `jti ∉ revoked_access_token` |
| 소유권 | 서비스 계층에서 `isOwnedBy(sub)` 검사. **`ROLE_CREATOR` 만으로는 부족하다** — 크리에이터끼리 서로의 강의 수강생 명단을 볼 수 없어야 한다 |
| 사용자 id 출처 | **항상 JWT `sub`.** 요청 본문·경로에서 `userId` 를 받지 않는다 |
| 정원 우회 | 앱 검사 + `ck_klass_count` 이중 방어 |
| 중복 신청 | 앱 검사 + `uq_enrollment_active` 이중 방어 |
| CSRF | `SecurityConfig` 에서 이미 비활성. **되살리면 모든 POST 가 403** 이 된다 |

### 8.1 `SecurityConfig` 변경

```java
// ── 강의별 수강생 목록: 크리에이터 전용 ────────────────────
// 기존 GET permitAll 규칙(/v1/klasses, /v1/klasses/{id:[0-9]+})은
// 한 세그먼트만 매칭하므로 이 중첩 경로를 잡지 않는다. 명시하지 않으면
// anyRequest().authenticated() 로 떨어져 아무 로그인 사용자나 명단을 본다.
.requestMatchers(HttpMethod.GET, "/v1/klasses/{klassId:[0-9]+}/enrollments")
        .hasRole(CREATOR_ROLE)
```

나머지 8개는 `anyRequest().authenticated()` 로 충분하다. **경로가 기존 규칙에 걸리지 않는지
확인했다**:

| 신규 경로 | 기존 규칙과의 충돌 |
|-----------|--------------------|
| `POST /v1/klasses/{id}/enrollments` | `POST /v1/klasses` 는 **정확 매칭**이라 걸리지 않는다 ✅ |
| `POST /v1/klasses/{id}/waitlists` | 같음 ✅ |
| `GET /v1/klasses/{id}/enrollments` | `GET /v1/klasses/{id:[0-9]+}` 는 한 세그먼트라 걸리지 않는다 → **명시 필요** ⚠️ |
| `/v1/enrollments/**`, `/v1/waitlists/**` | 기존 규칙 없음 → `authenticated()` ✅ |

> **규칙 순서**: 새 CREATOR 규칙을 기존 "강의 조회: 선택적 인증" 블록보다 **위**에 둔다.
> 지금은 매칭이 겹치지 않지만, 나중에 누군가 `GET /v1/klasses/**` permitAll 을 추가하면
> 순서가 방어선이 된다. klass-management 에서 `/v1/klasses/me` 가 같은 이유로 위에 있다.

---

## 9. 테스트 계획

> **코드와 테스트는 한 세트다.** 이 저장소에서 테스트는 검증 수단이자 **문서 생성원**이라
> (문서 파이프라인) 빠뜨리면 산출물이 함께 빠진다.

### 9.1 레벨별 범위

| 레벨 | 대상 | 위치 | 신규 |
|------|------|------|:----:|
| **L1** 도메인 단위 | 전이 규칙·취소 정책·카운터 | `enrollment/domain/`, `waitlist/domain/`, `klass/domain/` | ~30 |
| **L2** 어댑터·서비스 | 포트 구현, 락 조회, 서비스 조립 | `*/adapter/out/`, `*/application/service/` | ~35 |
| **L3** 컨트롤러 + 문서 | 엔드포인트 계약 + **RestDocs 스니펫** | `controller/` | **9** |
| **L4** 통합 | 동시성·권한 게이트·전 흐름 | `integration/EnrollmentFlowIntegrationTest` | ~20 |
| **L5** 문서 산출물 | 생성된 스펙이 실제로 서빙되는지 | `integration/DocumentationIntegrationTest` | 갱신 |
| **스키마** | `information_schema` + **제약 동작** | `enrollment/domain/EnrollmentSchemaTest` | **3** (§9.6) |

### 9.2 L1 — 도메인 단위

| # | 대상 | 검증 |
|:-:|------|------|
| 1 | `Enrollment.confirm` | `PENDING → CONFIRMED`, `confirmedAt` 설정, **`expiresAt` 이 null 이 된다** |
| 2 | `Enrollment.confirm` | `CONFIRMED`/`CANCELLED` 에서 호출 시 409 |
| 3 | `Enrollment.confirm` | `expiresAt == now` 경계, `expiresAt < now` → `ENROLLMENT_EXPIRED` |
| 4 | `Enrollment.cancel` | `PENDING` 은 기간·종료일 **면제** |
| 5 | `Enrollment.cancel` | `CONFIRMED` + 기간 내 + 강의 진행 중 → 성공 |
| 6 | `Enrollment.cancel` | `confirmedAt + periodDays` **정확히 그 시각** → 성공 (경계 포함) |
| 7 | `Enrollment.cancel` | 1초 초과 → `CANCELLATION_PERIOD_EXPIRED` |
| 8 | `Enrollment.cancel` | `today == ends_on` → 성공 / `today == ends_on + 1` → `KLASS_ALREADY_FINISHED` |
| 9 | `Enrollment.cancel` | **종료일과 기간이 동시에 걸리면 `KLASS_ALREADY_FINISHED` 가 먼저** (검사 순서) |
| 10 | `CancellationPolicy` | `periodDays = 0` 이면 확정 즉시 취소 불가 |
| 11 | `Klass.cancellationPolicy` | `cancellationPeriodDays == null` → 전역 기본값이 들어간다 |
| 12 | `Klass.occupySeat` | `count == capacity` 에서 → `KLASS_CAPACITY_FULL` |
| 13 | `Klass.occupySeat` | **`updatedAt` 이 바뀌지 않는다** |
| 14 | `Klass.releaseSeat` | `count == 0` 에서 → `IllegalStateException` |
| 15 | `Waitlist.promote` | `WAITING → PROMOTED`, `promotedAt` 설정 |
| 16 | `Waitlist.cancel` | `PROMOTED` 에서 호출 시 거부 |

### 9.3 L2 — 어댑터·서비스

| # | 대상 | 검증 |
|:-:|------|------|
| 1 | `KlassJpaRepository.findWithLockById` | **부트스트랩이 깨지지 않는다** (파생 쿼리 이름 함정) |
| 2 | 같음 | `@EntityGraph` 가 붙지 않아 `creator` 가 프록시로 남는다 |
| 3 | `EnrollmentQueryPort.existsActive` | `CANCELLED` 는 활성으로 세지 않는다 |
| 4 | `WaitlistQueryPort.findNextWaitingWithLock` | position 오름차순 1건, `lastPos` 초과분만 |
| 5 | `EnrollmentService.apply` | 6단계 검사 순서 — **개설자 검사가 중복 검사보다 먼저** |
| 6 | `EnrollmentService.cancel` | 승격 성공 시 `enrollment_count` **순변화 0** |
| 7 | 같음 | 대기자 없으면 순변화 `-1` |
| 8 | 같음 | `klass.status != OPEN` 이면 승격 없이 `-1` |
| 9 | 승격 루프 | 1순위 부적격(비활성 사용자) → `CANCELLED` 후 **2순위 승격** |
| 10 | 승격 루프 | **1순위가 개설자면 건너뛴다** (FR-19 세 번째 지점) |
| 11 | `cancelRemainingWaitlist` | `WAITING` 만 `CANCELLED`, `PROMOTED` 는 그대로 |

### 9.4 L3 — 컨트롤러 + RestDocs (9건, **컨트롤러보다 먼저 쓴다**)

| # | 엔드포인트 | 문서화할 것 |
|:-:|-----------|-------------|
| 1 | `POST /v1/klasses/{klassId}/enrollments` | pathParameters, 응답 필드 10종, 201 |
| 2 | `GET /v1/klasses/{klassId}/enrollments` | queryParameters(cursor/size), 커서 응답 |
| 3 | `GET /v1/enrollments/me` | queryParameters(cursor/size/status) |
| 4 | `GET /v1/enrollments/{id}` | pathParameters |
| 5 | `POST /v1/enrollments/{id}/confirm` | 본문 없음, 200 |
| 6 | `POST /v1/enrollments/{id}/cancel` | 본문 없음, 200 |
| 7 | `POST /v1/klasses/{klassId}/waitlists` | 201, `position` 포함 |
| 8 | `GET /v1/waitlists/me` | 커서 응답 |
| 9 | `POST /v1/waitlists/{id}/cancel` | 200 |

> `fieldWithPath("data.isCancellable")` 같은 **RestDocs 경로 문자열**은 컴파일러가 잡지 못한다.
> 필드명을 바꾸면 문서 생성 단계에서 깨진다 (CLAUDE.md 지점 4번).

### 9.5 L4 — 통합

**정본 §8 검증 시나리오 매핑** — 이번 범위 해당분:

| 시나리오 | 내용 | 이번 범위 |
|:--------:|------|:---------:|
| 1 | **잔여 1석에 100건 동시 신청 → 1건 성공** | ✅ 핵심 |
| 2 | 정원 초과 상태에서 신청 → 거부 | ✅ |
| 3 | 동일 사용자 2회 신청 → 거부 | ✅ |
| 4 | 취소 후 재신청 → 성공 | ✅ |
| 5 | CONFIRMED 1건 취소, 대기자 3명 → 1순위만 승격, 순변화 0 | ✅ |
| 6 | 취소 2건 동시, 대기자 1명 → 승격 1건만 | ✅ |
| 7·8 | PENDING 만료 배치 관련 | ❌ 범위 밖 (D-32) |
| 9 | 취소 가능 기간 초과 → 거부, 카운터 불변 | ✅ |
| 10 | CLOSED 후 기존 PENDING 결제 (만료 전) → 성공 | ✅ |
| 11 | CLOSED 에서 신규 신청 → 거부 | ✅ |
| 12 | CLOSED 에서 취소, 대기자 존재 → **승격 없음**, `-1` | ✅ |
| 13 | CLOSED 후 명단 신규 추가 없음 | ✅ |
| 14 | capacity 를 count 보다 작게 → 거부 | ✅ (이제 실제 값으로 검증된다) |
| **15·16** | 로그아웃된 Access 토큰 / 폐기된 Refresh 재사용 | **기존** — `AuthFlowIntegrationTest` 가 이미 덮는다. 신규 엔드포인트도 같은 필터를 지나므로 재작성하지 않는다 |
| 17 | 다른 크리에이터의 수강생 목록 → 403 | ✅ |
| 18·19 | `OPEN → DRAFT` 신청자 0 / 존재 | ✅ (이제 실제 값으로) |
| 20 | CANCELLED 에 확정·취소 재시도 → 거부 | ✅ |
| 21 | 1순위 비활성 → 2순위 승격 | ✅ |
| 22 | DRAFT 강의에 신청 → 거부 | ✅ |
| 23 | CONFIRMED 사용자가 대기 등록 → 거부 | ✅ |
| **24·25·26** | `CLOSED → DRAFT` / `CLOSED → OPEN` / `DRAFT → OPEN` 전이 | **기존** — klass-management 가 화이트리스트로 덮는다. 이번 변경(`CLOSED` 시 대기자 정리)이 깨뜨리지 않는지만 확인 |
| **27** | `CONFIRMED → PENDING` 되돌리기 거부 | ✅ **이번 범위** — L1 #2 가 덮는다 (`confirm` 을 `CONFIRMED` 에서 호출 시 409). 되돌리는 메서드 자체를 만들지 않는 것이 1차 방어 |
| 28 | PROMOTED 대기 포기 → 거부 | ✅ |
| 29 | 만료 시각 지난 PENDING 결제 → 거부 | ✅ (§4.3 4번은 살아 있다) |
| 32·33 | 타인의 신청 취소·확정 → 403 | ✅ |
| 34 | PENDING 직접 취소 → 성공, 승격 | ✅ |
| 35·36 | 대기 포기 / 포기 후 재대기 | ✅ |
| 37·38 | `DRAFT → CLOSED` / `OPEN → CLOSED` 시 대기자 3명 정리 | ✅ |
| 39 | 정원 증가 시 승격 | ❌ **현 정책에서 도달 불가** (D-33) |
| **30** | `ROLE_CREATOR` 회수된 사용자가 자기 강의 상태 변경 → 거부 | **기존** — klass-management 권한 게이트. 신규 `GET .../enrollments` 도 같은 `hasRole` 을 쓰므로 그 경로만 1건 추가 |
| **31** | 탈취 감지 일괄 무효화 시 `revoked_at` 미설정 | **범위 밖** — 인증 도메인 전용. 이 사이클이 건드리지 않는다 |
| 40 | 자리 있는데 대기 등록 → 거부 | ✅ |
| 41 | **정합성 검증 쿼리 — 마지막에 실행, 결과 0행** | ✅ 필수 |

> **정본 41건 전건이 위 표에 등재됐다.** 정본 §6 이 세운 "누락된 FR 이 없어야 한다"는 기계적
> 대조 규율을 시나리오 쪽에도 적용한다 — ❌ 든 "기존"이든 **표시 없이 빠진 행이 없어야 한다.**

**정합성 검증 쿼리 2종** (§11.3 module-5 완료 조건)

```sql
-- ① 카운터 정합성 (ERD §5.1) — 결과 0행이어야 한다
SELECT k.id, k.enrollment_count, COALESCE(e.actual, 0) AS actual
  FROM klass k LEFT JOIN (
       SELECT klass_id, COUNT(*) AS actual FROM enrollment
        WHERE status IN ('PENDING','CONFIRMED') GROUP BY klass_id) e
    ON e.klass_id = k.id
 WHERE k.enrollment_count <> COALESCE(e.actual, 0);

-- ② 만료 좌석 관측 (R-01 완화책) — 0행일 필요는 없다. 세어서 보고한다
SELECT COUNT(*) FROM enrollment
 WHERE status = 'PENDING' AND expires_at <= :now;
```

> **②가 R-01 의 유일한 완화책이다.** 만료 회수를 만들지 않기로 했으므로(D-32), 좌석이 얼마나
> 묶여 있는지 **세어볼 수단만이라도** 남긴다. 이것이 빠지면 High 리스크가 무방비가 된다.
> 통합 테스트에서 "만료된 PENDING 을 만든 뒤 이 쿼리가 그것을 센다"를 확인하고, 완료 보고서에
> 실제 값을 기록한다.

**신규 시나리오 3건** (정본에 없는 FR-19·FR-20):

| # | 시나리오 | 기대 |
|:-:|----------|------|
| N-1 | 개설자가 자기 강의에 신청 | 403 `SELF_ENROLLMENT_FORBIDDEN` |
| N-2 | 개설자가 자기 강의 대기열 등록 | 403 `SELF_ENROLLMENT_FORBIDDEN` |
| N-3 | `ends_on` 경과 후 기간 내 취소 시도 | 409 `KLASS_ALREADY_FINISHED`, 카운터 불변 |

**시나리오 1(동시성) 작성 지침**

```
ExecutorService(100) + CountDownLatch 로 동시 발사
검증 3중:
  ① 성공 응답 수 == 1
  ② klass.enrollment_count == capacity
  ③ 실제 활성 enrollment 행 수 == capacity     ← ②만 보면 카운터 버그를 놓친다
```

> **H2 의 락 거동이 실 DB 와 다르다** (Plan R-04). 성공 건수만 세면 위양성이 날 수 있으므로
> ②③ 을 함께 단정한다. 인메모리 H2 에서 불안정하면 **그 사실 자체를 완료 보고서에 남긴다.**

### 9.6 스키마 검증 — **이미 대부분 있다**

> ⚠️ **초안은 "`waitlist` 6종을 추가한다"고 썼는데 사실과 다르다.** `EnrollmentSchemaTest` 를
> 실제로 확인한 결과 **5종이 이미 존재한다.** 그 잘못된 전제 위에 module-0 게이트와 R-06 이
> 서 있었으므로 함께 정정한다.

| 검증 항목 | 현재 상태 |
|-----------|-----------|
| 테이블 존재 | ✅ `:86` 이 `"WAITLIST"` 포함 |
| CHECK 2개 | ✅ `:187` `CK_WAITLIST_POSITION` · `CK_WAITLIST_PROMOTED` |
| FK 2개 | ✅ `:274` `FK_WAITLIST_KLASS` · `FK_WAITLIST_USER` |
| UNIQUE 2개 | ✅ `:387` `UQ_WAITLIST_POSITION` · `UQ_WAITLIST_WAITING` |
| 인덱스 | ✅ `:371` `IDX_WAITLIST_NEXT` |
| **ENUM 저장 형식** | ❌ `:106` 은 `enrollment` 만 확인한다 — **유일한 실제 누락** |

**따라서 추가할 것은 3건이다.**

| # | 추가 | 이유 |
|:-:|------|------|
| 1 | `waitlist.status` 가 문자열로 저장되는지 | 위 표의 유일한 구멍. ordinal 로 저장되면 값 순서가 바뀔 때 조용히 다른 의미가 된다 |
| 2 | **`uq_waitlist_position` 의 *동작*** | 제약이 **존재한다**는 것만 확인돼 있고, 같은 강의에 같은 순번을 넣었을 때 실제로 거부하는지는 한 번도 실행된 적이 없다 |
| 3 | **`waiting_user_key` 생성 컬럼의 *동작*** | `WAITING` 이면 `user_id`, 그 외 NULL. 포기 후 재대기가 허용되는 근거인데 검증된 적이 없다 |

> **"존재한다"와 "작동한다"는 또 다르다.** 이 저장소의 전제("선언했다 ≠ 생성됐다")를 한 단계
> 더 민다 — 생성된 제약이 의도한 위반을 실제로 거부하는지는 넣어봐야 안다. 2·3번이 그것이다.
>
> `@GeneratedValue(IDENTITY)` 는 `persist()` 시점에 곧바로 INSERT 를 날리므로, 제약 위반
> 예외가 `flush()` 가 아니라 `persist()` 에서 터진다. **`assertThatThrownBy` 로 둘을 함께
> 감싸야 한다** (CLAUDE.md).

### 9.7 L5 — 문서 산출물

`DocumentationIntegrationTest` 갱신:

```java
DOCUMENTED_PATH_COUNT = 16;                          // 8 → 16

DOCUMENTED_OPERATIONS = Map.ofEntries(               // Map.of 는 10쌍이 상한이다
        ... 기존 8 ...,
        entry("/v1/klasses/{klassId}/enrollments", List.of("get", "post")),
        entry("/v1/enrollments/me",                List.of("get")),
        entry("/v1/enrollments/{id}",              List.of("get")),
        entry("/v1/enrollments/{id}/confirm",      List.of("post")),
        entry("/v1/enrollments/{id}/cancel",       List.of("post")),
        entry("/v1/klasses/{klassId}/waitlists",   List.of("post")),
        entry("/v1/waitlists/me",                  List.of("get")),
        entry("/v1/waitlists/{id}/cancel",         List.of("post")));
```

> **`Map.of` 는 최대 10쌍이다.** 16개를 넣으려면 `Map.ofEntries(entry(...))` 로 바꿔야 한다.
> 그대로 두면 컴파일 에러가 나므로 조용히 깨지지는 않지만, 미리 알아두면 시간을 아낀다.

---

## 10. 계층 배치

### 10.1 신규 파일

```
enrollment/
├── adapter/
│   ├── in/web/
│   │   ├── controller/  EnrollmentController
│   │   └── dto/         EnrollmentResponse · EnrollmentSummaryResponse
│   │                    KlassEnrollmentResponse
│   └── out/persistence/ EnrollmentJpaRepository (기존, 확장)
│                        EnrollmentQueryDslRepository
│                        EnrollmentRepositoryAdapter
├── application/
│   ├── EnrollmentProperties                              ← §5.2
│   ├── dto/             ApplyEnrollmentCommand · ConfirmEnrollmentCommand
│   │                    CancelEnrollmentCommand · EnrollmentQuery
│   │                    EnrollmentResult · EnrollmentSummaryResult
│   ├── port/in/         ApplyEnrollmentUseCase · ConfirmEnrollmentUseCase
│   │                    CancelEnrollmentUseCase · FindEnrollmentUseCase
│   │                    ListEnrollmentUseCase · RegisterWaitlistUseCase
│   │                    GiveUpWaitlistUseCase · ListWaitlistUseCase
│   │                    CancelRemainingWaitlistUseCase        ← klass 가 부른다
│   ├── port/out/        EnrollmentCommandPort · EnrollmentQueryPort
│   └── service/         EnrollmentService                     ← 유일한 서비스
└── domain/
    ├── Enrollment (기존, 행위 추가)
    ├── EnrollmentStatus / EnrollmentSource (기존)
    └── error/EnrollmentError

waitlist/                                          ← 서비스 없음
├── adapter/
│   ├── in/web/
│   │   ├── controller/  WaitlistController
│   │   └── dto/         WaitlistResponse
│   └── out/persistence/ WaitlistJpaRepository (기존, 확장)
│                        WaitlistRepositoryAdapter
├── application/
│   ├── dto/             WaitlistResult
│   └── port/out/        WaitlistCommandPort · WaitlistQueryPort
└── domain/
    ├── Waitlist (기존, 행위 추가)
    ├── WaitlistStatus (기존)
    └── error/WaitlistError

klass/domain/CancellationPolicy                    ← 신규 값 객체
```

> **`WaitlistController` 가 `waitlist` 패키지에 있는데 `EnrollmentService` 를 부른다.**
> 어색해 보이지만 `adapter.in → port.in` 이라 규칙 위반이 아니고, URL 이 `/v1/waitlists/**`
> 인 이상 컨트롤러가 그 패키지에 있는 편이 찾기 쉽다.

### 10.2 수정 파일

| 파일 | 변경 |
|------|------|
| `Klass` | `occupySeat` · `releaseSeat` · `hasSeat` · `cancellationPolicy` 추가. `close`/`changeCapacity` javadoc 갱신 |
| `KlassError` | `KLASS_CAPACITY_FULL` 추가 |
| `KlassQueryPort` | `findWithLockById` 추가 + "2차에서 추가된다" javadoc 교체 |
| `KlassJpaRepository` | `findWithLockById` 추가 + javadoc 교체 |
| `KlassRepositoryAdapter` | 위임 추가 |
| `KlassService` | 명령 경로를 락 조회로 전환. `CLOSED` 전이 시 대기자 정리 위임 |
| `SecurityConfig` | 수강생 목록 CREATOR 규칙 1건 |
| `application.yml` | `app.enrollment.*` 3종 |
| `DocumentationIntegrationTest` | path 16 / operation 19 |
| `EnrollmentSchemaTest` | ENUM 저장 1건 + 제약 **동작** 검증 2건 (§9.6) |
| `Enrollment` / `Waitlist` | 클래스 javadoc 의 "1차 범위는 스키마 확정까지다" 제거 |

### 10.3 의존 규칙 준수 확인점

| 위치 | 이번 사이클에서 걸릴 수 있는 지점 | 대응 |
|------|-----------------------------------|------|
| `domain` | `Enrollment.cancel` 이 `Klass` 의 값을 봐야 한다 | `CancellationPolicy` 값 객체로 받는다 — 프록시 초기화 없음 |
| `domain` | 도메인이 `Clock` 을 알면 안 된다 | 시각·날짜를 파라미터로 받는다 |
| `application.service` | 남의 도메인 `adapter` 참조 금지 | 포트만 참조. `EnrollmentService` 가 `KlassRepositoryAdapter` 를 직접 부르지 않는다 |
| `application` | `infrastructure` 역행 | `EnrollmentProperties` 를 `application` 에 둔다 (§5.2) |
| `adapter.out` | `EnrollmentRepositoryAdapter` 가 `klass` 락을 잡으면 안 된다 | 락은 `KlassQueryPort` 소관 |
| `adapter.in` | 엔티티 직접 노출 금지, boolean `is` 접두어 | `EnrollmentResponse.isCancellable` |

---

## 11. 구현 가이드

### 11.1 구현 순서 원칙

1. **스키마 검증이 맨 앞이다.** `waitlist` 제약이 정말 생성됐는지 모르는 채로 구현하면
   나중에 전부 되돌린다
2. **RestDocs 테스트를 컨트롤러보다 먼저.** 안 쓰면 문서에서 조용히 누락되고 L5 가 깨진다
3. **락 복원(M2)이 서비스(M3)보다 먼저.** 서비스가 없는 포트를 부를 수 없다
4. **대기열(M4)을 취소(M3) 뒤에.** 좌석 반납 경로가 먼저 검증돼야 승격을 얹을 수 있다

### 11.2 세션 가이드

#### Module Map

| 모듈 | Scope Key | 내용 | 예상 턴 |
|------|-----------|------|:-------:|
| 모듈 | Scope Key | 내용 | 예상 턴 |
|------|-----------|------|:-------:|
| 스키마 검증 | `module-0` | `EnrollmentSchemaTest` 3건 — ENUM 저장 1 + **제약 동작** 2 (§9.6). **구현 전 실행** | 8-12 |
| 스파이크 정리 | `module-2` 착수 시 | `spike/` 4파일을 실제 리포지토리로 옮기고 **삭제**한다. 남기면 같은 것을 두 벌 검증한다 | — |
| 도메인 행위 | `module-1` | `Enrollment`(전이 2 + 판별 3)·`Waitlist`·`Klass` 카운터·`CancellationPolicy`·에러 enum 2종 + L1 ~30건 | 35-45 |
| 포트·어댑터·락 복원 | `module-2` | 포트 6종, 어댑터 2종, **`findWithLockById` 복원(D-21)**, **조회 3종의 QueryDSL + fetch join**, L2 ~15건 | 40-50 |
| 명령 서비스 | `module-3` | `EnrollmentService` **명령 6종** + 승격 루프 + `KlassService` 두 변경(**`loadForCommand` 락 전환** · `CLOSED` 위임) + `EnrollmentProperties` **+ `application.yml` 3종** + L2 ~20건 | 50-60 |
| 조회 서비스·컨트롤러·문서 | `module-4` | `EnrollmentService` **조회 4종**, 컨트롤러 2종, DTO 5종, `SecurityConfig`, **L3 RestDocs 9건**, L5 갱신 | 50-60 |
| 통합 검증 | `module-5` | L4 시나리오 ~33건 (동시성 포함) + **정합성 쿼리 2종** + javadoc 부채 정리 | 40-50 |

> **조회 4종을 `module-4` 에 둔 이유**: `module-2` 가 QueryDSL 리포지토리를, `module-4` 가
> 컨트롤러를 담당하는데 그 사이 서비스 조회 메서드가 어느 모듈에도 없었다(초안의 누락).
> 컨트롤러와 붙여 두면 DTO 매핑과 fetch join 검증을 한 자리에서 끝낼 수 있다.

> **`KlassService.loadForCommand` 락 전환이 `module-3` 이다.** `module-2` 는 리포지토리
> 레벨까지고, 서비스 전환은 R-08 이 경고한 기존 테스트 파급(`KlassServiceTest` ·
> `KlassControllerTest` · `KlassFlowIntegrationTest`)을 동반하므로 서비스 모듈에 함께 둔다.
> **`findWithCreatorById` → `findWithLockById` 로 바뀌면 `creator` 가 프록시가 되어
> `KlassResult.from(klass)` 경로에 추가 쿼리가 생긴다** — 그 처리까지가 이 모듈의 일이다.

#### 권장 세션 계획

| 세션 | 단계 | 범위 | 턴 |
|------|------|------|:--:|
| 1 | Plan + Design | 전체 | 완료 |
| 2 | Do | `--scope module-0,module-1` | 45-60 |
| 3 | Do | `--scope module-2,module-3` | 90-110 |
| 4 | Do | `--scope module-4` | 50-60 |
| 5 | Do | `--scope module-5` | 40-50 |
| 6 | Check + Report | 전체 | 35-45 |

### 11.3 각 모듈의 완료 조건

| 모듈 | "끝났다"의 정의 |
|------|-----------------|
| `module-0` | ENUM 저장 1건 통과 + **제약 위반이 실제로 거부된다** (순번 중복 / 활성 중복 대기). `assertThatThrownBy` 가 `persist()` 와 `flush()` 를 함께 감쌌는지 |
| `module-1` | L1 전건 통과. 취소 정책 경계(§9.2 #6~#9)가 특히. **`isCancellableAt` 과 `cancel()` 이 같은 판정을 쓴다** |
| `module-2` | 조회 3종이 fetch join 으로 N+1 없이 나간다. **락 조회 2종은 §4.1.1 스파이크가 이미 판정했으므로 스파이크 파일을 실제 리포지토리로 옮기고 삭제한다** |
| `module-3` | 승격 순변화 0 이 검증된다. `@Transactional` 전파가 `REQUIRED` 임이 확인된다. **`application.yml` 에 `app.enrollment.pending-expiry` 3종이 실제로 들어갔다** — 빠지면 기동은 성공하고 첫 신청에서 NPE 다 (§4.1.1 ④). **klass-management 기존 테스트가 전부 통과한다** (락 전환 파급, R-08) |
| `module-4` | `./gradlew build` 통과 — `openapi3.json` 에 16 path / 19 operation. `Map.of` → `Map.ofEntries` 전환 |
| `module-5` | 정합성 쿼리 ①이 **전 시나리오 수행 후 0행**. **②(만료 관측)가 값을 돌려주고 보고서에 기록된다.** "2차에서 추가된다" javadoc 5곳이 갱신됐다 |

---

## 12. Divergence 목록

> ERD 정본 및 Plan 대비 변경 사항. 나중에 "왜 정본과 다르지?" 를 추적할 좌표다.
> 번호는 klass-management 의 D-28 에서 이어진다.

| ID | 대상 | 정본 | 본 설계 | 이유 |
|----|------|------|---------|------|
| **D-29** | 서비스 배치 | 규정 없음 | **좌석 유스케이스 단일 서비스** (`EnrollmentService`). `waitlist` 에 서비스 없음 | ERD §4.1 이 `klass` 행을 트랜잭션 경계의 루트로 지정했으므로 세 테이블은 논리적으로 하나의 애그리거트다. 도메인별로 쪼개면 §4.4 의 "한 트랜잭션 안에서 끝낸다"가 여러 클래스에 걸쳐 `@Transactional` 전파 하나로 깨진다 |
| **D-30** | 신청 자격 | 인증된 사용자 (§7) | **개설자 본인 차단** — 신청·대기등록·**승격 적격성** 3지점 | 사용자 요건(신규). 세 번째 지점이 없으면 대기열이 우회로가 된다 |
| **D-31** | 취소 조건 | `confirmed_at + period` 만 (§4.4 5-b) | **`today <= klass.ends_on` 관문 추가** | 사용자 요건(신규). 강의가 끝난 뒤 취소는 성립하지 않는다 |
| **D-32** | PENDING 만료 회수 | §4.6 배치 | **구현하지 않는다.** `expires_at` 은 채우고 §4.3 4번 거부만 유지 | 사용자 결정 — 외부 배치 서버 전제. 새 `@Scheduled` 컴포넌트를 추가하지 않는다. **잔여 리스크는 §13 R-01** |
| **D-33** | 정원 증가 시 승격 | §4.8 capacity 5번 | **구현하지 않는다** | `changeCapacity` 는 `isFullyEditable()` 분기 안, 즉 `DRAFT` 에서만 호출되고(D-28) `DRAFT` 는 신청·대기가 불가능하다 — **승격 대상이 구조적으로 항상 0.** `OPEN` 에서도 정원 수정을 허용하는 정책으로 바뀌면 되살려야 하며, 그 조건을 `Klass.changeCapacity` javadoc 에 남긴다. 정본 시나리오 39번은 도달 불가 |
| **D-34** | 취소 시 강의 일치 검사 | §4.4 3번 (`enrollment.klass_id != klassId` → ABORT) | **생략** | 정본은 `klassId` 를 외부 입력으로 받는 호출자를 가정했다. 이 설계는 0번에서 스스로 구하므로 어긋날 경로가 없다 |
| **D-35** | 상태 전이 API | 규정 없음 | **전이별 엔드포인트** (`POST /confirm`, `POST /cancel`) | 강의는 `PATCH /status` 하나였으나 `confirm`/`cancel` 은 검증·락 범위·부수 효과가 모두 다르다 (§6.2) |
| **D-36** | 대기열 경로 | 규정 없음 | **`/v1/waitlists`** (복수형) | `/v1/klasses`·`/v1/enrollments` 와 일관. 생성 자원은 "대기 등록 항목" |
| **D-37** | 취소 정책 전달 | 규정 없음 | **`CancellationPolicy` 값 객체** | 프록시 초기화(①)와 원시값 나열(②)의 대가를 피한다 (§3.2.2, O-2 해결) |
| **D-38** | 카운터 갱신과 `updated_at` | 규정 없음 | **`occupySeat`/`releaseSeat` 는 `updatedAt` 을 건드리지 않는다** | `updated_at` 은 크리에이터가 내용을 고친 시각이다. 신청이 들어올 때마다 "최종 수정"이 흔들리면 안 된다 |
| **D-39** | `isCancellable` 응답 필드 | 규정 없음 | **응답에 담는다.** `Enrollment.isCancellableAt` 이 `cancel()` 과 같은 판정을 공유한다 | 클라이언트가 취소 가능 여부를 스스로 계산하면 판정 로직이 양쪽에 복제된다. 판별 메서드를 따로 두지 않으면 **서버 안에서 두 번 구현**되어 같은 문제가 재발한다 |
| **D-40** | 취소 시 소유권·상태 검사 순서 | §4.4 는 **4번(상태) → 5-a(소유권)** | **소유권 → 상태** (§4.3 ③ 3번 → 4번) | 정본이 상태를 먼저 본 것은 **만료 배치와 경로를 공유**해 소유권 블록(5번)을 조건부로 뒀기 때문이다. D-32 로 배치를 만들지 않으므로 그 제약이 사라졌고, 뒤집으면 **비소유자에게 신청 상태를 노출하지 않는다.** <br>**동작 차이**: 타인의 `CANCELLED` 신청을 취소 시도할 때 정본은 409, 이 설계는 403 |
| **D-41** | `@ConfigurationProperties` 배치 | 기존 2개는 `infrastructure/` 아래 (`JwtProperties` · `DefaultUserProperties`) | **`EnrollmentProperties` 는 `enrollment/application/`** | 기존 둘은 **어댑터·부트스트랩이** 소비하지만 이것은 **서비스가** 소비한다. `infrastructure` 에 두면 `application.service → infrastructure` 계층 역행이 생긴다. **배치 규칙이 두 갈래가 되므로** 기준을 명시한다 — *소비자가 있는 계층에 둔다* |

### 12.1 Plan 대비 정정

| 항목 | Plan | Design |
|------|------|--------|
| O-1 근거 | "도메인별로 쪼개면 **기동이 실패**한다" | **과장이었다.** 교차 지점 대부분이 서비스→포트라 빈 순환이 성립하지 않는다. 실제 근거는 애그리거트 경계와 락 경계 집중 (§2.0) |
| O-4 | 별도 미결 | O-1 에 흡수 (Plan 0.2 에서 반영 완료) |
| 미결 O-2·O-3·O-5·O-6 | 미확정 | **전건 확정** — D-37 / D-35 / `CursorPageResult` 재사용 / D-36 |
| `waitlist` 스키마 검증 | "6종을 추가한다" (Plan §2.1 M7, R-06) | **5종이 이미 있다.** 추가는 ENUM 저장 1건 + 제약 **동작** 2건 (§9.6). R-06 을 Medium → Low 로 하향 |

---

## 13. 리스크

| ID | 리스크 | 영향 | 완화 |
|----|--------|------|------|
| **R-01** | **만료 회수 부재로 미결제 신청이 좌석을 영구 점유** — 정원 10 강의에서 10명이 신청하고 아무도 결제하지 않으면 **영구 만석** | **High** | 사용자가 선택한 범위 제외이고 외부 배치 전제다. 이번 사이클은 **관측만 확보** — ERD §5.1 검증 세트에 "만료 시각이 지난 `PENDING` 행 수" 쿼리를 추가한다. `expires_at` 을 정확히 채우므로 외부 배치가 붙는 즉시 동작한다. **완료 보고서에 다음 사이클 최우선으로 등재** |
| R-02 | `@Transactional` 전파 오류로 락이 새 트랜잭션에 빠진다 | High | 위임은 1건(§4.3 ⑦)뿐. 전파를 명시하지 않아 기본값 `REQUIRED` 를 쓰고, `module-3` 완료 조건에 확인을 넣는다. 승격은 `private` 메서드라 애초에 프록시를 타지 않는다 |
| R-03 | H2 의 비관적 락 거동이 실 DB 와 달라 동시성 테스트가 위양성 | High | 성공 건수·카운터·실제 행 수 **3중 단정** (§9.5). 불안정하면 그 사실을 보고서에 남긴다 |
| ~~R-04~~ | ~~H2 가 `ORDER BY ... LIMIT 1 FOR UPDATE` 를 거부할 수 있다~~ | **해소** | **스파이크 실측 완료 (§4.1.1 ③).** H2 2.4.240 이 `ORDER BY … FETCH FIRST ? ROWS ONLY FOR UPDATE` 를 정상 처리한다. 대체안은 필요 없다. **다만 `FOR UPDATE` + 1건 제한 조합의 낡은 행 함정이 `klass` 락에 의존해 막힌다는 사실이 새로 드러났다** — §4.1.1 참조 |
| R-05 | RestDocs 테스트 누락으로 문서에서 조용히 사라진다 | Medium | 모듈별로 **컨트롤러보다 먼저** 쓴다. L5 가 최종 방어선이며 깨지면 고칠 것은 개수가 아니라 테스트다 |
| R-06 | ~~`waitlist` 제약이 선언만 되고 생성되지 않았을 수 있다~~ → **제약 *존재*는 이미 검증돼 있다. 남은 위험은 그것이 *작동*하는지다** | **Low** (하향) | `EnrollmentSchemaTest` 가 테이블·FK 2·CHECK 2·UNIQUE 2·인덱스를 이미 확인한다(§9.6). 미검증은 ENUM 저장 형식 1건과 **제약의 실제 거부 동작** 2건뿐이며 `module-0` 이 그것만 다룬다 |
| R-07 | 목록 조회 N+1 (`Enrollment.klass`/`user` 가 `LAZY`) | Medium | 조회 3종마다 fetch join 명시 + 쿼리 카운트 검증. `open-in-view: false` 라 지연 로딩이 컨트롤러에서 **즉시 실패**로 드러난다 |
| R-08 | 락 복원이 klass-management 기존 테스트를 깨뜨린다 | Medium | `loadForCommand` 가 `@EntityGraph` 없는 조회로 바뀌어 개설자 로딩 경로가 달라진다. `KlassServiceTest`·`KlassControllerTest`·`KlassFlowIntegrationTest` 를 함께 확인 |
| R-09 | `enrollment_count` 가 처음으로 0 이 아니게 되어 기존 RestDocs 예시·단정이 어긋난다 | Low | `module-4` 에서 기존 강의 스니펫을 함께 확인 |

---

## Version History

| 버전 | 날짜 | 변경 | 작성자 |
|------|------|------|--------|
| 0.1 | 2026-09-03 | 최초 작성. Option C(좌석 단일 서비스) 선택. Plan 미결 O-1~O-6 전건 확정. divergence D-29~D-39 등재. Plan §9.1 의 "기동 실패" 근거를 정정 | Chals85 |
| 0.3 | 2026-09-03 | **스파이크 5종 실측 판정 (§4.1.1 신설, 13 테스트 전건 통과).** Codex 교차검증이 할당량으로 중단돼 B축(기술적 실현 가능성)을 직접 실측으로 대체. R-04 해소 · 새 발견 4건 등재(`FOR UPDATE`+1건 제한의 낡은 행 함정이 `klass` 락에 의존 / `position` 예약어 안전 / Spring Data 4 의 `PropertyReferenceException` 패키지 이동 / 프로퍼티 누락 시 중첩 record 가 null 이라 첫 신청에서 NPE). module-2·3 완료 조건 갱신 | Chals85 |
| 0.2 | 2026-09-03 | **design-validator 지적 15건 전건 반영.** ⓘ `isCancellableAt` 추가(D-39 의 근거를 서버 안에서 배반하고 있었다) · **`waitlist` 스키마 검증 5종이 이미 존재함을 확인해 §9.6·module-0·R-06 정정** · 정본 시나리오 8건 매핑 복구(41건 전건 등재) · 만료 관측 쿼리를 구현 지시로 전환 · 유스케이스 "6종"→"명령 6 + 조회 4" 정정 후 조회를 module-4 에 배정 · DTO 3종 필드표 추가(fetch join 강제 지점 명시) · `loadForCommand` 락 전환을 module-3 에 배정 · 에러 배치 규칙 §7.0 신설 · `SEAT_AVAILABLE`→`WAITLIST_SEAT_AVAILABLE` · 기존 코드 재사용 §7.3.1 · 트랜잭션 속성 §4.3.1 · D-40(검사 순서) · D-41(프로퍼티 배치) 등재 | Chals85 |
