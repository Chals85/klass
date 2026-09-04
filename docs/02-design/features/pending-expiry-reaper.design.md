# PENDING 만료 회수 스케줄러 설계서

> **Summary**: `@Scheduled` 진입점을 인바운드 어댑터로 두고, 건별 독립 트랜잭션에서 `klass` 락 → 상태 재확인 → `expire()` → 좌석 반납 → 승격을 수행한다. `Enrollment.cancel` 시그니처는 건드리지 않는다.
>
> **Project**: klass
> **Version**: 0.9.0
> **Author**: developer2@lulumedic.com
> **Date**: 2026-09-04
> **Status**: Draft
> **Planning Doc**: [pending-expiry-reaper.plan.md](../../01-plan/features/pending-expiry-reaper.plan.md)
> **데이터 모델 정본**: [class-enrollment-erd.design.md](./class-enrollment-erd.design.md)

---

## Context Anchor

| Key | Value |
|-----|-------|
| **WHY** | 결제하지 않은 `PENDING` 이 좌석을 영구 점유해 강의가 영구 만석이 되는 R-01 을 해소한다 |
| **WHO** | 수강생(대기자) — 회수된 좌석으로 승격 / 개설자 — 정확한 수강생 명단과 정원 활용 / 운영자 — 만료율 관측 |
| **RISK** | 배치가 `klass` 락 순서를 어기거나 락 밖에서 승격해 신규 신청자와 좌석을 두고 경합하는 것 |
| **SUCCESS** | 만료 후 최대 10분 내 `enrollment_count` 감소 · 대기 1순위 승격 · 만료 `PENDING` 관측 쿼리(R-01)가 0 으로 수렴 |
| **SCOPE** | module-0 스키마·도메인 → module-1 포트·어댑터 → module-2 유스케이스 → module-3 스케줄러·설정 → module-4 응답·문서 → module-5 통합 테스트 |

---

## 1. Overview

### 1.1 Design Goals

1. **기존 회수 경로를 재사용한다.** 취소 → `releaseSeat()` → `promoteNextWaiting()` 은 이미 `EnrollmentService.cancel` 에 있다. 새로 만들 것은 **진입 경로**뿐이다.
2. **락 규약을 한 줄도 완화하지 않는다.** 배치도 `klass` 를 첫 락으로 잡고, 승격은 그 락 안에서 일어난다.
3. **기존 코드를 깨지 않는다.** `Enrollment.cancel` 시그니처를 유지해 호출부·테스트가 그대로 산다.
4. **조용히 깨지는 자리를 코드로 막는다.** 프록시 우회로 트랜잭션이 사라지는 자리, 설정 누락으로 배치 크기가 0 이 되는 자리를 구조로 차단한다.

### 1.2 Design Principles

- **의도가 드러나는 메서드** — `cancel` 에 플래그를 추가하지 않고 `expire()` 를 신설한다. CLAUDE.md 의 "public setter 없음, 의도가 드러나는 메서드로만"(`rotate()` · `occupySeat()`) 규약과 같은 계열이다.
- **판정은 한 곳에** — `confirm` 의 만료 검사와 배치의 재확인이 **같은 메서드**(`isExpiredAt`)를 부른다. 두 벌이 되면 갈라진다.
- **스케줄러는 인바운드 어댑터** — 시각을 소유하지 않고 유스케이스만 부른다.
- **불변식은 트랜잭션 안, 부수효과는 커밋 후** — 승격은 전자이므로 직접 호출한다 (D-47).

---

## 2. Architecture Options

### 2.0 Architecture Comparison

| 기준 | A: Minimal | B: Clean | **C: Pragmatic** |
|------|:-:|:-:|:-:|
| 스케줄러 위치 | `application/service/` | `adapter/in/scheduler/` | **`adapter/in/scheduler/`** |
| 도메인 전이 | `cancel(…, reason)` 인자 추가 | `expire(now)` + 값 객체 | **`expire(now)` 신설** |
| 조회 포트 | 기존 확장 | 전용 포트 신설 | **기존 확장** |
| 인바운드 포트 | 없음 | 유스케이스 2개 | **유스케이스 1개** |
| 신규 파일 | 3 | 8 | **5** |
| 수정 파일 | 8 | 9 | **6** |
| 기존 호출부 영향 | 🔴 Breaking | ✅ 없음 | ✅ **없음** |
| 복잡도 | 낮음 | 높음 | 중 |

> 파일 수는 **프로덕션 파일 기준 추정치**로 세 안을 비교하기 위한 것이다. 실제 목록은
> §11.2 를 따른다 (신규 4 · 수정 9 + DTO 6 + 테스트 7).

**Selected: C — Pragmatic**

**근거**: `expire()` 분리로 Plan §6.2 가 Breaking 으로 잡았던 `cancel` 시그니처 변경(R-3)이 **사라진다.** 스케줄러를 인바운드 어댑터로 올려 계층 규칙(`adapter.in → port.in`)을 정확히 지키되, B 가 추가하는 포트 분리는 **`promoteNextWaiting` 재사용 제약을 줄이지 못하므로** 실익이 생길 때까지 미룬다.

### 2.1 Component Diagram

```
┌──────────────────────────────────────────────────────────────┐
│ adapter/in/scheduler                                         │
│   ExpiredEnrollmentScheduler        @Scheduled fixedDelay 10m│
│     · 트랜잭션 없음 (진입점)                                  │
│     · port.in 만 의존 — Clock 도, out 포트도 모른다           │
└───────────────────────────┬──────────────────────────────────┘
                            │ ① findExpiredTargets()  ② reapExpired(id) × N
                            ▼
┌──────────────────────────────────────────────────────────────┐
│ application/port/in                                          │
│   ReapExpiredEnrollmentUseCase                               │
└───────────────────────────┬──────────────────────────────────┘
                            ▼
┌──────────────────────────────────────────────────────────────┐
│ application/service/EnrollmentService                        │
│   findExpiredTargets()   readOnly — 락 없이 id 만            │
│   reapExpired(id)        @Transactional — 건별 경계          │
│     └─ promoteNextWaiting(...)   ★ private 그대로 유지        │
└───────────────────────────┬──────────────────────────────────┘
                            ▼
┌──────────────────────────────────────────────────────────────┐
│ domain                                                       │
│   CancelReason  (USER / EXPIRED)            ← 신규            │
│   Enrollment.expire(now)                    ← 신규            │
│   Enrollment.isExpiredAt(now)               ← 신규 (판정 통합)│
│   Enrollment.cancel(...)                    ← 시그니처 불변   │
└──────────────────────────────────────────────────────────────┘
```

**빈이 둘로 나뉘는 것이 설계의 핵심**이다. `@Scheduled` 메서드에서 같은 클래스의 `@Transactional` 메서드를 부르면 프록시를 타지 않아 **트랜잭션이 걸리지 않는다.** 진입점과 처리 메서드를 다른 빈에 두면 그 실수가 구조적으로 불가능하다.

### 2.2 Data Flow

```
[10분마다]
  ①  findExpiredTargets()            트랜잭션: readOnly, 락 없음
      └─ SELECT id FROM enrollment
          WHERE status='PENDING' AND expires_at <= now
          ORDER BY expires_at ASC LIMIT :batchSize
                                     ↓  List<Long>
  ②  for each id:  reapExpired(id)   트랜잭션: 건별 REQUIRED (신규)
      ├─ findKlassIdById(id)          락 없음 — 락 순서 확보용 (§4.1)
      ├─ lockKlass(klassId)           ★ 1번째 락 (klass 단일 행)
      ├─ findWithLockById(id)         ★ 2번째 락 (enrollment)
      ├─ isExpiredAt(now) 재확인      ← 아니면 조용히 skip (FR-08)
      ├─ enrollment.expire(now)       CANCELLED · cancelReason=EXPIRED
      ├─ klass.releaseSeat()          enrollment_count--
      └─ promoteNextWaiting(klass)    OPEN 이면 대기 1순위 승격 (락 안)
      ↑ 예외 발생 시 이 건만 롤백, 루프는 계속
```

**①과 ② 사이가 이 설계의 유일한 경합 창**이다. 그 사이 사용자가 결제·취소했을 수 있으므로 ②에서 락을 잡은 뒤 반드시 재확인한다.

### 2.3 Dependencies

| 컴포넌트 | 의존 | 목적 |
|----------|------|------|
| `ExpiredEnrollmentScheduler` | `ReapExpiredEnrollmentUseCase` | **오직 이것 하나.** `adapter.in → port.in` 규칙 준수 |
| `EnrollmentService` | `EnrollmentQueryPort`(확장) · `KlassQueryPort` · `WaitlistQueryPort` · `Clock` | 전부 기존 의존. 새로 주입할 것 없음 |
| `Enrollment` | `CancelReason` | 신규 ENUM |
| `EnrollmentQueryDslRepository` | `JPAQueryFactory` | 기존 |

---

## 3. Data Model

### 3.1 CancelReason (신규 ENUM)

```java
package com.toby.klass.enrollment.domain;

/**
 * 취소 원인.
 *
 * <p>ERD 정본 §2 ⑦ 이 "만료율 측정이나 환불 정책 분기가 필요하면 감사 테이블보다 싸다"며
 * 도입 조건을 만료 회수로 명시해 둔 항목이다. 지금이 그 시점이다.
 */
public enum CancelReason {
    /** 사용자가 직접 취소했다. */
    USER,
    /** 결제 기한이 지나 배치가 회수했다. */
    EXPIRED
}
```

`{domain}/domain/` 에 두고 `@Enumerated(EnumType.STRING)` 으로 매핑한다 (ordinal 금지).

### 3.2 Enrollment 변경

**추가 필드**

```java
/**
 * 취소 원인. {@code CANCELLED} 일 때만 값이 있다 ({@code ck_enrollment_cancelled}).
 *
 * <p>ERD 정본 §2 ⑦ 의 열린 미결을 만료 회수 도입과 함께 닫은 것이다.
 */
@Enumerated(EnumType.STRING)
@Column(name = "cancel_reason", length = 20)
private CancelReason cancelReason;
```

**상태 전이 — `cancel` 은 시그니처가 바뀌지 않는다**

```java
public void cancel(LocalDateTime now, LocalDate today, CancellationPolicy policy) {
    ...기존 관문 그대로...
    this.status = EnrollmentStatus.CANCELLED;
    this.cancelledAt = now;
    this.cancelReason = CancelReason.USER;   // ← 추가되는 한 줄
    this.expiresAt = null;
}
```

**신규 — `expire`**

```java
/**
 * 결제 기한이 지난 신청을 회수한다. {@code PENDING → CANCELLED}.
 *
 * <h4>왜 {@link #cancel} 에 플래그를 넣지 않았는가</h4>
 * 만료는 취소 가능 기간·강의 종료 관문이 애초에 무의미하므로 {@code today}·{@code policy}
 * 를 받을 이유가 없다. 인자를 추가하면 두 경로가 하나의 메서드에서 조건문으로 갈리고,
 * 기존 호출부가 전부 바뀐다.
 *
 * <p><b>호출자는 반드시 {@code klass} 배타 락 아래</b>에서 이 메서드와
 * {@code releaseSeat()} · 승격을 <b>한 트랜잭션으로</b> 끝내야 한다.
 *
 * @param now 회수 시각. {@code LocalDateTime.now(clock)} 으로 얻은 값
 * @throws BusinessException {@code PENDING} 이 아니거나 아직 기한이 남은 경우
 */
public void expire(LocalDateTime now) {
    if (this.status != EnrollmentStatus.PENDING) {
        throw EnrollmentError.INVALID_ENROLLMENT_STATUS_TRANSITION.toException();
    }
    if (!isExpiredAt(now)) {
        throw EnrollmentError.ENROLLMENT_NOT_EXPIRED.toException();
    }
    this.status = EnrollmentStatus.CANCELLED;
    this.cancelledAt = now;
    this.cancelReason = CancelReason.EXPIRED;
    this.expiresAt = null;                    // ck_enrollment_pending
}
```

**신규 — `isExpiredAt` (판정 통합)**

```java
/**
 * 결제 기한이 지났는지 판별한다.
 *
 * <p><b>{@link #confirm} 과 {@link #expire} 가 이 판정을 공유한다.</b> 둘은 정확히 반대
 * 조건에서 성립하므로, 판정이 두 벌이 되면 경계에서 갈라진다 — 확정도 회수도 되지 않는
 * 행이 생긴다. 배치의 재확인(FR-08)도 같은 메서드를 쓴다.
 *
 * <p>경계는 <b>같은 시각을 이미 만료로 본다</b> — {@code expiresAt} 이 정확히 {@code now}
 * 이면 {@code true} 다. 기존 {@code confirm} 의 {@code !expiresAt.isAfter(now)} 를 그대로
 * 옮긴 것이라 동작이 바뀌지 않는다. "기한이 10:30 까지"가 아니라 "10:30 이 되면 끝"이다.
 */
public boolean isExpiredAt(LocalDateTime now) {
    return this.status == EnrollmentStatus.PENDING && !this.expiresAt.isAfter(now);
}
```

`confirm` 은 기존 조건식을 이 메서드로 교체한다 (동작 불변, 판정 일원화).

### 3.3 스키마 변경

**컬럼**

| 컬럼 | 타입 | NULL | 설명 |
|------|------|:----:|------|
| `cancel_reason` | `VARCHAR(20)` | ✅ | `CANCELLED` 일 때만 값. `USER` / `EXPIRED` |

**CHECK 제약 — `ck_enrollment_cancelled` 확장**

```java
@CheckConstraint(name = "ck_enrollment_cancelled",
        constraint = "(status = 'CANCELLED' AND cancelled_at IS NOT NULL "
                + "AND cancel_reason IS NOT NULL) "
                + "OR (status <> 'CANCELLED' AND cancel_reason IS NULL)")
```

기존은 `status <> 'CANCELLED' OR cancelled_at IS NOT NULL` 단방향이었다. **양방향으로 바꾼다** (D-49) — `cancel_reason` 은 신규 컬럼이라 기존 데이터 호환 문제가 없고, `ck_enrollment_pending` 이 이미 양방향 선례다. `cancelled_at` 의 단방향 성질은 그대로 포함되므로 기존 보장은 줄지 않는다.

**인덱스 — 추가하지 않는다**

후보 조회는 `idx_enrollment_expiry(expires_at)` 만으로 충분하다. 근거:

> `ck_enrollment_pending` 이 **"`PENDING` 이 아니면 `expires_at IS NULL`"** 을 강제하므로,
> `expires_at IS NOT NULL` 인 행은 **정의상 전부 `PENDING`** 이다.

따라서 `expires_at <= now` 조건만으로 후보가 정확히 걸러지며, `status` 조건은 인덱스 밖 필터가 아니라 **중복 조건**이다. 복합 인덱스를 새로 만들 이유가 없다. 지금까지 사용처가 없던 이 인덱스가 이번에 처음 제값을 한다.

### 3.4 마이그레이션 (실 DB 전환 시)

H2 인메모리라 이번 사이클에는 기존 데이터가 없다. 실 DB 전환 시에는 순서가 있다:

```sql
ALTER TABLE enrollment ADD COLUMN cancel_reason VARCHAR(20);
UPDATE enrollment SET cancel_reason = 'USER' WHERE status = 'CANCELLED';  -- 백필 필수
ALTER TABLE enrollment ADD CONSTRAINT ck_enrollment_cancelled CHECK (...);
```

**백필을 빠뜨리면 제약 추가가 실패한다.** 기존 `CANCELLED` 행은 전부 사용자 취소이므로 `USER` 가 맞다.

---

## 4. API Specification

### 4.1 신규 엔드포인트

**없다.** 관리자 수동 회수 엔드포인트는 Plan §2.2 에서 제외했다.

### 4.2 응답 필드 추가

기존 4개 응답 형태에 `cancelReason` 이 추가된다. **경로·메서드·상태 코드는 변하지 않는다.**

| DTO | 쓰이는 엔드포인트 |
|-----|-------------------|
| `EnrollmentResponse` | `POST /v1/klasses/{id}/enrollments` · `POST /v1/enrollments/{id}/confirm` · `POST /v1/enrollments/{id}/cancel` · `GET /v1/enrollments/{id}` |
| `EnrollmentSummaryResponse` | `GET /v1/enrollments/me` |
| `KlassEnrollmentResponse` | `GET /v1/klasses/{id}/enrollments` |

```json
{
  "data": {
    "id": 42,
    "status": "CANCELLED",
    "cancelledAt": "2026-09-04T12:10:00",
    "cancelReason": "EXPIRED",
    "expiresAt": null
  }
}
```

`CANCELLED` 가 아니면 `null` 이다. **`EnrollmentSummaryResponse` 에도 넣는 이유**: "내 신청 목록"이 사용자가 취소 사유를 확인하는 대표 화면이다. 여기서 빠지면 상세를 하나씩 열어봐야 한다.

> **목록 응답 둘은 `cancelledAt` 을 갖지 않는다.** `EnrollmentSummaryResult` 와
> `KlassEnrollmentResult` 는 원래 취소 **시각**을 담지 않으므로, "사유는 있는데 시각은 없는"
> 형태가 된다. **의도한 것이다** — 목록에서 필요한 것은 "왜 취소됐나"이고, "언제"는 상세
> (`EnrollmentResponse`)가 답한다. 시각까지 넣으면 목록 DTO 가 상세와 같아져 둘로 나눈
> 이유가 사라진다.

> ⚠️ RestDocs 는 `fieldWithPath("data.cancelReason").optional()` 로 문서화한다. 누락하면
> **문서 생성 단계에서 실패**한다 (CLAUDE.md "컴파일러가 잡지 못하는 지점" 4번). 엔드포인트
> 개수는 변하지 않으므로 `DocumentationIntegrationTest` 의 개수 검증은 깨지지 않는다.

---

## 5. 스케줄러 실행 명세

### 5.1 진입점

```java
package com.toby.klass.enrollment.adapter.in.scheduler;

/**
 * 만료된 결제 대기 신청을 주기적으로 회수한다.
 *
 * <h2>왜 트랜잭션이 없는가</h2>
 * 회수는 <b>건별 독립 트랜잭션</b>이다(FR-07). 이 메서드에 {@code @Transactional} 을 걸면
 * 사이클 전체가 한 트랜잭션이 되어 한 건의 실패가 전부를 롤백하고, 여러 {@code klass} 행을
 * 동시에 오래 잠근다.
 *
 * <h2>왜 별도 빈인가</h2>
 * {@code @Scheduled} 메서드에서 <b>같은 클래스</b>의 {@code @Transactional} 메서드를 부르면
 * 프록시를 타지 않아 <b>트랜잭션이 걸리지 않는다.</b> 컴파일도 테스트도 통과하며 조용히
 * 깨지므로, 진입점과 처리 메서드를 다른 빈에 두어 그 실수를 구조적으로 막는다.
 *
 * <h2>단일 인스턴스를 전제로 한다</h2>
 * 여러 대로 확장하면 같은 대상을 동시에 집는다. {@code klass} 행 락이 직렬화하고 재확인이
 * 중복 처리를 막으므로 <b>정합성은 깨지지 않지만</b> 불필요한 경합이 생긴다. 실서비스에서는
 * ShedLock 같은 분산 락이 필요하다 ({@code RevokedAccessTokenCleaner} 와 같은 전제).
 *
 * <p>Design Ref: §2.1, §5, Plan FR-01 · FR-07 · FR-09
 */
@Component
public class ExpiredEnrollmentScheduler {

    private static final Logger log = LoggerFactory.getLogger(ExpiredEnrollmentScheduler.class);

    private final ReapExpiredEnrollmentUseCase reapExpiredEnrollmentUseCase;

    @Scheduled(
            initialDelayString = "${app.enrollment.reap-interval:PT10M}",
            fixedDelayString = "${app.enrollment.reap-interval:PT10M}")
    public void reap() {
        List<Long> targets = reapExpiredEnrollmentUseCase.findExpiredTargets();

        int reaped = 0;
        for (Long enrollmentId : targets) {
            try {
                if (reapExpiredEnrollmentUseCase.reapExpired(enrollmentId)) {
                    reaped++;
                }
            } catch (Exception e) {
                // 한 건의 실패가 사이클을 멈추지 않는다 (FR-07)
                log.warn("만료 신청 회수 실패. enrollmentId={}", enrollmentId, e);
            }
        }

        if (reaped > 0) {
            log.info("만료된 수강신청 {}건을 회수했습니다 (후보 {}건)", reaped, targets.size());
        }
    }
}
```

**스케줄러는 `Clock` 을 주입받지 않는다.** 시각은 유스케이스가 소유한다 — `adapter.in` 이 도메인 규칙(만료 판정 기준 시각)을 결정하면 계층이 뒤집힌다.

**`initialDelay` 를 주기와 같게 두는 것이 테스트 격리 장치다.** `@SpringBootTest` 가 컨텍스트를 띄워도 10분 안에 배치가 돌지 않으므로 통합 테스트에 끼어들지 않는다. 테스트는 회수 메서드를 직접 호출해 검증한다.

### 5.2 유스케이스 포트

```java
package com.toby.klass.enrollment.application.port.in;

/**
 * 만료된 결제 대기 신청 회수.
 *
 * <p><b>메서드가 둘인 이유</b>: 후보 조회는 락 없이 읽어야 하고 회수는 건별 트랜잭션이어야
 * 한다. 하나로 합치면 사이클 전체가 한 트랜잭션이 된다.
 */
public interface ReapExpiredEnrollmentUseCase {

    /**
     * 회수 대상 id 를 읽는다. <b>락을 잡지 않으며 엔티티를 로딩하지 않는다.</b>
     *
     * <p>id 만 돌려주는 이유: 실제 처리는 건별 트랜잭션에서 <b>락을 걸고 다시 읽어야</b>
     * 하므로, 여기서 엔티티를 들고 가봐야 그 사이 낡은 값이 된다.
     *
     * <p>한 사이클의 상한이 있다({@code app.enrollment.reap-batch-size}). 남은 것은 다음
     * 사이클이 처리한다 — 만료가 폭증해도 한 번의 실행이 길어지지 않는다.
     *
     * @return 만료가 오래된 순서의 신청 id. 없으면 빈 목록
     */
    List<Long> findExpiredTargets();

    /**
     * 한 건을 회수한다. 호출마다 <b>독립 트랜잭션</b>이다.
     *
     * <p>락을 잡은 뒤 상태를 <b>재확인</b>한다 — 후보 조회 시점과 이 시점 사이에 사용자가
     * 결제를 마쳤거나 스스로 취소했을 수 있다. 그 경우 아무것도 하지 않고 {@code false} 를
     * 돌려준다. <b>예외가 아니다</b> — 정상적인 경합 결과다.
     *
     * @return 실제로 회수했으면 {@code true}
     */
    boolean reapExpired(Long enrollmentId);
}
```

### 5.3 서비스 구현

```java
@Override
public List<Long> findExpiredTargets() {
    // 클래스 레벨 @Transactional(readOnly = true) 를 그대로 받는다
    return enrollmentQueryPort.findExpiredIds(now(), properties.reapBatchSize());
}

@Override
@Transactional
public boolean reapExpired(Long enrollmentId) {
    // 0. 락 순서를 지키려고 소속 강의부터 알아낸다 (무락) — cancel 과 동일 (§4.1)
    Long klassId = enrollmentQueryPort.findKlassIdById(enrollmentId).orElse(null);
    if (klassId == null) {
        return false;   // 후보 조회 이후 사라졌다. 도달하기 어렵지만 배치는 방어한다
    }

    Klass klass = lockKlass(klassId);                    // ① klass 락
    Enrollment enrollment = enrollmentQueryPort
            .findWithLockById(enrollmentId).orElse(null); // ② enrollment 락
    if (enrollment == null) {
        return false;
    }

    LocalDateTime now = now();
    if (!enrollment.isExpiredAt(now)) {
        return false;   // ③ 재확인 — 그 사이 결제·취소됐다 (FR-08)
    }

    enrollment.expire(now);
    klass.releaseSeat();
    promoteNextWaiting(klass, now);   // ★ 락 안에서. private 그대로

    return true;
}
```

**`lockEnrollment(id, requesterId)` 를 쓰지 않는다.** 그 헬퍼는 소유권을 검사하는데 배치에는 요청자가 없다. `findWithLockById` 를 직접 부른다.

### 5.4 포트·어댑터 확장

```java
// EnrollmentQueryPort
/**
 * 결제 기한이 지난 신청의 id 를 읽는다. <b>락을 잡지 않는다.</b>
 *
 * <p>{@code idx_enrollment_expiry} 를 탄다. {@code ck_enrollment_pending} 이
 * "{@code PENDING} 이 아니면 {@code expires_at IS NULL}" 을 강제하므로
 * <b>{@code expires_at IS NOT NULL} 인 행은 정의상 전부 {@code PENDING}</b> 이다 —
 * 단일 인덱스만으로 후보가 정확히 걸러진다.
 *
 * @param now   기준 시각. 이 시각에 도달했거나 지난 것이 대상이다 (같은 시각도 만료)
 * @param limit 한 번에 가져올 최대 건수
 */
List<Long> findExpiredIds(LocalDateTime now, int limit);
```

```java
// EnrollmentQueryDslRepository — 오래된 만료부터 처리한다
public List<Long> findExpiredIds(LocalDateTime now, int limit) {
    return queryFactory
            .select(enrollment.id)
            .from(enrollment)
            .where(enrollment.status.eq(EnrollmentStatus.PENDING),
                   enrollment.expiresAt.loe(now))
            .orderBy(enrollment.expiresAt.asc())
            .limit(limit)
            .fetch();
}
```

`status` 조건은 논리적으로 중복이지만 **쿼리의 의도를 드러내기 위해 남긴다.** 제약이 언젠가 바뀌어도 이 쿼리는 여전히 옳다.

**경계는 도메인과 정확히 같다.** 포트가 `expires_at <= now`, 도메인 `isExpiredAt` 이 `!expiresAt.isAfter(now)` — 같은 조건이다. 어긋나면 후보가 재확인에서 전부 걸러지거나(좁으면) 아직 유효한 신청을 집어 온다(넓으면).

> `EnrollmentQueryPort` 의 클래스 javadoc 은 "세 가지 단건 조회가 나뉘어 있다" 표로 조회
> 갈래를 관리한다. `findExpiredIds` 는 단건이 아니므로 그 표에 넣지 않되, **표 아래에 목록
> 조회 갈래가 생겼음을 한 줄 덧붙인다** — 표만 보고 "조회는 셋뿐"이라고 읽으면 안 된다.

---

## 6. Error Handling

### 6.1 신규 에러 코드

| 코드 | HTTP | 메시지 | 비고 |
|------|:----:|--------|------|
| `ENROLLMENT_NOT_EXPIRED` | 409 | 아직 결제 기한이 지나지 않은 신청입니다 | **HTTP 로 나가지 않는다** |

`expire()` 를 아직 유효한 신청에 부르면 던진다. 배치가 재확인(FR-08)을 하므로 정상 경로에서는 도달하지 않으며, **도메인 불변식의 방어선**으로 존재한다. `WaitlistError.WAITLIST_PAGE_SIZE_OUT_OF_RANGE` 와 같은 성격이다 — 포트를 직접 호출하는 경로를 막는 둘째 방어선.

### 6.2 배치 내 예외 처리

| 상황 | 처리 | 근거 |
|------|------|------|
| 대상이 사라짐 / 이미 결제됨 / 이미 취소됨 | `false` 반환, 로그 없음 | **예외가 아니라 정상적인 경합 결과**다. 로그를 남기면 정상 동작이 경고로 쌓인다 |
| 락 획득 타임아웃 · DB 오류 | `catch` 후 `warn` 로그, 다음 건 계속 | 한 건의 실패가 사이클을 멈추면 안 된다 (FR-07) |
| 회수 0건 | 로그 없음 | 선례(`RevokedAccessTokenCleaner`)와 동일. 지울 게 없는 것은 정상이다 |

**`catch (Exception e)` 로 넓게 잡는다.** 배치 루프는 어떤 예외에도 다음 건으로 넘어가야 한다. 좁게 잡으면 예상 못 한 예외 하나가 남은 대상 전부를 미처리로 만든다.

---

## 7. Security Considerations

- [x] **인증·인가 영향 없음** — 신규 엔드포인트가 없다. `SecurityConfig` 매처를 건드리지 않는다
- [x] **요청자 없는 상태 변경** — 배치는 소유권을 검사하지 않는다. 정당한 이유는 "기한 경과"라는 **객관적 사실**뿐이며, 그 판정을 도메인(`isExpiredAt`)이 소유한다
- [x] **정보 노출** — `cancelReason` 은 본인 신청과 개설자의 자기 강의 명단에만 나간다. 기존 인가 경계를 그대로 탄다
- [x] **로그** — `enrollmentId` 만 남기고 사용자 식별 정보를 남기지 않는다

---

## 8. Test Plan

### 8.1 L1 — 도메인 (`EnrollmentTest`)

| # | 검증 |
|---|------|
| 1 | `expire` 가 `PENDING → CANCELLED`, `cancelReason = EXPIRED`, `expiresAt = null` |
| 2 | `expire` 가 `CONFIRMED` 에서 409 `INVALID_ENROLLMENT_STATUS_TRANSITION` |
| 3 | `expire` 가 `CANCELLED` 에서 409 (이중 회수 방지) |
| 4 | `expire` 가 아직 기한이 남았으면 409 `ENROLLMENT_NOT_EXPIRED` |
| 5 | **경계** — `expiresAt == now` 이면 **이미 만료** (`isExpiredAt == true`, `expire` 성공, `confirm` 거부) |
| 6 | **경계** — `expiresAt` 1나노초 전까지는 유효 (`confirm` 성공, `expire` 거부) |
| 7 | `cancel` 이 `cancelReason = USER` 를 남긴다 |
| 8 | `isExpiredAt` 이 `CONFIRMED`·`CANCELLED` 에 대해 항상 `false` (`expiresAt` 이 `null` 이라 NPE 가 나면 안 된다) |

L1·L2 는 `Clock` 을 고정해 시각을 만든다. **L4 는 다르다** — §8.8.1 참조.

### 8.2 L1 — 스키마 (`EnrollmentSchemaTest`)

**신규 검증**

| # | 검증 |
|---|------|
| 1 | `cancel_reason` 컬럼이 존재한다 |
| 2 | `ck_enrollment_cancelled` 확장식이 DDL 에 있다 |
| 3 | `CANCELLED` 인데 `cancel_reason` 이 없으면 DB 가 거부한다 |
| 4 | `PENDING` 인데 `cancel_reason` 이 있으면 DB 가 거부한다 (역방향) |
| 5 | `cancel_reason` 이 ordinal 이 아니라 문자열로 저장된다 (ENUM 검증에 편입) |

**기존 픽스처 갱신 — 빠뜨리면 기존 테스트 2건이 깨진다**

이 클래스는 두 곳에서 **도메인을 우회해 네이티브 SQL 로** `CANCELLED` 행을 만든다.

| 위치 | 테스트 |
|------|--------|
| `EnrollmentSchemaTest:324` | `allowsReapplyAfterCancellation` |
| `EnrollmentSchemaTest:348` | `generatedColumnIsComputedByDatabase` |

둘 다 `update enrollment set status = 'CANCELLED', expires_at = null, cancelled_at = current_timestamp` 이며 `cancel_reason` 을 채우지 않는다. §3.3 의 양방향 CHECK 가 붙는 순간 **즉시 제약 위반으로 실패한다.**

```sql
-- 두 곳 모두 이렇게 고친다
update enrollment set status = 'CANCELLED', expires_at = null,
       cancelled_at = current_timestamp, cancel_reason = 'USER'
 where user_id = ...
```

D-49 의 "기존 데이터 호환 문제가 없다"는 **데이터에 대한 이야기**다. 도메인을 우회하는 테스트 픽스처는 별개이며, 여기서 함께 고친다.

> ⚠️ `@GeneratedValue(IDENTITY)` 는 `persist()` 시점에 INSERT 를 날린다. 제약 위반은
> `flush()` 가 아니라 `persist()` 에서 터지므로 `assertThatThrownBy` 로 **둘을 함께 감싼다.**

### 8.3 L2 — 어댑터 (`EnrollmentRepositoryAdapterTest`)

`findExpiredIds` 는 **경계·정렬·상한을 한 쿼리에 담고 있어** 이 계층에서 검증하지 않으면 §8.1 #5·#6 의 도메인 경계와 어긋나도 드러나지 않는다.

| # | 검증 |
|---|------|
| 1 | `expires_at < now` 인 `PENDING` 을 반환한다 |
| 2 | **경계** — `expires_at == now` 를 반환한다 (`loe`). 도메인 `isExpiredAt` 도 이 경우 `true` 이므로 §5.3 재확인이 통과시킨다 — **두 경계가 같아야 한다** |
| 3 | **경계** — `expires_at` 이 1마이크로초라도 남았으면 반환하지 않는다 |
| 3b | `CONFIRMED`·`CANCELLED` 는 반환하지 않는다 |
| 4 | `expires_at` **오름차순** — 오래 묶인 것이 먼저 |
| 5 | `limit` 을 초과해 반환하지 않는다 |
| 6 | 대상이 없으면 빈 목록 (null 아님) |

> ⚠️ **#3 에 1나노초를 쓰면 안 된다.** H2 `TIMESTAMP` 기본 정밀도가 **마이크로초**라
> 나노초 차이는 저장 시 잘려 기준 시각과 같아지고, 그러면 #2 와 같은 것을 두 번 검증하게
> 된다. 도메인(L1)은 나노초까지 판정하지만 **영속화된 값의 최소 간격은 1마이크로초**다.
> 실 DB 전환 시 정밀도가 더 낮으면(MySQL `DATETIME` 은 기본 초 단위) 값을 다시 키워야 한다.
>
> #2 는 **포트와 도메인의 경계가 일치하는지**를 본다. 어긋나면 배치가 집어온 후보가
> 재확인에서 전부 걸러지거나(포트가 좁으면) 아직 유효한 신청을 집어 온다(넓으면).

### 8.4 L2 — 서비스 (`EnrollmentServiceTest`)

| # | 검증 |
|---|------|
| 1 | `reapExpired` 가 만료 건을 회수하고 `releaseSeat` 를 부른다 |
| 2 | **`klass` 락을 `enrollment` 락보다 먼저** 잡는다 (`InOrder` 검증) |
| 3 | 재확인에서 이미 `CONFIRMED` 면 `false`, 상태를 바꾸지 않는다 |
| 4 | 재확인에서 이미 `CANCELLED` 면 `false` |
| 5 | 대상이 사라졌으면 `false` (예외 아님) |
| 6 | **경계** — `expires_at == now` 면 재확인을 통과해 회수된다 (§8.3 #2 와 짝) |
| 7 | `OPEN` 강의면 대기 1순위가 승격되고 `enrollment_count` **순변화 0** |
| 8 | `CLOSED` 강의면 승격하지 않고 좌석이 빈 채로 남는다 (순변화 −1) |
| 9 | `findExpiredTargets` 가 설정된 상한을 포트에 넘긴다 |

### 8.5 L2 — 스케줄러 (`ExpiredEnrollmentSchedulerTest`)

| # | 검증 |
|---|------|
| 1 | 후보 전건에 대해 `reapExpired` 를 부른다 |
| 2 | **한 건이 예외를 던져도 나머지를 계속 처리한다** (FR-07 핵심) |
| 3 | 후보가 0건이면 `reapExpired` 를 부르지 않는다 |
| 4 | 스케줄러가 `EnrollmentQueryPort` 를 의존하지 않는다 (계층 규칙 — 생성자 시그니처로 확인) |

### 8.6 L2 — 설정 바인딩 (`EnrollmentPropertiesTest`)

**이 테스트는 이미 존재하며**, 중첩 `record` 가 `null` 로 바인딩되는 함정을 잡으려고 만들어졌다(`ConfigDataApplicationContextInitializer` 로 실제 `application.yml` 을 읽는다). §10.2 가 지적한 `reapBatchSize = 0` 함정도 **정확히 같은 부류**이므로 여기서 잡는다.

| # | 검증 |
|---|------|
| 1 | `reapBatchSize` 가 0 이 아니다 — `LIMIT 0` 이면 배치가 매번 0건을 조회하고 조용히 아무것도 하지 않는다 |
| 2 | `reapBatchSize` 가 `application.yml` 의 값과 일치한다 (`@DefaultValue` 가 실제 설정을 덮지 않는다) |

### 8.7 L3 — RestDocs

신규 엔드포인트가 없으므로 **문서 케이스는 늘지 않는다.** 기존 스니펫에 `cancelReason` 필드를 추가한다. `DocumentationIntegrationTest` 의 엔드포인트 개수 검증은 그대로 통과해야 한다 — 깨지면 의도치 않게 엔드포인트를 건드린 것이다.

### 8.8 L4 — 통합 (`EnrollmentFlowIntegrationTest`)

#### 8.8.1 만료 상태를 만드는 방법

**시각을 조작할 수 없다.** `ClockConfig` 가 `Clock.systemDefaultZone()` 을 빈으로 등록하고 `@SpringBootTest` 가 그 실제 시계를 쓴다. `apply` 는 `now + PT30M` 으로 `expires_at` 을 채우므로 **API 만으로는 만료 상태에 도달하지 못한다.**

`JdbcTemplate` 로 `expires_at` 을 과거로 백데이트한다.

```java
jdbcTemplate.update("update enrollment set expires_at = ? where id = ?",
        Timestamp.valueOf(LocalDateTime.now().minusMinutes(1)), enrollmentId);
```

**이 클래스의 기존 전제와 어긋나지 않는다.** 클래스 javadoc 이 "시각을 조작하지 않고 **데이터로 조건을 만든다**"고 못박았는데(취소 기간 0일, 종료일이 과거인 강의), 백데이트가 정확히 그 방식이다. `jdbcTemplate.update` 로 데이터를 직접 바꾸는 선례도 이미 있다(비활성 계정 시나리오, `:873`).

`expires_at` 만 바꾸므로 `ck_enrollment_pending`(`PENDING` 이면 NOT NULL)을 위반하지 않는다.

#### 8.8.2 시나리오

| # | 시나리오 |
|---|----------|
| 1 | **R-01 해소** — 정원 N 을 만료 `PENDING` 으로 채운 뒤 회수하면 다시 신청 가능해진다 |
| 2 | 만료 → 회수 → 대기 1순위 승격 → 새 `PENDING(source=WAITLIST)` 전 구간 |
| 3 | `CLOSED` 강의의 만료 `PENDING` 도 회수되며 승격은 없다 |
| 4 | 사용자 취소는 `USER`, 배치 회수는 `EXPIRED` 로 구분 조회된다 |
| 5 | **동시성** — 회수와 신규 신청을 동시 실행해도 정원 초과·중복 승격이 없다 |

#### 8.8.3 정합성 (`Integrity` 중첩 클래스)

`Integrity` 는 **같은 인메모리 DB 에 누적된 상태**를 검사한다 — JUnit 5 가 메서드 순서를 보장하지 않기 때문이다(`:1114`). 이 전제 위에서 단언을 설계한다.

| # | 검증 | 범위 |
|---|------|------|
| 6 | `enrollment_count` = `PENDING`+`CONFIRMED` 행 수 | 전역 (기존) |
| 7 | `ck_enrollment_pending` 위반 행 0 | 전역 (기존) |
| 8 | **신규** — `CANCELLED` 인데 `cancel_reason` 이 없는 행 0 | 전역 |
| 9 | **R-01 관측 강화** — 만료 `PENDING` 이 0 이다 | **회수 시나리오가 만든 강의로 한정** |
| 10 | **R-9 관측** — `source='WAITLIST'` 인 만료 `PENDING` 수 | 전역, 단언 없이 **관측만** |

**#9 를 전역에서 `isZero()` 로 강화하지 않는다.** 기존 단언은 의도적으로 `isGreaterThanOrEqualTo(0)` 이며(`:1188`) "0 이 아닐 수 있다"고 주석돼 있다. 다른 시나리오(특히 #5 동시성)가 회수되지 않은 만료 건을 남길 수 있고, 실행 순서가 보장되지 않으므로 전역 강화는 **간헐 실패**를 만든다. `where klass_id = ?` 로 범위를 좁혀 단언한다.

```sql
-- #9: 회수 시나리오가 만든 강의 안에서는 0 이어야 한다
select count(*) from enrollment
 where klass_id = ? and status = 'PENDING' and expires_at <= current_timestamp
```

**#10 은 R-9 의 관측 수단이다** (Plan §5 R-9). 승격됐지만 결제하지 않은 대기자 수이며, **승격 알림 부재의 실제 영향 규모**다. 단언하지 않고 값을 기록해 완료 보고서로 넘긴다 — 0 이어야 하는 값이 아니라 **알아야 하는 값**이다.

```sql
-- #10: 알림 없이 승격돼 만료된 대기자
select count(*) from enrollment
 where status = 'PENDING' and source = 'WAITLIST'
   and expires_at <= current_timestamp
```

## 9. 동시성 설계

### 9.1 락 획득 순서 (기존 표 확장)

| 유스케이스 | 1번째 | 2번째 | 3번째 | 비고 |
|-----------|-------|-------|-------|------|
| `apply` | `klass` | — | — | |
| `confirm` | `enrollment` | — | — | §4.1 예외 |
| `cancel` | `klass` | `enrollment` | `waitlist` | |
| `register` | `klass` | — | — | |
| `giveUp` | `waitlist` | — | — | §4.1 예외 |
| `cancelRemaining` | (호출자의 `klass`) | `waitlist` | — | |
| **`reapExpired`** | **`klass`** | **`enrollment`** | **`waitlist`** | **`cancel` 과 동일** |

`reapExpired` 는 `cancel` 과 **똑같은 순서**로 똑같은 대상을 잠근다. 새로운 락 경로가 생기지 않으므로 데드락 가능성이 늘지 않는다.

### 9.2 경합 시나리오

| # | 경합 | 결과 |
|---|------|------|
| 1 | 배치 회수 ↔ 사용자 결제 확정 | `confirm` 이 먼저면 재확인에서 걸려 배치가 skip. 배치가 먼저면 `confirm` 이 409 `INVALID_ENROLLMENT_STATUS_TRANSITION` |
| 2 | 배치 회수 ↔ 사용자 직접 취소 | 먼저 커밋된 쪽이 이기고 나머지는 skip / 409. 어느 쪽이든 좌석은 **한 번만** 반납된다 |
| 3 | 배치 회수 ↔ 신규 신청 | `klass` 락이 직렬화. 회수가 먼저면 신청자가 그 좌석을 얻을 수 있고, 신청이 먼저면 회수는 좌석을 반납만 한다 |
| 4 | 배치 회수 ↔ 대기 등록 | 동일. `klass` 락으로 직렬화 |
| 5 | 배치 회수 ↔ 강의 마감 | `changeStatus` 도 `klass` 락을 잡으므로 직렬화. 마감이 먼저면 승격이 일어나지 않는다 |
| 6 | 배치 두 인스턴스가 같은 건 | 하나가 락을 잡고 처리, 다른 하나는 재확인에서 skip. **정합성은 안전**하고 경합만 낭비된다 |

### 9.3 승격이 락 안에 있음을 지키는 장치

1. `promoteNextWaiting` 은 **`private` 그대로** — 다른 빈에서 부를 수 없다
2. `reapExpired` 가 **같은 클래스**의 메서드라 직접 호출한다 — 프록시를 타지 않으므로 전파가 끼어들 자리가 없다
3. `@Transactional` 에 **전파를 명시하지 않는다** — 기본 `REQUIRED`. `REQUIRES_NEW` 를 걸면 같은 `klass` 행을 두고 자기 자신과 경합한다

---

## 10. Configuration

### 10.1 application.yml

```yaml
app:
  enrollment:
    default-cancellation-period-days: 7
    pending-expiry:
      direct: PT30M
      waitlist: PT10M
    # 만료 회수 주기. @Scheduled 가 placeholder 로 직접 읽는다 (선례와 동일)
    reap-interval: PT10M
    # 한 사이클의 처리 상한. 남은 것은 다음 사이클이 가져간다
    reap-batch-size: 200
```

### 10.2 EnrollmentProperties

```java
@ConfigurationProperties(prefix = "app.enrollment")
public record EnrollmentProperties(int defaultCancellationPeriodDays,
                                   PendingExpiry pendingExpiry,
                                   @DefaultValue("200") int reapBatchSize) {
```

> ⚠️ **`@DefaultValue` 가 필수다.** `record` 의 `int` 컴포넌트는 yml 에 키가 없으면 예외 없이
> **0 으로 바인딩**된다. `reapBatchSize = 0` 이면 `LIMIT 0` 이 되어 배치가 **매번 0건을 조회하고
> 조용히 아무것도 하지 않는다.** 기동도 테스트도 통과하므로 드러나지 않는다 — 중첩 `record`
> 가 `null` 로 바인딩되는 함정(§4.1.1 ⑤)의 정수 버전이다.

`reapInterval` 은 `EnrollmentProperties` 에 **두지 않는다.** `@Scheduled` 가 placeholder 로만 읽으므로 둘 다 두면 설정 소스가 이중화되고, 한쪽만 고치는 실수가 열린다.

> **이 결정의 대가**: `${app.enrollment.reap-interval:PT10M}` 은 키를 못 찾으면 **예외 없이
> 기본값으로 대체된다.** `reap-intervals` 같은 오타가 나면 설정이 무시된 채 10분으로 도는데
> 어떤 테스트도 잡지 못한다. 받아들이는 이유는 **기본값이 곧 요구사항 값(10분)이라 오타의
> 실질 피해가 없기** 때문이다. 주기를 요구사항과 다르게 운영하기 시작하면 이 대가가 실제
> 위험이 되므로, 그때 `EnrollmentProperties` 로 옮기고 `EnrollmentPropertiesTest` 로 잡는다.

---

## 11. Implementation Guide

### 11.1 구현 순서

의존 방향을 따라 **안쪽부터** 만든다. 각 단계가 끝날 때 컴파일과 해당 계층 테스트가 통과해야 한다.

### 11.2 파일 목록

**신규 (5)**

| 파일 | 계층 |
|------|------|
| `enrollment/domain/CancelReason.java` | domain |
| `enrollment/application/port/in/ReapExpiredEnrollmentUseCase.java` | port.in |
| `enrollment/adapter/in/scheduler/ExpiredEnrollmentScheduler.java` | adapter.in |
| `enrollment/adapter/in/scheduler/ExpiredEnrollmentSchedulerTest.java` | test (L2) |
| — 통합 테스트는 기존 파일에 `@Nested` 로 추가 | |

**수정 (6 + 테스트)**

| 파일 | 변경 |
|------|------|
| `enrollment/domain/Enrollment.java` | `cancelReason` 필드 · `expire()` · `isExpiredAt()` · `cancel()` 한 줄 · CHECK 확장 |
| `enrollment/domain/error/EnrollmentError.java` | `ENROLLMENT_NOT_EXPIRED` |
| `enrollment/application/port/out/EnrollmentQueryPort.java` | `findExpiredIds` |
| `enrollment/adapter/out/persistence/EnrollmentQueryDslRepository.java` | 구현 |
| `enrollment/adapter/out/persistence/EnrollmentRepositoryAdapter.java` | 위임 |
| `enrollment/application/service/EnrollmentService.java` | `implements` 추가 · `findExpiredTargets` · `reapExpired` |
| `enrollment/application/EnrollmentProperties.java` | `reapBatchSize` |
| `application.yml` | `reap-interval` · `reap-batch-size` |
| `infrastructure/config/SchedulingConfig.java` | javadoc (등록 작업이 둘) |
| DTO 4종 (`EnrollmentResult` · `EnrollmentSummaryResult` · `KlassEnrollmentResult` + Response 3종) | `cancelReason` |
| `EnrollmentTest` | L1 도메인 (§8.1) |
| `EnrollmentSchemaTest` | L1 스키마 (§8.2) — **기존 네이티브 UPDATE 2건에 `cancel_reason` 추가 필수** |
| `EnrollmentRepositoryAdapterTest` | L2 어댑터 (§8.3) |
| `EnrollmentServiceTest` | L2 서비스 (§8.4) |
| `EnrollmentPropertiesTest` | L2 설정 바인딩 (§8.6) |
| `EnrollmentControllerTest` | L3 RestDocs (§8.7) |
| `EnrollmentFlowIntegrationTest` | L4 통합 (§8.8) |

### 11.3 Session Guide

| 모듈 | 범위 | 완료 기준 |
|------|------|-----------|
| **module-0** | `CancelReason` · `Enrollment`(필드·`expire`·`isExpiredAt`·`cancel`) · CHECK 확장 · `ENROLLMENT_NOT_EXPIRED` · L1 테스트 2종 | `EnrollmentTest` · `EnrollmentSchemaTest` 통과 |
| **module-1** | `EnrollmentQueryPort.findExpiredIds` · QueryDSL · 어댑터 위임 · **L2 어댑터 테스트(§8.3)** | `EnrollmentRepositoryAdapterTest` 통과 |
| **module-2** | `ReapExpiredEnrollmentUseCase` · `EnrollmentService` 구현 · **`EnrollmentProperties.reapBatchSize` · `application.yml` 의 `reap-batch-size`** · L2 서비스·설정 테스트(§8.4·§8.6) | `EnrollmentServiceTest` · `EnrollmentPropertiesTest` 통과 |
| **module-3** | `ExpiredEnrollmentScheduler` · `application.yml` 의 `reap-interval` · L2 스케줄러 테스트(§8.5) | 기동 성공 + 스케줄러 테스트 |
| **module-4** | DTO 4종 · Response 3종 · L3 RestDocs 필드 추가 | `./gradlew documentationTest` 통과 |
| **module-5** | L4 통합 테스트 9종 | `./gradlew build` 통과 |

**권장 세션 분할**: `module-0,1` → `module-2,3` → `module-4,5`

module-0 이 가장 크고(스키마 + 도메인 + 테스트 2종) 나머지가 전부 여기에 의존하므로, 첫 세션에서 확실히 끝내는 것이 좋다.

> ⚠️ **`reapBatchSize` 는 module-3 이 아니라 module-2 다.** §5.3 의 `findExpiredTargets()` 가
> `properties.reapBatchSize()` 를 호출하므로, 설정을 module-3 으로 미루면 **module-2 에서
> 컴파일이 깨진다.** `reap-interval` 은 스케줄러만 placeholder 로 읽으므로 module-3 에 남는다.

---

## 12. Divergence

원본(Plan · ERD 정본 · 기존 코드 관례)과 다른 지점을 전건 기록한다.

### D-47 — 승격을 Spring 이벤트로 발행하지 않는다

**Plan 요건 3 에서 이탈.** 원 요건은 "승격 처리를 Spring 이벤트로 해서 나중에 이벤트 기반 아키텍처 전환 시 유연하게"였다.

1. **동기 이벤트는 지금 실익이 없다** — 승격 트리거 둘(`cancel` · `reapExpired`)이 모두 `EnrollmentService` 안에 있다. 같은 클래스에서 `private` 메서드를 부를 수 있는데 이벤트를 끼우면 간접 계층만 늘어난다
2. **"나중 전환의 유연성"이 크지 않다** — 비동기 전환의 걸림돌은 이벤트 타입의 부재가 아니라 **승격이 `klass` 락 안에서 일어나야 한다는 사실**이다. `AFTER_COMMIT` 으로 옮기면 outbox · 재시도 · 멱등성 · 재락킹을 새로 설계해야 하며, 이벤트 record 를 미리 만들어 둔다고 그 작업이 줄지 않는다
3. **`private` 이 물리적 방어다** — 이벤트로 빼면 그 방어가 규약으로만 남고, `@TransactionalEventListener` 로 한 글자 바꾸면 컴파일도 테스트도 통과하면서 조용히 깨진다

**되살릴 조건**: 승격 알림(ERD §4.8) 도입 시, **알림 리스너에 한정.** 승격은 불변식이라 트랜잭션 안에, 알림은 부수효과라 커밋 후에 있어야 한다. 그 분리가 이벤트 도입의 올바른 지점이다.

### D-48 — 스케줄러를 `adapter/in/scheduler/` 에 둔다

auth 선례(`RevokedAccessTokenCleaner`)는 `application/service/` 에 있다. **다르게 간다.**

스케줄러는 시스템을 **바깥에서 구동하는 주체**이므로 헥사고날의 driving adapter 다. `adapter.in → port.in` 규칙이 그대로 적용되며, 실제로 이 규칙 덕분에 스케줄러가 `EnrollmentQueryPort`(out 포트)를 직접 주입하려던 초기 설계가 걸러졌다 — 후보 조회도 유스케이스를 경유하게 되어 계층이 정리됐다.

auth 쪽은 **이번에 옮기지 않는다.** 동작이 같고 위험이 없어 변경 자체가 비용이다. 다음에 그쪽을 손볼 때 함께 정리한다.

### D-49 — `ck_enrollment_cancelled` 를 양방향 제약으로 확장한다

기존은 `status <> 'CANCELLED' OR cancelled_at IS NOT NULL` 단방향이었다. `cancel_reason` 을 추가하며 **양방향**으로 바꾼다 — `CANCELLED` 가 아닌데 `cancel_reason` 이 있으면 거부한다.

근거: `cancel_reason` 은 신규 컬럼이라 기존 데이터 호환 문제가 없고, `ck_enrollment_pending` 이 이미 양방향 선례다. `cancelled_at` 의 단방향 성질은 확장식에 그대로 포함되므로 **기존 보장이 줄지 않는다.**

**대가 — 도메인을 우회하는 테스트 픽스처가 깨진다.** "데이터 호환 문제가 없다"는 *운영 데이터* 에 대한 이야기다. `EnrollmentSchemaTest` 는 스키마 성질을 검증하려고 네이티브 SQL 로 `CANCELLED` 행을 만드는데(`:324` · `:348`), 그 UPDATE 가 `cancel_reason` 을 채우지 않아 **제약이 붙는 순간 실패한다.** §8.2 에 갱신 방법을 명시했다.

### D-50 — 한 사이클의 처리 상한(`reap-batch-size`)을 도입한다

Plan 에 없던 설계 추가다. 만료가 폭증했을 때 한 번의 실행이 길어지는 것을 막는다. Plan NFR 의 "사이클당 `klass` 락 보유 시간 제한"을 실제로 보장하는 장치이며, `fixedDelay` 와 함께 작동해 남은 대상은 다음 사이클이 가져간다.

### D-51 — `Enrollment.expire()` 를 별도 메서드로 신설한다

Plan §6.2 는 `cancel(now, today, policy)` 에 취소 원인 인자를 추가하는 것을 전제하고 이를 **Breaking(R-3)** 으로 잡았다. 설계안 C 는 `expire()` 를 신설해 **그 변경 자체를 없앤다.**

- 기존 호출부·L1·L2·L4 테스트가 한 줄도 바뀌지 않는다 → **R-3 소멸**
- 만료는 취소 기간·강의 종료 관문이 무의미하므로 `today`·`policy` 를 받을 이유가 없다
- CLAUDE.md 의 "의도가 드러나는 메서드로만" 규약과 일치한다

부수적으로 `isExpiredAt()` 을 신설해 `confirm` 과 판정을 통합했다. 두 경로가 정확히 반대 조건에서 성립하므로 판정이 두 벌이면 경계에서 갈라진다.

### D-52 — 배치 클래스 이름을 `{역할}Scheduler` 로 한다

Plan §8.2 가 "`{역할}Reaper` vs `{역할}Cleaner` 통일 여부"를 이 사이클에서 정할 것으로 남겼다. **제3의 이름 `ExpiredEnrollmentScheduler` 를 쓴다.**

현재 이름이 세 갈래로 갈려 있었다.

| 출처 | 이름 |
|------|------|
| 문서 파일명·기능명 | `pending-expiry-reaper` |
| Plan §7.3 계층 배치 다이어그램 | `ExpiredEnrollmentReaper` |
| **이 설계 (확정)** | **`ExpiredEnrollmentScheduler`** |

근거: CLAUDE.md 의 클래스 접미사 표는 **"이름이 곧 위치이자 역할"** 을 원칙으로 한다. 이 클래스의 역할은 "회수"가 아니라 **"주기적으로 유스케이스를 구동하는 것"** 이다 — 회수 자체는 `EnrollmentService` 가 한다. `Reaper`·`Cleaner` 는 무엇을 하는지를 말하지만 **어떤 종류의 어댑터인지는 말하지 않는다.** `adapter/in/scheduler/` 에 놓기로 한 D-48 과 이름이 맞아떨어진다.

**이것이 선례가 된다.** 앞으로 `@Scheduled` 진입점은 `{대상}Scheduler` 로 하고 `adapter/in/scheduler/` 에 둔다. auth 의 `RevokedAccessTokenCleaner` 는 이번에 옮기지도 이름을 바꾸지도 않는다 (D-48 과 같은 이유 — 동작이 같고 변경 자체가 비용이다).

기능명(`pending-expiry-reaper`)은 문서 식별자이므로 그대로 둔다. 클래스명과 다른 것이 혼란스럽지만, 이미 아카이브된 문서·상태 파일이 그 이름을 참조하므로 바꾸는 비용이 더 크다.

---

## 13. 잔여 위험

이 설계가 **해소하지 않는** 것을 명시한다. 완료 보고서로 그대로 넘긴다.

### R-9 — 승격자가 승격 사실을 모른다 (High / High)

Plan §5 R-9 이자 이 사이클 유일의 High/High 리스크다.

```
대기 1순위 승격 → PENDING(10분) → 알림 없음 → 미결제 → 만료
    → 배치 회수 → 대기 2순위 승격 → PENDING(10분) → ...
```

`pendingExpiry.waitlist`(`PT10M`)와 `reap-interval`(`PT10M`)이 같은 값이라 **대기자 1명당 평균 15분·최대 20분**이 걸린다(만료 10분 + 배치 발견 0~10분). 대기자 3명이 연달아 결제하지 않으면 마지막 순번까지 최대 1시간이며, **대기열은 소진되고 좌석은 빈 채로 남는다.**

**이 사이클이 만드는 위험이 아니라 드러내는 위험이다.** 지금도 사용자 취소로 승격이 일어나면 똑같이 알림 없이 만료되고 있다. 다만 빈도가 낮아 보이지 않았을 뿐이며, 배치가 연쇄를 자동으로 돌리면서 전면에 나온다.

**관측 수단**: §8.8.3 #10 — `source = 'WAITLIST'` 인 만료 `PENDING` 수. 단언하지 않고 값을 기록한다.

**해소 시점**: 승격 알림(ERD §4.8) 도입 시. D-47 의 "이벤트를 되살릴 조건"과 정확히 같은 시점이다 — 알림은 부수효과이므로 `AFTER_COMMIT` 리스너가 맞고, 그때 이벤트가 제값을 한다.

### 그 밖에 유지되는 공백

| 항목 | 근거 |
|------|------|
| 정원 증가 시 대기열 승격 (D-33) | `changeCapacity` 가 `DRAFT` 에서만 호출돼 도달 불가 |
| `CLOSED → OPEN` 재모집 | D-18 이 봉쇄. 역전이를 여는 설계가 선행돼야 한다 |
| 다중 인스턴스 배치 | 단일 인스턴스 전제(§5.1). 정합성은 안전하나 경합이 낭비된다 |
| 외부 결제 연동 | ERD §1.3. `confirm` 은 여전히 결제 성공을 가정 |

---

## Version History

| Version | Date | Changes | Author |
|---------|------|---------|--------|
| 0.1 | 2026-09-04 | 최초 작성. 설계안 C(Pragmatic) 선택. D-47~D-51 기록 | developer2@lulumedic.com |
| 0.4 | 2026-09-04 | L2 경계 테스트의 DB 정밀도 제약 명시(§8.3) — H2 `TIMESTAMP` 는 마이크로초라 나노초 경계를 저장하지 못한다 | developer2@lulumedic.com |
| 0.3 | 2026-09-04 | **만료 경계 정정** — `expiresAt == now` 는 "아직 유효"가 아니라 **이미 만료**다. 기존 `confirm` 의 조건식이 그러하며 문서가 틀렸다. 포트·도메인 경계가 "의도된 불일치"가 아니라 **일치**임을 §5.4·§8.3 에 반영 (module-0 구현 중 L1 테스트가 발견) | developer2@lulumedic.com |
| 0.2 | 2026-09-04 | design-validator 검증 반영 — §8 전면 개정(어댑터·설정 절 신설, L4 만료 생성 수단·정합성 범위 명시), C-2 모듈 순서 교정, D-52 네이밍 확정, §13 잔여 위험(R-9) 신설 | developer2@lulumedic.com |
