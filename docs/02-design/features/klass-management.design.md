# 강의 관리 기능 설계서

> **Summary**: 강의 명령 3종(등록·수정·상태 전이)과 조회 3종(상세·공개 목록·내 강의 목록)을 헥사고날 전 계층에 설계한다. 스키마는 ERD 정본이 확정했으므로 이 문서가 정하는 것은 **행위의 배치** — 어떤 규칙이 도메인에 있고, 어떤 검사가 서비스에 있고, 무엇이 설정으로 내려가는가.
>
> **Project**: klass
> **Version**: 0.0.1-SNAPSHOT
> **Author**: developer2@lulumedic.com
> **Date**: 2026-09-02
> **Status**: Verified (Check 0.5 — Match Rate 93%)
> **Planning Doc**: [klass-management.plan.md](../../01-plan/features/klass-management.plan.md)
> **데이터 모델 정본**: [class-enrollment-erd.design.md](./class-enrollment-erd.design.md)

---

## Context Anchor

> Plan 에서 복사. Design→Do 인수인계에서 맥락이 유실되지 않게 한다.

| Key | Value |
|-----|-------|
| **WHY** | 강의를 만들·고칠·볼 경로가 없어 수강신청 도메인이 착지할 대상이 존재하지 않는다 |
| **WHO** | 크리에이터(ROLE_CREATOR) — 등록·수정·상태 전이·내 강의 목록 / 일반 사용자·비로그인 방문자 — 공개 목록·상세 조회 |
| **RISK** | `ROLE_CREATOR` 권한 검사만 통과하면 **남의 강의를 수정할 수 있는** 수평 권한 상승 (소유권 검사 누락) |
| **SUCCESS** | 타 크리에이터의 강의 수정·상태 전이 시도가 403 / 금지된 상태 전이 4종이 전부 거부 / DRAFT 가 공개 목록·타인 상세 조회에서 완전히 비노출 / 커서 목록이 중복·누락 없이 이어짐 |
| **SCOPE** | Phase 1 도메인 행위(전이·수정 메서드 + L1) → Phase 2 포트·서비스·어댑터(소유권·커서 + L2) → Phase 3 컨트롤러 + RestDocs 6종(L3) → Phase 4 스키마 개정(`updated_at`)·통합 검증(L4/L5) |

---

## 1. Overview

### 1.1 설계 목표

1. **소유권 검사를 빠뜨릴 수 없는 구조로 만든다.** `ROLE_CREATOR` 만 검사하면 크리에이터 A 가 B 의 강의를 수정할 수 있다. 권한(설정)과 소유권(코드)을 **다른 계층**에 두되, 소유권 검사가 지나가는 길을 하나로 좁혀 누락이 눈에 띄게 한다.
2. **상태 전이 규칙을 도메인 안에 둔다.** 서비스가 `if (status == DRAFT)` 로 분기하면 규칙이 호출처마다 복제된다. 전이는 `Klass` 의 메서드로만 일어난다.
3. **컴파일러가 잡을 수 있는 것을 런타임으로 미루지 않는다.** CLAUDE.md 가 지목한 "컴파일러가 못 잡는 3종" 중 파생 쿼리 메서드명을 QueryDSL 로 컴파일 타임에 옮긴다.
4. **선택적 인증을 이 저장소의 세 번째 경로 종류로 명문화한다.**

### 1.2 설계 원칙

- **판단은 도메인, 조립은 서비스, 형식은 어댑터.** 정원 축소 가부는 `Klass` 가 알고, "이 요청자가 이 강의의 주인인가"는 서비스가 조립하며, 그것이 403 인지 404 인지는 에러 코드가 정한다.
- **읽기 규칙과 쓰기 규칙을 같은 곳에서 표현한다.** `DRAFT` 는 목록에서 빠지고 상세에서 404 다. 둘이 다른 곳에 흩어지면 한쪽만 고쳐진다.
- **없는 것을 만들지 않는다.** 대기열이 없으므로 승격도 없다. 다만 붙을 자리를 주석으로 표시한다.

### 1.3 범위 외 (명시)

Plan §2.2 와 동일. 특히 **`enrollment_count` 를 쓰는 코드는 이 설계에 없다** — 읽기만 한다.

---

## 2. Architecture Options

### 2.0 아키텍처 비교

헥사고날 골격은 CLAUDE.md 가 고정한다. 실제로 갈리는 축은 셋이었다 — 포트 분할 정도 / 커서 조회 구현 수단 / 수정 요청 표현.

> **"부분 수정 표현" 축은 나중에 전제 자체가 뒤집혔다** — 수정은 부분 수정이 아니라 전체 필수 수신이 됐고, 그래서 `Optional<T>` 도 걷어냈다 (D-25). 아래 표는 선택 시점의 기록이다.

| 기준 | A. 최소 변경 | B. 완전 분리 | **C. 실용적 균형** |
|------|:-:|:-:|:-:|
| 인바운드 포트 | 2개 (Command/Query) | 5개 + 서비스도 분리 | **5개** |
| 서비스 | 1개 | Command/Query 2개 | **1개** |
| 커서 조회 | Spring Data 파생 쿼리 | QueryDSL + 프로젝션 직접 조회 | **QueryDSL (엔티티 반환)** |
| 부분 수정 | `null` = 미지정 | `Optional<T>` | ~~`Optional<T>`~~ → **전체 필수 수신** (D-25) |
| 신규 파일 | ~16 | ~29 | **~22** |
| 복잡도 | 낮음 | 높음 | 중간 |
| 위험 | 파생 쿼리 메서드명 오타 = **부트스트랩 실패** | 프로젝션이 도메인 규칙을 우회 | QueryDSL 첫 배선 |

**Selected: Option C** — 근거는 둘이다.

**① 목록 조회의 조건 조합이 파생 쿼리로는 폭발한다.** 공개목록 / `?status=OPEN` 지정 / 내 강의목록 세 갈래에 각각 커서 유무가 갈려 메서드가 6개가 된다. CLAUDE.md 가 "컴파일러가 잡지 못하는 지점" 3종 중 하나로 파생 쿼리 메서드명을 꼽고 있고(엔티티 속성명과 어긋나면 **앱이 기동조차 못 한다**), A안은 그 위험 표면을 6배로 늘린다.

```java
// A안 — 조합마다 메서드가 필요하다. 이름이 곧 계약이고, 컴파일러는 검사하지 않는다
findTop21ByStatusInOrderByIdDesc(...)
findTop21ByStatusInAndIdLessThanOrderByIdDesc(...)
findTop21ByCreatorIdOrderByIdDesc(...)
findTop21ByCreatorIdAndIdLessThanOrderByIdDesc(...)   // ... 6개

// C안 — 조건을 조립한다. QKlass 가 컴파일 타임에 속성명을 검증한다
where(statusIn(statuses), creatorEq(creatorId), cursorLt(cursor))
    .orderBy(klass.id.desc()).limit(size + 1)
```

**② QueryDSL 이 이미 도입돼 있는데 실사용처가 0건이다.** 1차에서 `build.gradle` 에 넣고(Design §12 D-3) 스파이크로 판정만 했다. `QKlass` 가 생성되고 있으므로 배선(`JPAQueryFactory` 빈)만 추가하면 된다. 도입해 두고 안 쓰는 의존성은 다음 사람에게 "쓰면 안 되는 건가"를 묻게 만든다.

**B안을 택하지 않은 이유**: 프로젝션 DTO 를 직접 조회하면 엔티티를 우회하는데, 이 설계는 `DRAFT` 노출 규칙 같은 판단이 도메인에 있어야 한다는 입장이다(§1.2). 성능 이득도 20건 페이지에서는 측정되지 않는다.

### 2.1 컴포넌트 구조

```
                       adapter.in.web
                    ┌──────────────────┐
   HTTP ──────────▶ │  KlassController │
                    └────────┬─────────┘
                             │ port.in (5)
                             ▼
                    ┌──────────────────┐        ┌──────────────┐
                    │   KlassService   │───────▶│    Klass     │  ← 전이·수정 규칙
                    │  (소유권 조립)     │        │  (domain)    │     정원 검사
                    └────────┬─────────┘        └──────────────┘
                             │ port.out (2)
              ┌──────────────┴───────────────┐
              ▼                              ▼
   ┌────────────────────┐        ┌──────────────────────┐
   │ KlassCommandPort   │        │   KlassQueryPort     │
   └─────────┬──────────┘        └──────────┬───────────┘
             └──────────────┬───────────────┘
                            ▼  adapter.out.persistence
              ┌───────────────────────────────┐
              │    KlassRepositoryAdapter     │
              ├───────────────┬───────────────┤
              │ KlassJpa      │ KlassQueryDsl │
              │ Repository    │ Repository    │  ← 커서·동적 조건
              │ (단건·락)      │               │
              └───────────────┴───────────────┘
```

### 2.2 데이터 흐름 — 세 갈래

**① 명령 (등록)**
```
Request → @Valid → Command → 서비스: creator 조회 → Klass.open(clock)
                                                   → CommandPort.save → Response
```

**② 명령 (수정·전이) — 락이 붙는다**
```
Request → Command → 서비스: QueryPort.findByIdForUpdate(id)   ← SELECT ... FOR UPDATE
                          → klass.isOwnedBy(sub) 아니면 403
                          → klass.publish(now) / klass.changeCapacity(n, now)
                          → (dirty checking) → Response
```

**③ 조회 — viewerId 가 null 일 수 있다**
```
Request(토큰 有/無) → principal?.id() → viewerId (nullable)
                   → 서비스: QueryPort.find*(viewerId, cursor, size)
                   → DRAFT 필터링이 쿼리 조건에 들어감 → Response
```

### 2.3 의존 관계

| 컴포넌트 | 의존 대상 | 목적 |
|----------|-----------|------|
| `KlassController` | `port.in` 5종, 자신의 DTO | HTTP ↔ Command 변환 |
| `KlassService` | `domain`, `port.out` 2종, `Clock`, `UserQueryPort` | 소유권 조립, 트랜잭션 경계 |
| `KlassRepositoryAdapter` | `domain`, `port.out`, JPA·QueryDSL | 영속화 |
| `Klass` | JPA/Jakarta 어노테이션, JDK | 상태 전이·정원 규칙 |

> **`KlassService` → `UserQueryPort`**: 등록 시 `Klass.creator` 에 넣을 `User` 엔티티가 필요하다. `user` 도메인의 아웃바운드 포트를 재사용하며, 이는 `application.service` 가 다른 도메인의 **포트**를 참조하는 것이라 의존 규칙 위반이 아니다 (금지 대상은 `adapter.*`).
>
> `UserQueryPort.findById` 가 비어 있으면 `UserError.USER_NOT_FOUND` 를 그대로 쓴다. 토큰은 유효한데 사용자가 사라진 상황이고, 그것은 강의 도메인의 사건이 아니다.

### 2.4 락은 이번 범위에서 걷어냈다 (D-21)

원 설계는 수정·상태 전이가 `SELECT ... FOR UPDATE` 로 `klass` 행을 잡도록 했다 (ERD 정본 §4.1 락 순서 규약). **구현하면서 그 락이 지금은 아무것도 막지 않는다는 것이 드러나 제거했다.**

**락이 막으려던 상대는 수강신청 트랜잭션이다.** ERD §4.2 가 같은 `klass` 행을 읽고 쓴다.

```
수강신청:  1. SELECT ... FOR UPDATE            ← klass 행 배타 락
          2. status = 'OPEN' 확인               ← 상태를 읽는다
          4. enrollment_count >= capacity 확인  ← 정원을 읽는다
          6. UPDATE klass SET enrollment_count = +1   ← 카운터를 쓴다
```

즉 경합은 **크리에이터 ↔ 수강생** 사이에서 일어난다. 본인이 자기 강의를 고치는 것끼리는 경합하지 않는다 — 소유권 검사가 이미 다른 사람을 배제한다.

**수강신청이 2차 범위이므로 그 상대가 존재하지 않는다.** `enrollment_count` 를 쓰는 코드가 이 사이클에 단 한 줄도 없다.

#### 작업별로 필요도가 다르다

| 작업 | `enrollment_count` 를 읽나 | 락이 필요한가 |
|------|:-:|------|
| `changeCapacity` | ✅ | **수강신청이 붙으면 반드시** |
| `publish` / `close` | ❌ | 신청의 `status` 검사와 직렬화하려면 유용 |
| 제목·내용·가격·기간 수정 | ❌ | **어느 시점에도 불필요** |

세 번째 줄이 결정적이었다. 락을 `loadForCommand` 에 두면 **제목만 바꾸는 요청도 배타 락을 잡는다** — 그건 수강신청이 붙은 뒤에도 정당화되지 않는다.

`publish`/`close` 의 근거도 약해졌다. ERD §4.8 이 상태 전이에 락을 요구한 이유는 "조건부 전이 두 개(`OPEN→DRAFT`, `CLOSED→OPEN`)가 `enrollment_count` 를 읽기 때문"이었는데, **이 설계는 그 두 전이를 D-18 로 차단했다.** 남은 전이는 `status` 만 본다.

#### 2차에서 되살릴 자리

되돌아올 좌표를 코드 세 곳에 남겼다 — 이 결정의 관건이다.

| 위치 | 남긴 것 |
|------|---------|
| `KlassService.loadForCommand` javadoc | 왜 걷어냈는지 + **정원 축소가 깨지는 시나리오 예시** + 되살릴 메서드명 |
| `KlassQueryPort` 클래스 javadoc | `findByIdForUpdate` 를 여기 되살려야 한다는 표시 |
| `KlassJpaRepository` 클래스 javadoc | `@Lock` + **`@EntityGraph` 를 함께 붙이면 안 되는 이유** |

```
정원 10, 현재 9명
  [크리에이터] 정원을 9로 → enrollment_count 읽음 = 9 → "9 >= 9 OK"
  [수강생]     10번째 신청 성공 → count = 10
  [크리에이터] UPDATE capacity = 9 → count(10) > capacity(9)  ✗
```

`ck_klass_count` 가 최종 거부하지만 **사용자는 이유를 알 수 없다.** 앱이 먼저 막아야 하고, 그러려면 락이 필요하다.

> ⚠️ **락을 되살릴 때 `@EntityGraph` 를 함께 붙이면 안 된다.** Hibernate 가 조인된 `users` 행까지 `FOR UPDATE` 를 걸면 ERD 정본 §4.1 이 고정한 "락 대상은 `klass` 단일 행" 규약이 깨진다. 같은 사용자가 개설한 두 강의를 동시에 수정하면 `users` 행에서 경합이 생기고, 그것이 §4.1 이 데드락을 배제한 근거를 무너뜨린다. 소유권 검사는 프록시의 `getId()` 만 건드려 초기화를 유발하지 않으므로 조인이 필요 없다.
>
> 이 성질을 `KlassRepositoryAdapterTest.ownershipCheckDoesNotTriggerQuery` 가 고정한다 — **단, 어댑터의 `findById` 로 검증하면 안 된다.** 그쪽은 `@EntityGraph` 로 개설자를 함께 읽으므로 `creator` 가 이미 초기화된 채 와서, `isOwnedBy` 가 무엇을 하든 추가 쿼리가 0이다. **구현을 어떻게 바꿔도 실패시킬 수 없는 테스트**가 된다. 그래서 `JpaRepository.findById`(조인 없음)로 읽어 프록시 상태에서 검사하고, 사전 조건(`isInitialized == false`)까지 단언한다. Check 단계에서 이 결함이 발견돼 고쳤다.

---

## 3. Data Model

### 3.1 스키마 변경 — 2건

ERD 정본 §3.2.5 를 개정한다.

| 컬럼 | 변경 | NULL | 설명 |
|------|------|:----:|------|
| `updated_at` | **추가** | N | 최종 수정 시각. 생성 시 `created_at` 과 같은 값으로 채운다 |
| `description` | NULL 허용 → **NOT NULL** | N | 강의 내용. **필수값** (§4.3, D-18) |

**`description` 을 필수로 올린 근거** (D-18). 원 요구사항은 등록 항목으로 "제목, **내용**, 가격, 정원, 수강 기간"을 나열했고 내용만 선택이라는 단서가 없었다. ERD 가 nullable 로 둔 것은 보수적 선택이었다.

등록·수정 양쪽에서 `null` 도 공백도 받지 않는다. **"내용을 비운다"라는 요청은 성립하지 않는다** — 내용을 줄이고 싶으면 짧은 내용을 보내는 것이지 비우는 것이 아니다 (§4.3).

> 이 절의 초안은 "필수로 올리면 부분 수정에서 `null` 의 중의성이 사라진다"를 근거의 절반으로 삼았다. **수정이 전체 필수 수신이 되면서 그 논거는 무의미해졌다**(D-25) — 애초에 중의성이 생기지 않기 때문이다. D-18 자체는 유효하다. 근거는 위의 원 요구사항 하나로 충분하다.

**NOT NULL 로 정한 근거** (Plan §7.2 잠정 결정 해소):

NULL 허용은 "한 번도 수정된 적 없음"을 표현할 수 있어 직관적으로 보인다. 그런데 그 정보는 **`created_at == updated_at` 으로 이미 표현된다.** 반면 NULL 을 허용하면 대가가 번진다 — 응답 DTO 가 null 을 다뤄야 하고, 나중에 "최근 수정순" 정렬을 붙이면 NULL 정렬 순서를 DB 방언별로 신경 써야 한다. 없는 정보를 표현하려고 NULL 을 들이는 게 아니라, **이미 표현되는 정보를 위해 NULL 을 들이는 것**이므로 순이익이 없다.

```sql
-- ERD 정본 §3.7 DDL 변경
description  TEXT       NOT NULL,   -- NULL 허용에서 변경
updated_at   TIMESTAMP  NOT NULL,   -- 신규
```

> **개정 대상은 정본 세 곳이다** — §3.1 mermaid `klass` 블록 / §3.2.5 컬럼표 / §3.7 DDL. 세 곳이 어긋나면 다음 사람이 어느 것을 믿을지 알 수 없다. Version History 도 함께 갱신한다.

> **인덱스는 추가하지 않는다.** `updated_at` 정렬 조회 요건이 없다. 요건이 생기면 그때 붙인다.
>
> **`idx_klass_status(status, id DESC)` 도 그대로 둔다 — 다만 전제가 좁았다는 사실을 기록한다.** 이 인덱스는 ERD §3.6 에서 `WHERE status = ? ORDER BY id DESC` 라는 **단일 상태 조회**를 상정해 설계됐다. 그런데 공개 목록의 기본 조건은 `status IN ('OPEN','CLOSED')` 이므로 블록이 둘이고, 전체 `id DESC` 순서를 만들려면 두 range 를 병합해야 한다. 2값 병합은 DB 가 일상적으로 처리하고 이 규모에서는 측정되지 않으므로 감수하되, **강의가 수만 건에 이르면 `(id DESC, status)` 인덱스를 재검토한다.** 결정을 기록해 두지 않으면 나중에 "왜 인덱스가 안 먹지"를 처음부터 다시 조사하게 된다.

### 3.2 도메인 메서드 설계

`Klass` 에 추가되는 것들. **모두 `Clock` 산출값을 파라미터로 받는다** — 도메인이 Spring 을 모르므로 `@CreatedDate` 를 못 쓰고, 무인자 `now()` 는 금지다.

```java
// ── 상태 전이 (ERD 정본 §3.4 전이표) ──────────────────────────
public void publish(LocalDateTime now)   // DRAFT → OPEN
public void close(LocalDateTime now)     // DRAFT | OPEN → CLOSED  (§3.3)

// ── 내용 수정 ────────────────────────────────────────────────
public void changeTitle(String title, LocalDateTime now)
public void changeDescription(String description, LocalDateTime now)
public void changePrice(BigDecimal price, LocalDateTime now)
public void changePeriod(LocalDate startsOn, LocalDate endsOn, LocalDateTime now)
public void changeCapacity(int capacity, LocalDateTime now)
public void changeCancellationPeriodDays(Integer days, LocalDateTime now)  // DRAFT 에서만 (D-26)

// ── 판별 ────────────────────────────────────────────────────
public boolean isOwnedBy(Long userId)
public boolean isVisibleTo(Long viewerId)
```

**팩토리 `open()` 의 시그니처는 바꾸지 않는다** (Plan §6.2 가 Design 판단으로 넘긴 건). 기존 `createdAt` 파라미터 하나를 받아 `createdAt` 과 `updatedAt` **양쪽에 넣는다.** §3.1 의 NOT NULL 근거와 같은 논리다 — "수정된 적 없음"이 `createdAt == updatedAt` 으로 표현되므로 별도 인자가 필요 없다. 기존 호출자(`EnrollmentSchemaTest`)도 그대로 컴파일된다.

**전이 메서드를 `changeStatus(KlassStatus)` 하나로 두지 않은 이유.** 그렇게 하면 허용 여부 판단이 메서드 **안의 조건문**으로 들어가고, 호출부는 어떤 전이가 가능한지 알 수 없어 시그니처가 거짓말을 한다. `publish()` / `close()` 는 이름이 곧 전이이고, **존재하지 않는 전이는 호출할 메서드가 없다.** 역전이 2종(`OPEN→DRAFT`, `CLOSED→OPEN`)이 이번 범위 밖인 것이 코드에 그대로 드러난다 (Plan §3.3).

**`changePeriod` 가 두 날짜를 함께 받는 이유.** `startsOn` 만 바꾸면 `ends_on >= starts_on` (`ck_klass_period`) 를 깰 수 있다. 함께 받아 도메인이 먼저 검사하면 CHECK 제약까지 가지 않는다 — CHECK 는 최종 방어선이지 1차 방어선이 아니다.

**`changeCancellationPeriodDays` 만 상태를 본다 — `DRAFT` 에서만 값을 바꿀 수 있다** (D-26).

```
changeCancellationPeriodDays(days):
  1. Objects.equals(현재 값, days)  → 아무것도 하지 않고 반환 (no-op)
  2. status != DRAFT                → CANCELLATION_PERIOD_NOT_EDITABLE (409)
  3. this.cancellationPeriodDays = days;  this.updatedAt = now
```

취소 가능 기간은 **수강생과의 약속**이다. `OPEN` 이 되어 신청자가 생긴 뒤에 바꾸면 **이미 신청한 사람의 취소 조건이 사후에, 그리고 불리하게 바뀔 수 있다.** `DRAFT` 는 애초에 신청을 받지 않으므로(ERD 정본 §2.2 — `OPEN` 만 신청을 받는다) 약속의 상대가 아직 없어 안전하다. 그래서 다른 `change*` 메서드와 달리 이 하나만 자신의 상태를 참조한다.

**1번(같은 값 no-op)이 없으면 `OPEN` 강의를 통째로 못 고친다.** 수정은 전체 교체이므로(D-25) 모든 수정 요청이 이 필드를 **항상 싣고 오고**, `KlassService.update` 는 이 메서드를 **무조건 호출**한다. 상태만 보고 무조건 거부하면 `OPEN` 강의의 제목만 바꾸려는 요청까지 409 가 된다. 전체 교체 규약에서 클라이언트가 바꾸지 않은 필드에 현재 값을 그대로 실어 보내는 것은 정상 동작이므로, **같은 값 재전송은 변경이 아니다.** no-op 경로에서 `updatedAt` 을 건드리지 않는 것도 같은 이유다 — "매 요청이 수정"이라는 전체 교체 규약은 `update` 가 호출하는 다른 `change*` 메서드가 이미 지킨다.

**`null` 전환도 변경이다.** `null` 은 "전역 기본값을 따른다"는 뜻이며 그 자체가 하나의 약속이다. 따라서 `null → 값` 과 `값 → null` 양쪽 다 조건을 바꾸는 일이고 `DRAFT` 에서만 허용된다. 비교에 `Objects.equals` 를 쓰는 이유가 여기 있다 — 필드가 `Integer` 라 `==` 는 박싱 캐시 범위(-128~127) 밖에서 조용히 틀리고, `this.cancellationPeriodDays.equals(...)` 는 현재 값이 `null` 일 때 NPE 다. 양쪽 모두 `null` 일 수 있다.

### 3.3 상태 전이 검증

```
                    publish()
        ┌──────────────────────────▶ OPEN
        │                             │
      DRAFT                           │ close()
        │                             ▼
        └──────────────────────────▶ CLOSED
                    close()          ▲ │
                  (개설 철회)          └─┘ ✗ CLOSED → OPEN
                                          ERD §3.4: 초기 구현 차단
        ▲                                 (대기자 유령 행 문제)
        └────────── ✗ OPEN → DRAFT ──────┘  D-18
```

**허용 3종** (ERD 정본 §4.8 화이트리스트와 일치): `DRAFT → OPEN` · `DRAFT → CLOSED` · `OPEN → CLOSED`.

`DRAFT → CLOSED` 는 **개설 철회**다. 이 설계에는 물리 삭제가 없으므로(ERD §2), 공개하지 않기로 한 초안을 정리하는 유일한 수단이 이 전이다. 신청자가 있을 수 없어(`DRAFT` 는 신청 불가) 안전하다.

거부 대상:

| 시도 | 현재 상태 | 결과 |
|------|-----------|------|
| `publish()` | OPEN | 409 (이미 공개됨) |
| `publish()` | CLOSED | 409 (역전이 금지 — `CLOSED → OPEN`) |
| `close()` | CLOSED | 409 (이미 마감됨) |
| 목표 상태 = `DRAFT` | 모든 상태 | 409 (`OPEN → DRAFT` 는 D-18, `CLOSED → DRAFT` 는 ERD 도 금지) |

따라서 도메인 메서드의 전제는 이렇게 된다.

```java
public void publish(LocalDateTime now)  // 전제: status == DRAFT
public void close(LocalDateTime now)    // 전제: status != CLOSED   ← DRAFT·OPEN 양쪽에서 가능
```

### 3.4 정원 수정 규칙 (ERD 정본 §4.8)

```
changeCapacity(n):
  1. n <= 0                    → 도메인 예외 (ck_klass_capacity 와 같은 규칙)
  2. n < this.enrollmentCount  → CAPACITY_BELOW_ENROLLMENT (409)
  3. this.capacity = n
  ── 2차에서 여기에 붙는다 ────────────────────────────────
  4. n 이 증가했고 status == OPEN 이면, 늘어난 자리만큼 대기열 승격
     (ERD §4.8 capacity 5번. 지금은 waitlist 가 없어 발현 불가)
```

**4번을 주석으로 남기는 이유**: 대기자가 생긴 뒤 정원을 올리면 신규 신청자가 대기자를 앞지른다. 지금은 대기열 자체가 없어 피해가 없지만, 수강신청 사이클에서 이 지점을 다시 찾아와야 한다. 찾아올 좌표를 코드에 남긴다.

**같은 이유로 `close()` 에도 좌표를 남긴다.** ERD §4.8 상태 전이 트랜잭션 5번은 `CLOSED` 로 전이할 때 잔여 `WAITING` 행을 전부 `CANCELLED` 로 정리하도록 규정한다 — 남겨두면 영구히 승격되지 않는 유령 행이 되기 때문이다. 대기열이 없어 지금은 발현하지 않는다 (D-16).

### 3.5 조회 가시성 규칙

**가시성 판단은 `isVisibleTo` 하나, 목록 노출 범위는 엔드포인트가 정한다 — 둘은 다른 질문이다.**

"이 사람이 이 강의를 볼 수 있는가"(가시성)와 "이 목록은 무엇을 보여주는 화면인가"(노출 범위)는 구분해야 한다. D-14 로 목록을 둘로 나눈 순간 후자는 엔드포인트별로 갈라졌고, 그것이 정상이다. 전자만 한 곳에 있으면 된다.

```java
// Klass.isVisibleTo — 상세 조회가 쓴다
public boolean isVisibleTo(Long viewerId) {
    return status != KlassStatus.DRAFT || isOwnedBy(viewerId);
}
```

| 조회 | viewerId | 보이는 것 |
|------|----------|-----------|
| 공개 목록 `GET /v1/klasses` | null (비로그인) | `OPEN`, `CLOSED` |
| 공개 목록 | 있음 | `OPEN`, `CLOSED` (**본인 DRAFT 도 안 보인다** — 아래 참조) |
| 내 강의 목록 `GET /v1/klasses/me` | 필수 | 본인 개설분 전부 (`DRAFT` 포함) |
| 상세 `GET /v1/klasses/{id}` | null 또는 있음 | `isVisibleTo(viewerId)` 가 false 면 **404** |

> **공개 목록에서 본인 DRAFT 를 빼는 이유.** ERD §7 은 "토큰이 있으면 `creator_id == sub` 인 DRAFT 도 포함" 이라고 적혀 있지만, 그것은 **목록이 하나뿐이던 전제**에서 쓰인 문장이다. 이 설계는 목록을 둘로 나눴으므로(Plan Checkpoint 1) 초안은 `/me` 가 담당한다. 공개 목록에 내 초안이 섞이면 크리에이터가 보는 화면과 사용자가 보는 화면이 달라져, 크리에이터는 자기 강의가 남에게 어떻게 보이는지 확인할 수 없다. **이 문서가 ERD §7 을 이 지점에서 좁힌다** (§9 divergence D-14).

---

## 4. API Specification

### 4.1 엔드포인트 목록

| # | Method | Path | 설명 | 인증 |
|---|--------|------|------|------|
| 1 | POST | `/v1/klasses` | 강의 등록 | `ROLE_CREATOR` |
| 2 | **PUT** | `/v1/klasses/{id}` | 강의 **전체 교체** (D-25 · D-27) | `ROLE_CREATOR` + 소유권 |
| 3 | PATCH | `/v1/klasses/{id}/status` | 강의 상태 수정 | `ROLE_CREATOR` + 소유권 |
| 4 | GET | `/v1/klasses/{id}` | 상세 조회 | **선택적** |
| 5 | GET | `/v1/klasses` | 공개 목록 | **선택적** |
| 6 | GET | `/v1/klasses/me` | 내 강의 목록 | `ROLE_CREATOR` |

> **경로 순서 주의**: `/v1/klasses/me` 가 `/v1/klasses/{id}` 보다 **먼저** 매칭돼야 한다. Spring MVC 는 리터럴 경로를 변수 경로보다 우선하므로 자동으로 해결되지만, `{id}` 가 `String` 이면 모호해질 수 있다. **`{id}` 는 `Long` 으로 받는다** — 타입이 다르면 `/me` 요청이 `{id}` 로 흘러도 400 이 아니라 애초에 매칭되지 않는다.

### 4.2 선택적 인증 — 이 저장소의 세 번째 경로 종류

지금까지 경로는 두 종류였다.

| 종류 | 설정 | 토큰 없이 요청 |
|------|------|----------------|
| 공개 | `PUBLIC_ENDPOINTS` + `permitAll` | 200 (토큰이 의미 없는 경로) |
| 보호 | `anyRequest().authenticated()` | 401 |
| **선택적** ← 신규 | `permitAll` + **서비스가 viewerId null 을 다룸** | **200, 보이는 범위가 줄어듦** |

**필터 쪽은 이미 준비돼 있다.** `JwtAuthenticationFilter` 는 무토큰 요청에서 예외를 던지지 않고 체인을 통과시킨다 (해당 파일 주석: "이 필터는 요청을 직접 거부하지 않는다"). 따라서 필요한 변경은 둘뿐이다.

```java
// ① SecurityConfig — 구체적인 규칙이 먼저 와야 한다 (아래 ⚠️)
.authorizeHttpRequests(auth -> auth
        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
        // 명령 + 내 강의 목록: ROLE_CREATOR. permitAll 보다 먼저 선언한다
        .requestMatchers("/v1/klasses/me").hasRole("CREATOR")
        .requestMatchers(HttpMethod.POST,  "/v1/klasses").hasRole("CREATOR")
        .requestMatchers(HttpMethod.PATCH, "/v1/klasses/**").hasRole("CREATOR")
        // 선택적 인증: 토큰이 없어도 통과하되, 있으면 서비스가 그 사실을 쓴다.
        // 매처를 숫자로 좁혀 /me 가 여기로 흘러들 여지 자체를 없앤다
        .requestMatchers(HttpMethod.GET, "/v1/klasses").permitAll()
        .requestMatchers(HttpMethod.GET, "/v1/klasses/{id:[0-9]+}").permitAll()
        .anyRequest().authenticated())
```

> ⚠️ **규칙 순서가 결과를 바꾼다.** Security 는 **먼저 매칭되는 규칙**을 쓴다. `/v1/klasses/*` 의 `permitAll` 이 `/v1/klasses/me` 의 `hasRole` 보다 앞에 오면 — `*` 는 단일 세그먼트를 매칭하므로 `GET /v1/klasses/me` 가 permitAll 로 확정되고 **내 강의 목록이 무인증으로 열린다.** 아래 `hasRole` 줄은 도달조차 하지 않는다.
>
> 방어선을 둘 둔다 — ① `hasRole` 을 먼저 선언하고, ② permitAll 매처를 `{id:[0-9]+}` 로 좁힌다. 순서 하나에 기대지 않는 편이 낫고, 후자는 `{id}` 를 `Long` 으로 받기로 한 위 결정과 방어선이 같아진다.
>
> ⚠️ **`hasRole("CREATOR")` 는 `ROLE_` 접두어를 자동으로 붙인다.** 이 저장소의 권한 문자열은 `ROLE_CREATOR` 이므로 `hasRole("CREATOR")` 가 맞다. `hasRole("ROLE_CREATOR")` 로 쓰면 `ROLE_ROLE_CREATOR` 를 찾아 **항상 403** 이 된다. 컴파일도 테스트도(권한 케이스를 안 쓰면) 통과하는 종류의 실수다.

```java
// ② 컨트롤러 — principal 이 null 일 수 있다
@GetMapping("/{id}")
public ApiResponse<KlassResponse> detail(
        @PathVariable Long id,
        @AuthenticationPrincipal AuthenticatedUser principal) {
    Long viewerId = (principal == null) ? null : principal.id();
    return ApiResponse.ok(KlassResponse.from(findKlassUseCase.findById(id, viewerId)));
}
```

기존 `UserController.me()` 는 보호 경로라 `principal.id()` 를 바로 역참조한다. 조회 경로에서 같은 코드를 쓰면 **비로그인 요청에 NPE** 가 난다.

### 4.3 상세 명세

#### `POST /v1/klasses` — 강의 등록

```json
// Request
{
  "title": "스프링 부트 입문",
  "description": "처음 시작하는 스프링 부트",
  "price": 50000,
  "capacity": 30,
  "startsOn": "2026-10-01",
  "endsOn": "2026-12-31",
  "cancellationPeriodDays": 7
}
```

| 필드 | 필수 | 검증 |
|------|:----:|------|
| `title` | Y | `@NotBlank`, `@Size(max = 200)` |
| `description` | **Y** | `@NotBlank` (D-18 — 필수값) |
| `price` | Y | `@NotNull`, `@DecimalMin("0")`, `@Digits(integer = 10, fraction = 2)` |
| `capacity` | Y | `@Min(1)` |
| `startsOn` | Y | `@NotNull` |
| `endsOn` | Y | `@NotNull`, **`endsOn >= startsOn` 은 도메인이 검사** |
| `cancellationPeriodDays` | N | `@Min(0)` |

> **필드 간 검증(`endsOn >= startsOn`)을 `@Valid` 로 하지 않는 이유**: 클래스 레벨 커스텀 검증기를 만들면 규칙이 `adapter.in` 에 놓인다. 같은 규칙을 수정 API 도 지켜야 하므로 두 요청 DTO 에 같은 검증기가 흩어진다. **규칙은 도메인에 하나만 둔다** (`changePeriod`).

```json
// Response 201
{ "success": true, "data": {
    "id": 1, "title": "스프링 부트 입문", "description": "...",
    "price": 50000, "capacity": 30, "enrollmentCount": 0,
    "status": "DRAFT",
    "startsOn": "2026-10-01", "endsOn": "2026-12-31",
    "cancellationPeriodDays": 7,
    "creator": { "id": 2, "username": "creator" },
    "createdAt": "2026-09-02T10:00:00", "updatedAt": "2026-09-02T10:00:00"
  }, "error": null }
```

#### `PUT /v1/klasses/{id}` — 강의 전체 교체

**전체 필수 수신 — 부분 수정이 아니다** (D-25).

```json
// Request — 변경하지 않은 필드도 현재 값을 그대로 싣는다
{
  "title": "스프링 부트 입문",
  "description": "처음 시작하는 스프링 부트",
  "price": 50000,
  "capacity": 30,
  "startsOn": "2026-10-01",
  "endsOn": "2026-12-31",
  "cancellationPeriodDays": 7
}
```

| 필드 | 필수 | 검증 |
|------|:----:|------|
| `title` | Y | `@NotBlank`, `@Size(max = 200)` |
| `description` | **Y** | `@NotBlank` (D-18 — 필수값) |
| `price` | Y | `@NotNull`, `@DecimalMin("0")`, `@Digits(integer = 10, fraction = 2)` |
| `capacity` | Y | `@NotNull`, `@Min(1)`, **현재 `enrollment_count` 미만으로 줄일 수 없다**(도메인) |
| `startsOn` | Y | `@NotNull` |
| `endsOn` | Y | `@NotNull`, **`endsOn >= startsOn` 은 도메인이 검사** |
| `cancellationPeriodDays` | N | `@Min(0)`. **`DRAFT` 에서만 변경 가능** — 다른 상태에서 값을 바꾸면 409 `CANCELLATION_PERIOD_NOT_EDITABLE`(도메인). 같은 값 재전송은 허용 (D-26) |

**등록 API 와 검증 기준이 같다.** 같은 값 집합을 같은 필수 조건으로 받기 때문이다. 두 요청 DTO 의 애노테이션이 어긋나면 같은 입력이 등록에서는 통과하고 수정에서는 거부되는(또는 그 반대) 자리가 생긴다. `status` 와 `enrollmentCount` 는 받지 않는다 — 전자는 상태 전이 API, 후자는 서버 소관이다.

```java
public record UpdateKlassCommand(
        Long klassId, Long requesterId,
        String title,
        String description,
        BigDecimal price,
        Integer capacity,
        LocalDate startsOn,      // 두 날짜를 따로 싣는다 (D-22) — 둘 다 필수로 오므로 조립은 없다
        LocalDate endsOn,
        Integer cancellationPeriodDays) { }
```

**전체 교체 규약과 근거.** 클라이언트의 수정 화면은 상세 조회로 **강의의 전체 값을 이미 들고 있다.** 저장할 때 변경되지 않은 필드도 현재 값을 그대로 실어 보내는 것이 정상 동작이다. 따라서 필드가 빠졌거나 `null`·공백으로 왔다는 것은 "안 바꿈"이 아니라 **클라이언트가 실제로 그렇게 입력했다는 뜻**이며, 400 `VALIDATION_ERROR` 로 거부해야 한다.

> **부분 수정 규격은 입력 오류를 조용히 무시한다.** `Optional.empty()` = "바꾸지 않는다" 체계에서는 클라이언트가 필드를 흘려도 200 이 나가고 아무 일도 일어나지 않는다. 사용자는 저장에 성공했다고 믿고, 그 필드만 옛 값으로 남는다. 그 무시를 없애는 것이 이 결정의 목적이다.

> **HTTP 메서드는 `PATCH` 를 유지한다.** 시맨틱상 `PUT` 이 맞지만, 바꾸면 `SecurityConfig` 매처 · openapi 오퍼레이션 키 · `DocumentationIntegrationTest` 의 `DOCUMENTED_OPERATIONS` · RestDocs 스니펫 이름까지 번져 **위험 대비 이득이 없다.** 메서드가 `PATCH` 라는 사실이 부분 수정을 뜻하지 않으며, 전체 교체임을 문서와 javadoc 에 명시한다.

**`cancellationPeriodDays` 를 `null` 로 보내면 전역 기본값으로 되돌아간다.** 등록과 같이 선택 필드이므로 `@NotNull` 을 붙이지 않는다. 전체 교체에서는 "이 필드를 비운 채 보냈다" = "이 강의는 전역 기본값을 따른다"는 의사 표시이고, 서비스가 `changeCancellationPeriodDays(null, now)` 를 무조건 호출하므로 그대로 반영된다 (§10). **단 되돌리기도 값 변경이므로 `DRAFT` 에서만 성립한다** (아래).

**`cancellationPeriodDays` 는 `DRAFT` 에서만 바꿀 수 있다. 다만 같은 값 재전송은 허용된다** (D-26).

취소 가능 기간은 **수강생과의 약속**이다. `OPEN` 이 되어 신청자가 생긴 뒤에 바꾸면 이미 신청한 사람의 취소 조건이 사후에, 그리고 불리하게 바뀔 수 있다. `DRAFT` 는 신청 자체가 불가능하므로(ERD 정본 §2.2 — `OPEN` 만 신청을 받는다) 그때까지만 열어 둔다. 다른 상태에서 값을 바꾸려 하면 409 `CANCELLATION_PERIOD_NOT_EDITABLE` 이며, 값이 실제로 달라질 때만 거부한다.

> **같은 값 재전송을 허용하지 않으면 `OPEN` 강의를 아예 수정할 수 없다.** 이 API 는 전체 필수 수신이므로(D-25) **모든 수정 요청이 `cancellationPeriodDays` 를 항상 싣고 오고**, 서비스는 `changeCancellationPeriodDays` 를 무조건 호출한다. 상태만 보고 무조건 거부하면 `OPEN` 강의의 **제목만** 바꾸려는 요청까지 409 가 되어 수정 경로가 통째로 막힌다. 전체 교체 규약에서 클라이언트가 바꾸지 않은 필드에 현재 값을 그대로 실어 보내는 것은 정상 동작이므로, **같은 값 재전송은 변경이 아니다** — 상태와 무관하게 통과한다 (`null → null` 도 마찬가지다). 그 no-op 경로는 `updatedAt` 도 남기지 않는다. "매 요청이 수정"이라는 규약은 함께 호출되는 다른 `change*` 메서드가 이미 지킨다 (§3.2).

> **`INVALID_KLASS_STATUS_TRANSITION` 을 재사용하지 않는다.** 그것은 **상태 전이**가 실패했다는 뜻이고 이것은 **필드 수정**이 실패했다는 뜻이다. 같은 코드를 주면 클라이언트가 상태 변경 API(`PATCH /v1/klasses/{id}/status`)를 다시 호출하려 든다 — 고쳐야 할 요청은 수정 API 쪽인데 엉뚱한 곳을 보게 된다 (§6.2).

**값이 바뀌지 않아도 `updatedAt` 이 갱신된다.** 전체 교체이므로 **매 요청이 수정**이다. 기존 값과 동일한 값을 보내도 "그 값으로 저장하라"는 지시이며, 서버가 값을 비교해 "실질적 변경이 없었다"고 판단해 시각을 남기지 않으면 **클라이언트가 저장했다고 믿는 시점과 이력이 어긋난다.** 부분 수정 시절에는 "바꿀 것이 없는 요청"이 성립해 그때만 시각을 보존했지만, 지금은 그런 요청 자체가 없다 (`UpdateKlassCommand.isEmpty()` 제거).

> **`endsOn >= startsOn` 을 `@Valid` 로 하지 않는다.** 등록 절과 같은 근거다 — 클래스 레벨 커스텀 검증기를 만들면 규칙이 `adapter.in` 에 놓이고, 같은 규칙을 등록 API 도 지켜야 하므로 두 DTO 에 흩어진다. **규칙은 도메인에 하나만 둔다** (`Klass.changePeriod`). 위반 시 400 `INVALID_KLASS_PERIOD`.

> **수강 기간을 두 필드로 받는다** (D-22 유지, 근거 갱신). 도메인의 `changePeriod` 는 두 날짜를 **함께** 받아야 `ends_on >= starts_on` 을 판정할 수 있다(§3.2). 설계 초안의 `KlassPeriod` 값 타입을 만들지 않는 것은 그것이 도메인 시그니처를 한 번 더 감싸기만 하고, 이 명령의 다른 필드들과 표현 방식이 어긋나기 때문이다. **전체 교체에서는 두 날짜가 항상 함께 오므로 서비스의 조립 단계(`applyPeriod`)도 사라졌다** — `changePeriod(command.startsOn(), command.endsOn(), now)` 를 직접 호출한다. "한쪽만 오면 나머지는 강의의 현재 값이고 서비스만 그것을 안다"는 초안의 근거는 부분 수정 전제였으며 더는 성립하지 않는다.

#### `PATCH /v1/klasses/{id}/status` — 상태 수정

```json
// Request
{ "status": "OPEN" }
```

`status` 는 `@NotNull` + `KlassStatus` enum. 파싱 불가한 값(`"OPENED"`)은 `HttpMessageNotReadableException` → 기존 핸들러가 400 `MALFORMED_REQUEST` 로 처리한다.

> ⚠️ **이것은 요청 *본문* 경로에서만 성립한다.** 쿼리 파라미터(`GET /v1/klasses?status=OPENED`)와 경로 변수(`GET /v1/klasses/abc`)는 다른 예외를 던지며, 그 둘은 현재 `GlobalExceptionControllerAdvice` 가 **다루지 않아 500 이 된다** (§6.5).

서비스는 **목표 상태로 분기해 도메인 메서드를 고른다** — 전이 판단 자체는 도메인이 한다.

```java
switch (command.status()) {
    case OPEN   -> klass.publish(now);   // 전제: DRAFT
    case CLOSED -> klass.close(now);     // 전제: DRAFT 또는 OPEN (§3.3)
    case DRAFT  -> throw KlassError.INVALID_KLASS_STATUS_TRANSITION.toException();
}
```

`DRAFT` 를 목표로 하는 요청은 도메인까지 가지 않고 여기서 끝난다 — 되돌아갈 메서드가 존재하지 않기 때문이다 (`CLOSED → DRAFT` 는 ERD 도 금지, `OPEN → DRAFT` 는 D-18).

#### `GET /v1/klasses` — 공개 목록

| 파라미터 | 필수 | 기본값 | 설명 |
|----------|:----:|--------|------|
| `cursor` | N | 없음 | 직전 페이지 마지막 항목의 `id`. 없으면 첫 페이지 |
| `size` | N | 20 | 1~100. 범위 밖이면 400 |
| `status` | N | 없음 | `OPEN` \| `CLOSED`. 미지정 시 둘 다 |

```json
// Response 200
{ "success": true, "data": {
    "items": [ { "id": 42, "title": "...", "price": 50000,
                 "capacity": 30, "enrollmentCount": 12, "status": "OPEN",
                 "startsOn": "2026-10-01", "endsOn": "2026-12-31",
                 "creator": { "id": 2, "username": "creator" } } ],
    "hasNext": true,
    "nextCursor": 42
  }, "error": null }
```

**커서 응답 규격 확정** (Plan §8.2):

| 필드 | 타입 | 의미 |
|------|------|------|
| `items` | 배열 | 항목. `size` 개 이하 |
| `hasNext` | boolean | 다음 페이지 존재 여부 |
| `nextCursor` | number \| null | 다음 요청에 넣을 커서. `hasNext=false` 면 null |

> **`hasNext` 가 `is` 접두어 규칙의 예외인 이유** (§9 D-15). CLAUDE.md 는 boolean 에 전 계층 `is` 접두어를 요구한다. 그 규칙의 **목적은 "이름만 보고 boolean 임을 알 수 있게" 하는 것**이고, `hasNext` 는 이미 그것을 충족한다. `isHasNext` 는 규칙의 문자는 지키지만 목적을 배반한다. 예외를 여기 명시하고, 이후 boolean 필드는 `is` 아니면 `has` 접두어를 쓴다.

**목록에 `description` 을 넣지 않는다.** TEXT 컬럼이라 20건이면 응답이 크게 부푼다. 상세에서만 준다 (`KlassSummaryResponse` vs `KlassResponse` 를 나누는 이유).

**커서 구현 — `size + 1` 조회**

```java
List<Klass> found = query.limit(size + 1).fetch();   // 하나 더 가져온다
boolean hasNext = found.size() > size;
List<Klass> items = hasNext ? found.subList(0, size) : found;
Long nextCursor = hasNext ? items.get(items.size() - 1).getId() : null;
```

`COUNT(*)` 를 돌리지 않고 다음 페이지 유무를 안다. 커서 방식이 총 개수를 제공하지 않는 대가로 얻는 것이다.

#### `GET /v1/klasses/me` — 내 강의 목록

파라미터는 공개 목록과 같되 `status` 에 `DRAFT` 도 허용된다. 응답 규격 동일.

---

## 5. UI/UX Design

해당 없음 — 백엔드 API 전용. 화면 계약은 §4 의 요청/응답 스펙이 대신하며, RestDocs 가 생성하는 `/docs/api-guide.html` 이 그 문서다.

---

## 6. Error Handling

### 6.1 `KlassError` 정의

```java
public enum KlassError implements ErrorCode {
    KLASS_NOT_FOUND(404, "강의를 찾을 수 없습니다"),
    NOT_KLASS_OWNER(403, "본인이 개설한 강의만 관리할 수 있습니다"),
    INVALID_KLASS_STATUS_TRANSITION(409, "허용되지 않는 상태 변경입니다"),
    CAPACITY_BELOW_ENROLLMENT(409, "현재 수강 인원보다 적은 정원으로 변경할 수 없습니다"),
    CANCELLATION_PERIOD_NOT_EDITABLE(409, "취소 가능 기간은 초안 상태에서만 변경할 수 있습니다"),
    INVALID_KLASS_PERIOD(400, "수강 종료일은 시작일보다 빠를 수 없습니다"),
    INVALID_KLASS_CAPACITY(400, "정원은 1명 이상이어야 합니다"),
    INVALID_KLASS_PAGE_SIZE(400, "조회 개수는 1 이상 100 이하여야 합니다");
}
```

**상수명이 곧 `error.code` 다** (`ErrorCode` 인터페이스). 다른 `*Error` enum 과 이름이 겹치면 클라이언트가 구분할 수 없으므로 — `CommonError.NOT_FOUND` / `ACCESS_DENIED` 가 이미 있어 `KLASS_` 접두어로 구분한다.

### 6.2 상태 코드 선택 근거

| 상황 | 코드 | 근거 |
|------|:----:|------|
| 타인 DRAFT 상세 조회 | **404** | 403 은 "그 강의는 존재한다"를 알려준다. 목록에서 안 보이는 것과 같은 이야기를 해야 한다 (Plan §3.3) |
| 타인 강의 수정 | **403** | 여기서는 강의의 존재가 이미 공개돼 있다(`OPEN`/`CLOSED` 라면 상세 조회로 볼 수 있다). 404 로 감추면 개설자 본인도 자기 강의를 못 찾는 것처럼 보인다 |
| 금지된 상태 전이 | **409** | 입력 형식은 옳고 현재 리소스 상태와 충돌한다. 400 은 "요청이 잘못됐다"로 읽혀 클라이언트가 입력을 고치려 든다 |
| 정원 축소 실패 | **409** | 위와 같음. 요청 값 자체는 유효하고 현재 점유 인원과 충돌한다 |
| 취소 기간을 `DRAFT` 아닌 상태에서 변경 | **409** | 요청 값 자체는 유효하고 **현재 리소스 상태와 충돌**한다. 400 은 "입력을 고쳐 다시 보내라"로 읽혀 클라이언트가 값을 바꿔 재시도하게 만드는데, 어떤 값을 보내도 강의가 `DRAFT` 로 돌아가지 않는 한 성공하지 않는다. `INVALID_KLASS_STATUS_TRANSITION` 을 재사용하지 않는 것은 그것이 **상태 전이** 실패이고 이것은 **필드 수정** 실패이기 때문이다 — 같은 코드를 주면 클라이언트가 상태 변경 API 를 다시 호출하려 든다 (D-26) |
| `endsOn < startsOn` | **400** | 다른 상태를 참조하지 않는 요청 자체의 오류 |

> **타인 DRAFT 를 404 로 하면서 타인 강의 수정을 403 으로 하는 것이 모순 아닌가?** 아니다. 판단 기준은 **"이 응답이 감춰야 할 것을 드러내는가"** 하나다. DRAFT 는 존재 자체가 비밀이고, 공개된 강의는 존재가 비밀이 아니다. 다만 **타인의 DRAFT 를 수정 시도하면 404 여야 한다** — 존재를 드러내면 안 되므로. 서비스는 소유권 검사보다 **가시성 검사를 먼저** 한다.

### 6.3 검사 순서 (수정·전이 공통)

```
1. 락과 함께 조회 → 없으면            KLASS_NOT_FOUND (404)
2. isVisibleTo(requesterId) 아니면    KLASS_NOT_FOUND (404)   ← 존재를 감춘다
3. isOwnedBy(requesterId) 아니면      NOT_KLASS_OWNER (403)
4. 도메인 메서드 호출 → 규칙 위반 시   409 / 400
```

**2번과 3번의 순서가 뒤집히면 타인 DRAFT 의 존재가 403 으로 새어나간다.** `DomainAuthenticationProvider` 가 비밀번호 검증을 계정 상태 검사보다 먼저 하도록 순서를 보장하는 것과 같은 종류의 결합이다 — 컴파일도 테스트도(순서 케이스를 안 쓰면) 통과한다.

### 6.4 응답 형식

기존 `ApiResponse` + `ErrorResponse` 를 그대로 쓴다. 새 형식이 없다.

```json
{ "success": false, "data": null,
  "error": { "code": "NOT_KLASS_OWNER", "message": "본인이 개설한 강의만 관리할 수 있습니다", "details": {} } }
```

### 6.5 `GlobalExceptionControllerAdvice` 확장 — 400 이 500 으로 새는 구멍

**이 기능이 이 저장소에서 처음으로 쿼리 파라미터와 경로 변수를 쓴다.** 기존 4개 엔드포인트는 둘 다 없었고, 그래서 이 구멍이 드러난 적이 없다.

현재 Advice 가 다루는 예외는 다섯이다 — `BusinessException` / `MethodArgumentNotValidException` / `NoResourceFoundException` / `HttpRequestMethodNotSupportedException` / `HttpMessageNotReadableException`. 나머지는 `handleUnexpected` 가 잡아 **500 + `INTERNAL_ERROR`** 로 만든다. Advice 자신의 주석이 경고하는 그대로다 — "고유한 상태코드를 가져야 하는 예외는 위쪽에 명시적 핸들러를 먼저 두어야 한다."

| 요청 | 던져지는 예외 | 현재 | 기대 |
|------|--------------|:----:|:----:|
| `GET /v1/klasses?size=101` | `HandlerMethodValidationException` | **500** | 400 |
| `GET /v1/klasses?status=OPENED` | `MethodArgumentTypeMismatchException` | **500** | 400 |
| `GET /v1/klasses/abc` (인증됨) | `MethodArgumentTypeMismatchException` | **500** | 400 |
| `GET /v1/klasses/abc` (무토큰) | — | **401** | 401 ✅ |

> **마지막 줄은 코드가 옳고 이 표의 초안이 틀렸다.** §4.2 가 permitAll 매처를 `{id:[0-9]+}` 로 좁혔으므로 숫자가 아닌 경로는 `anyRequest().authenticated()` 로 떨어져 **컨트롤러에 닿지 않는다.** 무인증 요청자에게 타입 오류를 알려줄 이유가 없으니 401 이 맞다 — `KlassFlowIntegrationTest#14` 가 이것을 단언한다.

핸들러 2종을 추가한다. 둘 다 `CommonError.VALIDATION_ERROR` 로 매핑하고, 가능하면 `details` 에 파라미터명을 담는다.

```java
@ExceptionHandler(HandlerMethodValidationException.class)      // @RequestParam 검증 실패
@ExceptionHandler(MethodArgumentTypeMismatchException.class)   // 타입 변환 실패
```

> ⚠️ **컨트롤러에 `@Validated` 를 붙이면 안 된다 — 붙이면 500 이 된다.** Spring 6.1 부터 `@RequestParam` 의 제약 애노테이션은 **내장 메서드 검증**이 처리하고 `HandlerMethodValidationException` 을 던진다(위 핸들러가 잡는 그 예외다). 그런데 클래스에 `@Validated` 가 있으면 **AOP 기반 검증이 대신 동작**해 `ConstraintViolationException` 을 던지고(이중 검증을 피하려 내장 쪽이 물러난다), 그 예외는 핸들러가 없어 `handleUnexpected` 로 떨어진다.
>
> Check 단계에서 실제로 붙여 보고 500 을 확인한 뒤 걷어냈다. **애노테이션을 더 붙였는데 응답이 나빠지는** 종류의 함정이다.

**두 방어선을 실제로 세웠다** — 어느 쪽이 잡는지가 경로마다 다르다.

| 경로 | 잡는 곳 | 응답 코드 |
|------|---------|-----------|
| HTTP `?size=101` | 컨트롤러의 `@Min`/`@Max` → Advice | `VALIDATION_ERROR` |
| 포트 직접 호출 (배치·내부 서비스) | `KlassQuery` 생성자 | `INVALID_KLASS_PAGE_SIZE` |

즉 HTTP 요청은 **둘째 방어선에 도달하지 않는다.** 그것이 무용하다는 뜻이 아니다 — `KlassQuery` 를 만드는 다른 호출자가 생기면 그때 유일한 방어선이 된다. 각각을 검증하는 테스트도 다르다: `KlassFlowIntegrationTest#12`(첫째) / `KlassQueryTest`(둘째).
>
> ⚠️ **이 변경은 `klass` 도메인 밖으로 나간다.** `common` 의 Advice 를 고치므로 기존 4개 엔드포인트의 동작에도 영향이 있다 — 다만 추가만 하고 기존 핸들러를 건드리지 않으므로 회귀 위험은 낮다. `AuthControllerTest`·`UserControllerTest` 가 여전히 통과하는지 확인한다.

---

## 7. Security Considerations

- [x] **수평 권한 상승 차단** — `ROLE_CREATOR` (설정) + `creator_id == sub` (서비스) 이중 검사. §6.3 순서 준수
- [x] **정보 노출 차단** — 타인 DRAFT 는 존재가 드러나지 않는다 (404, 목록 제외)
- [x] **입력 검증** — `@Valid` + 도메인 규칙. `size` 상한 100 으로 대량 조회 방지
- [x] **SQL Injection** — QueryDSL·JPA 파라미터 바인딩. 문자열 연결 없음
- [x] **선택적 인증 경로가 의도한 것만 열려 있는지** — `permitAll` 은 GET 두 경로에만. `/me` 는 `hasRole` 이 먼저 매칭돼야 한다 (§4.2 ⚠️)
- [ ] Rate Limiting — 범위 밖 (프로젝트 전체 미도입)

**`@PreAuthorize` 를 쓰지 않는 이유**: `CommonError.ACCESS_DENIED` 주석이 "메서드 보안은 이 예제의 범위 밖"이라고 적어 뒀다. 경로 단위 설정으로 충분하고, 소유권은 어차피 메서드 보안으로 표현할 수 없다(엔티티를 읽어야 알 수 있다).

**권한 회수가 즉시 반영되지 않는다 — 의도된 완화다** (D-19).

`JwtAuthenticationFilter` 는 권한을 **Access 토큰의 `roles` 클레임**에서 만든다. 따라서 관리자가 어떤 사용자의 `ROLE_CREATOR` 를 회수해도, 그 사용자가 이미 들고 있는 Access 토큰이 만료될 때까지는 `hasRole("CREATOR")` 가 통과한다. ERD §8 시나리오 #30("`ROLE_CREATOR` 가 회수된 사용자의 상태 변경 → 거부")과 어긋나는 지점이다.

막으려면 명령 3종마다 `UserQueryPort` 로 권한을 다시 읽어야 하는데(`KlassService` 가 이미 그 포트를 주입받으므로 배선 비용은 없다), **매 명령에 사용자 조회가 1회 추가된다.** Access 토큰 수명이 짧고 크리에이터 권한 회수가 드문 운영 사건이므로 그 비용을 치르지 않는다.

> `/v1/users/me` 는 같은 상황에서 DB 를 다시 읽는다("권한 변경도 즉시 반영돼야 하므로"). 온도차가 있는 것은 맞고, 근거는 **읽기와 쓰기의 대가가 다르다**는 것이다 — 조회는 낡은 권한을 보여줄 뿐이지만, 명령은 낡은 권한으로 데이터를 바꾼다. 그럼에도 완화를 택한 것은 토큰 수명 안에서만 발생하는 창이기 때문이며, 실서비스라면 재검토 대상이다.

---

## 8. Test Plan

> **코드와 테스트는 한 세트다.** 이 저장소에서 테스트는 검증 수단이자 문서 생성원이다.

### 8.1 테스트 범위

| 레벨 | 대상 | 위치 | 시점 |
|------|------|------|:----:|
| L1 | 도메인 규칙 | `klass/domain/KlassTest` | Do |
| L2 | 어댑터 (커서·fetch join·프록시) | `klass/adapter/out/persistence/KlassRepositoryAdapterTest` | Do |
| L1 | 조회 조건 (`KlassQuery`) | `klass/application/dto/KlassQueryTest` | Do |
| L2 | 서비스 (소유권·전체 교체 수정) | `klass/application/service/KlassServiceTest` | Do |
| L3 | 컨트롤러 + **RestDocs** | `controller/KlassControllerTest` (기존 `AuthControllerTest`·`UserControllerTest` 와 같은 위치 — `BaseControllerTest` 를 공유한다) | Do |
| L4 | 통합 흐름 | `integration/KlassFlowIntegrationTest` | Do |
| L5 | 문서 산출물 | `integration/DocumentationIntegrationTest` (갱신) | Do |
| — | ↳ **path 4 → 8** + 오퍼레이션 단언 추가 (§8.8) | | |
| 스키마 | `updated_at` 존재 | `EnrollmentSchemaTest` (갱신) | Do |

### 8.2 L1 — 도메인 단위

| # | 대상 | 기대 |
|---|------|------|
| 1 | `publish()` on DRAFT | OPEN, `updatedAt` 갱신 |
| 2 | `publish()` on OPEN / CLOSED | `INVALID_KLASS_STATUS_TRANSITION` |
| 3 | `close()` on OPEN | CLOSED, `updatedAt` 갱신 |
| 3-b | **`close()` on DRAFT** | **CLOSED** (개설 철회 — ERD §3.4 허용, §3.3) |
| 4 | `close()` on CLOSED | `INVALID_KLASS_STATUS_TRANSITION` |
| 5 | `changeCapacity(n)` where n < `enrollmentCount` | `CAPACITY_BELOW_ENROLLMENT` |
| 6 | `changeCapacity(0)` | `INVALID_KLASS_CAPACITY` |
| 7 | `changeCapacity(n)` where n >= count | 반영, `updatedAt` 갱신 |
| 8 | `changePeriod(s, e)` where e < s | `INVALID_KLASS_PERIOD` |
| 9 | `isOwnedBy` — 본인 / 타인 / null | true / false / false |
| 10 | `isVisibleTo` — DRAFT×본인 / DRAFT×타인 / DRAFT×null / OPEN×null | true / false / false / true |
| 11 | `open()` 직후 | `status=DRAFT`, `enrollmentCount=0`, `createdAt == updatedAt` |
| 12 | **`changeCancellationPeriodDays(14)` on DRAFT** | 14 로 반영, `updatedAt` 갱신 |
| 13 | `changeCancellationPeriodDays(14)` on OPEN | `CANCELLATION_PERIOD_NOT_EDITABLE`. **원값(7)·`updatedAt` 이 보존된다** |
| 14 | `changeCancellationPeriodDays(14)` on CLOSED | `CANCELLATION_PERIOD_NOT_EDITABLE`, 원값 보존 |
| 15 | **`changeCancellationPeriodDays(7)` on OPEN** (현재 값과 같음) | **예외 없음**, 값 유지, **`updatedAt` 도 그대로**(no-op) |
| 16 | `null ↔ 값` 전환 | DRAFT: `값→null`·`null→값` 모두 반영. OPEN: `값→null` 은 `CANCELLATION_PERIOD_NOT_EDITABLE` |
| 17 | `null → null` on OPEN | **예외 없음**(no-op), `updatedAt` 그대로 |

> **15번이 함정 방어의 핵심이다.** 여기가 깨지면 `OPEN` 강의의 제목만 바꾸려는 요청까지 409 가 된다 — 전체 교체(D-25)에서 클라이언트는 바꾸지 않은 필드에 현재 값을 그대로 실어 보내고, `KlassService.update` 는 이 메서드를 무조건 호출하기 때문이다 (§3.2, D-26). 13·15 가 **함께** 있어야 규칙이 값을 실제로 보는지, 아니면 상태만 보고 무조건 통과·거부하는지 갈린다.
>
> **16·17번이 `Objects.equals` 를 고정한다.** `equals` 를 직접 호출하면 현재 값이 `null` 인 경로에서 NPE 이고, `==` 는 `Integer` 박싱 캐시 범위(-128~127) 밖에서 조용히 틀린다. 17번은 현재 값을 `null` 로 만들어야 하므로 `ReflectionTestUtils.setField` 를 쓴다 — 팩토리로는 `null` 상태의 `OPEN` 강의를 만들 수 없다.

> **9·10번에서 `null` 케이스를 반드시 쓴다.** 비로그인 조회가 이 경로로 들어온다. `isOwnedBy` 를 `creator.getId().equals(userId)` 로 쓰면 `userId=null` 에서 false 가 나와 우연히 맞지만, `userId.equals(...)` 로 쓰면 NPE 다.

### 8.3 L2 — 어댑터

| # | 대상 | 기대 |
|---|------|------|
| 1 | 공개 목록 첫 페이지 (cursor 없음) | `id DESC` 상위 N건, DRAFT 0건 |
| 2 | 공개 목록 커서 이어받기 | 직전 마지막 id 미만, 중복 0 |
| 3 | 마지막 페이지 | `hasNext=false`, `nextCursor=null` |
| 4 | `status=OPEN` 필터 | CLOSED 0건 |
| 5 | 내 강의 목록 | 본인 것만, DRAFT 포함, 타인 것 0건 |
| 6 | **소유권 검사 시 프록시 초기화 여부** | 조인 없는 경로에서 읽어 `isOwnedBy` 후 추가 쿼리 0 (D-21 대비) |
| 7 | **목록 조회 후 전 항목의 `creator.getUsername()` 을 읽음** | **증가분 0** (fetch join 검증) |

> **7번이 핵심이고, 서술이 정확해야 의미가 있다.** `@ManyToOne(LAZY)` 라 `creator` 는 프록시로 오고, 실제 쿼리는 **`getUsername()` 을 호출하는 순간** 나간다. 따라서 조회만 하고 끝내는 테스트는 fetch join 이 있든 없든 **쿼리 1회로 통과한다** — 검증하는 척만 하는 테스트가 된다.
>
> Option C 에서 포트가 `List<Klass>` 엔티티를 돌려주므로 초기화 시점은 어댑터 밖(서비스의 `KlassResult` 변환)이다. 어댑터 테스트가 그 지점을 흉내 내려면 **조회 후 전 항목의 `getUsername()` 을 명시적으로 읽어야 한다.** 대안으로 `Hibernate.isInitialized(k.getCreator())` 를 전 항목에 단언해도 된다.
>
> **계측 수단이 이 저장소에 없다.** `spring.jpa.properties.hibernate.generate_statistics=true` 를 테스트 프로파일에 켜고 `SessionFactory.getStatistics().getPrepareStatementCount()` 로 센다. 설계 초안이 §11.1 에 `application-test.yml ✎` 을 넣은 이유가 이것이었지만, 계측이 필요한 곳이 이 테스트 파일 하나뿐이라 `@DataJpaTest(properties = ...)` 로 국소화했다 (D-23).

### 8.4 L2 — 서비스

| # | 대상 | 기대 |
|---|------|------|
| 1 | 타인 강의 수정 (공개 강의) | `NOT_KLASS_OWNER` |
| 2 | 타인 DRAFT 수정 | **`KLASS_NOT_FOUND`** (403 아님 — §6.3 순서) |
| 3 | 수정: 전 필드를 실어 보냄 | 전 필드가 그 값으로 교체, `updatedAt` = 주입 시각 |
| 4 | 수정: 기존 값과 동일한 값을 보냄 | 그대로 반영되고 **`updatedAt` 도 갱신** (D-25) |
| 5 | 수정: `cancellationPeriodDays` 를 `null` 로 | `null` 로 되돌아감 = 전역 기본값 |
| 6 | 수정: `endsOn < startsOn` | `INVALID_KLASS_PERIOD` (조립 없이 직접 검사) |
| 7 | 존재하지 않는 id | `KLASS_NOT_FOUND` |
| 8 | 고정 `Clock` 주입 | `updatedAt` 이 그 시각과 일치 |
| 9 | **`OPEN` 강의 수정: 취소 기간에 현재 값(7)을 싣고 다른 필드를 바꿈** | **200 — 전 필드 반영**, `cancellationPeriodDays=7` 유지, `updatedAt` = 주입 시각 |
| 10 | `OPEN` 강의 수정: 취소 기간을 다른 값(14)으로 | `CANCELLATION_PERIOD_NOT_EDITABLE` |

> **9번이 없으면 "`OPEN` 강의 수정 불가" 회귀를 아무도 잡지 못한다.** 서비스는 `changeCancellationPeriodDays` 를 무조건 호출하므로, 도메인이 no-op 을 잃는 순간 이 경로가 통째로 409 가 된다 (D-26).
>
> ⚠️ **값이 중요하지 않은 테스트도 이 규칙에 걸린다.** 소유권 검사(#1·#2)처럼 "유효한 수정 명령"만 필요한 케이스가 취소 기간에 강의의 현재 값과 다른 값을 넣으면, 검증하려던 것과 무관하게 409 로 실패한다. 그래서 현재 값 그대로인 명령을 만드는 **픽스처 하나(`sameValueUpdate`)로 모았다.**

### 8.5 L3 — 컨트롤러 + RestDocs

**엔드포인트 6개 전부 스니펫을 남긴다.** 하나라도 빠지면 문서에서 누락되고 §8.8 의 오퍼레이션 단언이 깨진다.

**⚠️ 이 레벨에서 검증할 수 없는 것이 있다.** 하위 클래스는 `@WebMvcTest(excludeFilters = {SecurityConfig, JwtAuthenticationFilter})` + `@AutoConfigureMockMvc(addFilters = false)` 로 돈다. `BaseControllerTest` javadoc 이 직접 적어 뒀다 — **"보안 필터가 꺼져 있어 JWT 인증이 실제로 동작하는지는 검증하지 못한다."**

따라서 **권한(403)·인증(401)은 L3 에서 검증 불가**다. `SecurityConfig` 가 컨텍스트에 없으므로 `hasRole` 이 적용되지 않고, EntryPoint 도 개입하지 않는다. 그 케이스들은 §8.6 L4 로 간다.

| # | 요청 | 기대 |
|---|------|------|
| 1 | POST 등록 (인증됨) | 201 + **스니펫** |
| 2 | PATCH 수정 (본인) | 200 + **스니펫** |
| 3 | PATCH 상태 (본인, DRAFT→OPEN) | 200 + **스니펫** |
| 4 | PATCH 상태 (CLOSED→OPEN) | 409 |
| 5 | PATCH 상태 (DRAFT→CLOSED) | 200 (개설 철회, §3.3) |
| 6 | GET 상세 (**미인증**, OPEN) | 200 + **스니펫** |
| 7 | GET 상세 (미인증, 타인 DRAFT) | 404 |
| 8 | GET 상세 (개설자, 본인 DRAFT) | 200 |
| 9 | GET 공개 목록 (**미인증**) | 200 + **스니펫**, DRAFT 0건 |
| 10 | GET `/me` (ROLE_CREATOR) | 200 + **스니펫**, DRAFT 포함 |
| 11 | POST 등록 (title 누락) | 400, `details.title` 존재 |
| 12 | POST 등록 (**description 누락**) | 400, `details.description` 존재 (D-18) |
| 13 | GET 목록 (`size=101`) | 400 (§6.5 핸들러 필요) |
| 14 | PATCH 수정 (**description 누락**) | 400, `details.description` 존재 (D-25) |
| 15 | PATCH 수정 (**capacity 누락**) | 400, `details.capacity` 존재 (D-25) |
| 16 | PATCH 수정 (title·description 이 공백) | 400, `details` 에 해당 필드 |
| 17 | PATCH 수정, 유즈케이스가 `CANCELLATION_PERIOD_NOT_EDITABLE` 을 던짐 | **409** + `error.code` 그대로. **새 스니펫 없음** |

> **17 은 Advice 매핑만 검증한다.** 유즈케이스가 `@MockitoBean` 이라 규칙 자체는 실행되지 않는다 — "`DRAFT` 에서만"과 "같은 값은 no-op"을 고정하는 것은 §8.2 L1 과 §8.4 L2 다. 여기서 확인하는 것은 새 에러 코드가 **400 이나 500 이 아니라 409** 로 나간다는 것뿐이다.
>
> ⚠️ **새 스니펫을 만들면 안 된다.** 같은 엔드포인트이므로 오퍼레이션 수가 늘어 §8.8 의 단언이 깨진다. 이번 규칙은 **필드 규칙**이라 path 8 / 오퍼레이션 10 이 불변이어야 한다. 규칙 자체는 기존 PATCH 스니펫의 `cancellationPeriodDays` **필드 description 과 오퍼레이션 description** 에 실어 문서화한다 — 문서에 드러나면서 개수는 그대로다.

> **14·15 가 D-25 전환의 계약을 고정한다.** 부분 수정 규격에서는 같은 요청이 **200** 이었고 아무 일도 일어나지 않았다 — 클라이언트가 필드를 흘렸다는 사실이 묻히던 자리다. 이 두 건이 없으면 규격을 되돌려도 테스트가 통과한다.

> ⚠️ **`BaseControllerTest.authenticateAs()` 는 자동 주입이 아니라 하위 클래스가 명시적으로 호출하는 메서드다.** 따라서 "끄는" 조작이 없다 — **6·9번은 호출하지 않으면 된다.**
>
> ⚠️ **진짜 장애물은 권한이 하드코딩돼 있다는 것이다.** 현재 `authenticateAs(Long, String)` 는 `List.of("ROLE_USER")` 를 박아 넣는다. `ROLE_CREATOR` 로 인증된 요청을 만들 수 없으므로 **`authenticateAs(Long, String, List<String>)` 오버로드를 추가해야 한다** (§11.1 에 `BaseControllerTest ✎`).
>
> ⚠️ **경로 변수를 쓰는 첫 엔드포인트다.** `RestDocumentationRequestBuilders.get("/v1/klasses/{id}", 1L)` 를 써야 한다 — `MockMvcRequestBuilders` 를 쓰면 OpenAPI path 키가 `/v1/klasses/1` 로 굳어 **요청마다 path 가 늘고 문서가 오염된다.** `pathParameters(parameterWithName("id")...)` 도 누락하면 스니펫 생성이 실패한다. CLAUDE.md 가 "RestDocs 경로"를 컴파일러가 못 잡는 지점으로 꼽은 자리다.

### 8.6 L4 — 통합 흐름 + **인증·권한 검증**

L3 이 검증할 수 없는 것들이 여기로 온다. `AuthFlowIntegrationTest` 가 선례다 — `@SpringBootTest(RANDOM_PORT)` + `TestRestTemplate` 으로 **실제 필터 체인을 통과**시키며, 그 `AuthGate` 중첩 클래스가 정확히 이 패턴이다.

**① 정상 흐름**
```
로그인(creator) → 등록(DRAFT) → 공개 목록에 없음 확인
                → 수정(title) → 상태 변경(OPEN) → 공개 목록에 있음 확인
                → 비로그인으로 상세 조회 200 → 마감(CLOSED) → 상세 조회 200
```

**② 인증·권한 게이트** (L3 에서 이관)

| # | 요청 | 기대 | 검증하는 것 |
|---|------|:----:|-------------|
| 1 | POST 등록, `ROLE_USER` 토큰 | **403** | `hasRole("CREATOR")` 가 실제로 적용되는가 |
| 2 | PATCH 수정, **다른 크리에이터** 토큰 | **403** | ← **Context Anchor RISK 대응** |
| 3 | PATCH, 타인 **DRAFT** | **404** | §6.3 검사 순서 (403 으로 새면 실패) |
| 4 | GET `/me`, 토큰 없음 | **401** | `/me` 가 permitAll 로 새지 않았는가 (C-1) |
| 5 | GET 공개 목록, 토큰 없음 | **200** | 선택적 인증이 실제로 열려 있는가 |

**③ 취소 가능 기간의 `DRAFT` 제한** (D-26)

| # | 요청 | 기대 | 검증하는 것 |
|---|------|:----:|-------------|
| 15 | `OPEN` 전이 후 취소 기간을 **다른 값**으로 수정 | **409** `CANCELLATION_PERIOD_NOT_EDITABLE` | 신청자가 생긴 뒤 조건 변경이 실제 필터 체인 끝까지 막히는가 |
| 16 | `DRAFT` 강의의 취소 기간을 다른 값으로 수정 | **200** + `data.cancellationPeriodDays` 가 새 값 | 규칙이 상태를 보고 갈리는가 |

> ①의 정상 흐름이 **`OPEN` 전이 후에도 제목을 수정한다** — 그것이 "같은 값 재전송은 통과한다"를 종단에서 고정하는 자리다. 따라서 **수정 본문을 만드는 헬퍼는 등록 헬퍼와 같은 취소 기간 값을 실어야 한다.** 두 헬퍼가 어긋나면 제목만 바꾸는 단계에서 409 가 나 흐름이 깨진다 — 상수 하나를 공유해 구조적으로 막는다.

> **2번은 L3 에 두면 동어반복이 된다.** 유즈케이스가 `@MockitoBean` 이라 "내가 스텁한 예외를 내가 확인"하는 꼴이고, 실제 소유권 검사는 실행되지 않는다. 실제 검사는 §8.4 L2 #1·#2 가 덮고, **경로 전체가 이어지는지**는 여기서만 볼 수 있다.
>
> ⚠️ **여기서 상태코드만 보면 위양성이 난다.** `TestRestTemplate` 은 리다이렉트를 따라가므로, Security 가 로그인 페이지로 보내면 200 + `text/html` 이 돌아온다 (CLAUDE.md 에 기록된 실제 사고, `DocumentationIntegrationTest` 가 같은 이유로 본문을 확인한다). **응답 본문의 마커까지 단언한다.** — L3 의 `MockMvc` 는 리다이렉트를 따라가지 않으므로 이 함정이 없다.

### 8.7 시드 데이터

`DefaultUserInitializer` 가 이미 `chals`(ROLE_USER) / `creator`(ROLE_USER + ROLE_CREATOR) 를 만든다. **"다른 크리에이터" 계정이 없다** — L3 4번(타인 강의 수정 403)을 쓰려면 테스트 안에서 두 번째 크리에이터를 만들어야 한다. 시드를 늘리지 않고 테스트 픽스처로 해결한다 (기동 기본값은 문서화된 계약이다).

---

### 8.8 L5 — 문서 산출물 검증 갱신

현재 `DocumentationIntegrationTest` 는 이렇게 센다.

```java
private static final int DOCUMENTED_ENDPOINT_COUNT = 4;
assertThat(paths.size()).isEqualTo(DOCUMENTED_ENDPOINT_COUNT);
```

`paths` 는 **path 템플릿 맵**이지 오퍼레이션 목록이 아니다. 강의 엔드포인트 6개는 path 로는 4개다.

| path | 오퍼레이션 |
|------|-----------|
| `/v1/klasses` | `get`, `post` |
| `/v1/klasses/{id}` | `get`, `patch` |
| `/v1/klasses/{id}/status` | `patch` |
| `/v1/klasses/me` | `get` |

**따라서 상수는 4 → 8 이다** (기존 4 + 강의 4). 10 이 아니다.

> ⚠️ **path 수만 세면 오퍼레이션 누락이 잡히지 않는다.** `/v1/klasses` 의 GET·POST 중 하나만 문서화해도 path 수는 그대로라 **조용히 통과한다.** §8.5 에 "하나라도 빠지면 L5 가 깨진다"고 적었지만 지금 구조로는 참이 아니다. path 별로 기대 오퍼레이션이 존재하는지까지 단언하도록 검증을 확장한다.

## 9. Clean Architecture

### 9.1 계층 배치

| 컴포넌트 | 계층 | 위치 |
|----------|------|------|
| `Klass`, `KlassStatus`, `KlassError` | domain | `klass/domain/` |
| `*UseCase` 5종 | application.port.in | `klass/application/port/in/` |
| `KlassCommandPort`, `KlassQueryPort` | application.port.out | `klass/application/port/out/` |
| `KlassService` | application.service | `klass/application/service/` |
| `KlassRepositoryAdapter`, `KlassJpaRepository`, `KlassQueryDslRepository` | adapter.out | `klass/adapter/out/persistence/` |
| `KlassController`, Request/Response DTO | adapter.in | `klass/adapter/in/web/` |
| `CursorPageResponse` | adapter.in (공용) | `common/adapter/in/web/dto/` |
| `QueryDslConfig` | infrastructure | `infrastructure/config/` |

### 9.2 의존 규칙 준수 확인

| 위치 | 이 설계에서 참조하는 것 | 위반 여부 |
|------|------------------------|:---------:|
| `Klass` | JPA 어노테이션, `KlassError`, JDK | ✅ Spring 타입 없음 |
| `KlassService` | `domain`, `KlassCommandPort`/`KlassQueryPort`, `UserQueryPort`, `Clock`, `@Transactional` | ✅ `@Transactional` 은 Spring 이지만 `application.service` 는 허용 |
| `KlassRepositoryAdapter` | `domain`, `port.out`, JPA/QueryDSL | ✅ |
| `KlassController` | `port.in`, 자신의 DTO, `AuthenticatedUser` | ✅ 엔티티 직접 노출 없음 |

> **`KlassResponse.from()` 이 받는 것은 `KlassResult` 이지 `Klass` 가 아니다.** 컨트롤러가 엔티티를 만지면 `adapter.in → domain` 직접 노출이 된다. `UserController` 가 `UserResult` 를 받는 것과 같은 구조다.

### 9.3 QueryDSL 배선

이 저장소에서 **QueryDSL 의 첫 실사용**이다. `build.gradle` 설정과 `QKlass` 생성은 1차에서 검증됐다(Design §12 D-3).

```java
// infrastructure/config/QueryDslConfig.java — 신규
@Configuration
public class QueryDslConfig {
    @Bean
    public JPAQueryFactory jpaQueryFactory(EntityManager em) {
        return new JPAQueryFactory(em);
    }
}
```

`KlassQueryDslRepository` 는 Spring Data 커스텀 리포지토리 패턴(`KlassJpaRepository extends JpaRepository<...>, KlassQueryDslRepository`) 대신 **독립 빈**으로 둔다 — 커스텀 패턴은 `*Impl` 명명 규약이 또 하나의 "컴파일러가 못 잡는 문자열"이 되고, 어댑터가 두 빈을 주입받으면 되는 일에 마법을 들일 이유가 없다.

---

## 10. Coding Convention Reference

CLAUDE.md 와 ERD 정본이 정본. 이 기능에 적용되는 것:

| 항목 | 적용 |
|------|------|
| boolean | `is` 접두어. **예외: `hasNext`** (§4.3, §9 D-15) |
| 시각 | 주입된 `Clock` 만. 도메인 메서드는 `LocalDateTime` 을 **파라미터로** 받는다 |
| 컬럼 명명 | `updated_at` (시각이므로 `_at`) |
| ENUM | `@Enumerated(EnumType.STRING)` 유지 |
| 상태 변경 | public setter 없음. `publish()`·`close()`·`change*()` |
| 주석 | 한국어. `Design Ref: §n` 부착. **왜**를 적는다 |
| 에러 코드 | enum 상수명 = `error.code`. 타 `*Error` 와 이름 중복 금지 |
| nullable | `klass` 에 남는 nullable 은 `cancellation_period_days` 뿐이다. 수정 API 에서 이 필드를 생략하거나 `null` 로 보내면 **`null` 로 되돌아간다** — 즉 전역 기본값을 따른다. 수정이 전체 교체(D-25)이므로 "비운 채 보냈다"가 곧 의사 표시가 되며, 되돌리기가 성립한다. **다만 그 되돌리기도 `DRAFT` 에서만 가능하다** — `null` 은 "전역 기본값을 따른다"는 하나의 약속이므로 `값 → null` 도 조건 변경이고, 다른 상태에서는 409 `CANCELLATION_PERIOD_NOT_EDITABLE` 다. 상태와 무관하게 통과하는 것은 **같은 값 재전송**(`null → null` 포함)뿐이다 (§3.2, §4.3, D-26) |

---

## 11. Implementation Guide

### 11.1 파일 구조

```
klass/
├── adapter/
│   ├── in/web/
│   │   ├── controller/KlassController.java                    ✚
│   │   └── dto/
│   │       ├── RegisterKlassRequest.java                      ✚
│   │       ├── UpdateKlassRequest.java                        ✚
│   │       ├── ChangeKlassStatusRequest.java                  ✚
│   │       ├── KlassResponse.java                             ✚
│   │       ├── KlassSummaryResponse.java                      ✚
│   │       └── KlassCreatorResponse.java                      ✚
│   └── out/persistence/
│       ├── KlassJpaRepository.java                            ✎ (`@EntityGraph` 단건 조회 추가)
│       ├── KlassQueryDslRepository.java                       ✚
│       └── KlassRepositoryAdapter.java                        ✚
├── application/
│   ├── dto/
│   │   ├── RegisterKlassCommand.java                          ✚
│   │   ├── UpdateKlassCommand.java                            ✚
│   │   ├── ChangeKlassStatusCommand.java                      ✚
│   │   ├── KlassQuery.java             (cursor·size·status)   ✚
│   │   ├── KlassResult.java                                   ✚
│   │   ├── KlassSummaryResult.java                            ✚
│   │   └── KlassCreatorResult.java                            ✚
│   ├── port/in/
│   │   ├── RegisterKlassUseCase.java                          ✚
│   │   ├── UpdateKlassUseCase.java                            ✚
│   │   ├── ChangeKlassStatusUseCase.java                      ✚
│   │   ├── FindKlassUseCase.java                              ✚
│   │   └── ListKlassUseCase.java                              ✚
│   ├── port/out/
│   │   ├── KlassCommandPort.java                              ✚
│   │   └── KlassQueryPort.java                                ✚
│   └── service/KlassService.java                              ✚
└── domain/
    ├── Klass.java                                             ✎ (메서드 + updatedAt)
    ├── KlassStatus.java                                       —
    └── error/KlassError.java                                  ✚

common/application/dto/CursorPageResult.java                   ✚ (D-24 — klass 가 아니다)
common/adapter/in/web/dto/CursorPageResponse.java              ✚
common/adapter/in/web/advice/
    GlobalExceptionControllerAdvice.java                       ✎ (핸들러 2종, §6.5)
infrastructure/config/QueryDslConfig.java                      ✚
infrastructure/security/config/SecurityConfig.java             ✎ (경로 규칙)

src/test/.../controller/BaseControllerTest.java                ✎ (권한 오버로드, §8.5)
src/test/.../application/dto/KlassQueryTest.java               ✚ (조회 조건 단위 테스트)

docs/02-design/features/class-enrollment-erd.design.md         ✎ (§3.1·§3.2.5·§3.7 세 곳)
```

**신규 28 · 수정 7** (Check 단계 정정 반영)

| 구분 | 수 | 내역 |
|------|:--:|------|
| 신규 | 28 | 웹 DTO 6 · application DTO 7 · port 7 · 컨트롤러 1 · 서비스 1 · 어댑터 2 · `KlassError` 1 · `CursorPageResult` 1 · `CursorPageResponse` 1 · `QueryDslConfig` 1 |
| 수정 | 7 | `Klass` · `KlassJpaRepository` · `SecurityConfig` · Advice · `BaseControllerTest` · ERD 정본 · CLAUDE.md |

> **`application-test.yml` 은 만들지 않았다** (D-23). 쿼리 카운트 계측이 한 테스트 파일에서만 필요한데, 전역 설정은 다른 테스트의 로그·성능에까지 영향을 준다. `@DataJpaTest(properties = ...)` 로 국소화했다.
>
> **`KlassPeriod` 도 만들지 않았다** (D-22). 설계 초안은 수강 기간을 쌍으로 묶어 Command 에 실으려 했지만, **한쪽 날짜만 오는 요청에서 나머지 현재 값을 아는 것은 엔티티를 읽은 서비스뿐**이다 — 컨트롤러는 모른다. 두 날짜를 따로 싣고 `KlassService.applyPeriod` 가 조립한다.

### 11.2 구현 순서

1. [ ] `Klass` 확장 + `KlassError` + `updated_at` + `description` NOT NULL → **L1 테스트**
2. [ ] ERD 정본 개정 3곳 + `EnrollmentSchemaTest` 갱신
3. [ ] 포트 7종 + Command/Result DTO
4. [ ] `QueryDslConfig` + `KlassQueryDslRepository` + `KlassRepositoryAdapter` → **L2 어댑터 테스트**
   - **먼저 `./gradlew compileJava` 로 `QKlass` 가 실제로 생성되는지 확인한다.** 1차에서 스파이크로 판정만 했고 실사용처가 0건이라 배선이 검증된 적이 없다. `build.gradle` 의 애너테이션 프로세서 선언 순서(querydsl-apt → lombok)가 같은 파일 주석의 서술("Lombok 이 먼저")과 반대인 것도 확인 대상이다
   - 쿼리 카운트 계측은 `@DataJpaTest(properties = "...generate_statistics=true")` 로 국소화한다 (§8.3 #7, D-23)
5. [ ] `KlassService` → **L2 서비스 테스트**
6. [ ] `GlobalExceptionControllerAdvice` 핸들러 2종 (§6.5) → 기존 컨트롤러 테스트 회귀 확인
7. [ ] `SecurityConfig` 경로 규칙 (**구체적 규칙 먼저**, §4.2)
8. [ ] `BaseControllerTest` 권한 오버로드 + `KlassController` + DTO → **L3 RestDocs 6종**
9. [ ] **L4 `KlassFlowIntegrationTest`** — 정상 흐름 + 인증·권한 게이트 5종 (§8.6)
10. [ ] **L5 `DocumentationIntegrationTest`** — path 4 → 8 + 오퍼레이션 단언 (§8.8)
11. [ ] `./gradlew build` 전체 통과

> **순서가 뒤집히면 안 되는 지점**: **9번(L4)을 빠뜨리면 `SecurityConfig` 가 틀려도 아무 테스트도 실패하지 않는다.** L3 은 보안 필터가 꺼진 슬라이스라 경로 규칙을 아예 보지 못하고(§8.5), 그래서 C-1 같은 순서 오류가 조용히 통과한다. 8번과 9번은 한 세트다.

### 11.3 Session Guide

#### Module Map

| Module | Scope Key | 내용 | 예상 턴 |
|--------|-----------|------|:-------:|
| 도메인 + 스키마 | `module-1` | `Klass` 메서드, `KlassError`, `updated_at`, `description` NOT NULL, ERD 개정 3곳, L1 + 스키마 테스트 | 15-20 |
| 포트 + 영속 | `module-2` | 포트 7종, DTO, QueryDSL 배선 확인, 어댑터, L2 어댑터 테스트 | 20-25 |
| 서비스 | `module-3` | `KlassService`, 소유권 검사·수정 적용, Advice 핸들러 2종, L2 서비스 테스트 | 15-20 |
| 웹 + 문서 | `module-4` | `SecurityConfig`, `BaseControllerTest` 오버로드, 컨트롤러, DTO, **L3 RestDocs 6종**, **L4 권한 게이트**, L5 | 30-35 |

#### 권장 세션 계획

| 세션 | 단계 | 범위 | 턴 |
|------|------|------|:--:|
| 1 | Plan + Design | 전체 | 완료 |
| 2 | Do | `--scope module-1,module-2` | 40-45 |
| 3 | Do | `--scope module-3,module-4` | 45-50 |
| 4 | Check + Report | 전체 | 30-40 |

> `module-4` 를 쪼개지 않는다. **`SecurityConfig` 는 L4 에서만 검증되므로**(L3 은 필터가 꺼져 있다) 컨트롤러·통합 테스트와 한 덩어리여야 한다 (§11.2 주의).

---

## 12. Divergence

ERD 정본·CLAUDE.md 대비 이 문서가 좁히거나 예외를 두는 지점. 나중에 "왜 정본과 다르지?"를 추적하기 위한 기록이다.

| ID | 대상 | 내용 | 근거 |
|----|------|------|------|
| **D-14** | ERD 정본 §7 | "토큰이 있으면 본인 DRAFT 도 공개 목록에 포함" → **포함하지 않는다.** 초안은 `/v1/klasses/me` 가 담당 | §7 은 목록이 하나뿐이던 전제의 문장이다. 목록을 둘로 나눴으므로(Plan Checkpoint 1) 공개 목록은 "남에게 보이는 그대로"여야 크리에이터가 자기 강의의 노출 상태를 확인할 수 있다 (§3.5) |
| **D-15** | CLAUDE.md boolean 규약 | `hasNext` 에 `is` 접두어를 붙이지 않는다 | 규칙의 목적은 "이름만 보고 boolean 임을 알게" 하는 것이고 `hasNext` 는 충족한다. `isHasNext` 는 문자를 지키고 목적을 배반한다. 이후 boolean 은 `is` 또는 `has` (§4.3) |
| **D-16** | ERD 정본 §4.8 **capacity 5번 및 상태 전이 5번** | 대기열 관련 두 규칙을 **이행하지 않는다** — ① 정원 증가 시 승격 ② `CLOSED` 전이 시 잔여 `WAITING` 일괄 `CANCELLED` | 대기열이 2차 범위라 대상 행이 존재하지 않는다. 발현 불가한 규칙이며, 붙을 자리를 `changeCapacity`·`close` 주석에 남긴다 (§3.4) |
| **D-17** | Spring Data 커스텀 리포지토리 관례 | `*Impl` 명명 규약 대신 QueryDSL 리포지토리를 독립 빈으로 둔다 | `*Impl` 은 또 하나의 "컴파일러가 못 잡는 문자열"이다. 어댑터가 두 빈을 주입받으면 끝나는 일이다 (§9.3) |
| **D-18** | ERD 정본 §3.2.5 · §3.4 | ① `description` 을 **NOT NULL** 로 올린다 ② `OPEN → DRAFT`(조건부 허용)를 **금지**한다 | ①은 원 요구사항이 "내용"을 등록 항목으로 나열했고 선택이라는 단서가 없었다 — 근거는 이것 하나다. 초안이 함께 적었던 "부분 수정에서 `null` 의 중의성이 사라진다"는 D-25 로 **무의미해졌다**(전체 필수 수신에는 애초에 중의성이 없다). ②는 대기자가 `DRAFT` 강의에 유령으로 남는 구멍이며 ERD §4.8 주의 박스가 같은 결론을 적어 뒀다. `enrollment_count = 0` 조건으로 막을 수 있지만 대기열이 붙는 순간 조건이 늘어난다 — 대기열과 함께 다시 연다 |
| **D-19** | ERD 정본 §4.8 2번 · §8 시나리오 #30 | 명령 실행 시 `ROLE_CREATOR` 를 **DB 에서 재확인하지 않는다.** JWT 클레임을 신뢰한다 | 권한 회수가 Access 토큰 수명 동안 반영되지 않는 창이 생긴다. 막으려면 명령 3종마다 사용자 조회가 1회 추가되는데, 토큰 수명이 짧고 권한 회수가 드문 사건이라 그 비용을 치르지 않는다. 실서비스라면 재검토 대상 (§7) |
| **D-20** | Plan §6.1 | `PUBLIC_ENDPOINTS` 배열에 조회 경로를 넣는 대신 **별도 matcher** 로 선언한다 | 두 종류는 성격이 다르다 — `PUBLIC_ENDPOINTS`(로그인 등)는 토큰이 **의미 없는** 경로이고, 조회는 토큰이 **있으면 쓰이는** 경로다. 같은 배열에 섞으면 §4.2 가 구분한 세 종류가 코드에서 둘로 뭉개진다 |
| **D-21** | ERD 정본 §4.1 · §4.8, 본 문서 §2.4 초안 | 수정·상태 전이에 **비관적 락을 걸지 않는다.** 일반 조회로 읽는다 | 그 락이 직렬화하려던 상대는 수강신청 트랜잭션(§4.2)이고 **2차 범위라 존재하지 않는다.** 본인이 자기 강의를 고치는 것끼리는 경합하지 않으므로 지금은 막는 것이 없다. 게다가 `loadForCommand` 에 두면 제목만 바꾸는 요청도 배타 락을 잡아 **수강신청이 붙은 뒤에도 과하다.** 되돌아올 좌표를 코드 세 곳(서비스·포트·리포지토리 javadoc)에 근거와 함께 남겼다 |
| **D-22** | 본 문서 §4.3 초안 · §11.1 | `KlassPeriod` 를 만들지 않는다. `UpdateKlassCommand` 가 `startsOn`·`endsOn` 을 **따로** 싣는다 | 최초 근거는 "부분 수정에서 한쪽만 오면 나머지는 강의의 현재 값이고 그것을 아는 것은 서비스뿐" 이었고, 그래서 서비스가 쌍을 조립했다. **D-25 로 두 날짜가 항상 함께 오게 되어 조립 단계는 사라졌다**(`applyPeriod` 제거). 그래도 값 타입을 만들지 않는 것은 그것이 도메인 시그니처를 한 번 더 감싸기만 하고 이 명령의 다른 필드들과 표현이 어긋나기 때문이다 |
| **D-23** | 본 문서 §11.1 초안 | `src/test/resources/application-test.yml` 을 만들지 않는다. `@DataJpaTest(properties = ...)` 로 국소화 | 쿼리 카운트 계측(`generate_statistics`)이 **한 테스트 파일에서만** 필요하다. 전역 설정은 다른 테스트의 로그량과 성능에까지 영향을 준다 |
| **D-24** | 본 문서 §9.1 · §11.1 초안 | `CursorPageResult` 를 `klass/application/dto` → **`common/application/dto`** 로 옮긴다 | Plan §7.2 가 커서 페이지를 공통화한 근거는 "수강신청 목록도 같은 규격을 쓴다"였다. 그런데 `klass` 패키지에 두면 **`enrollment` 가 `klass` 를 경유해야 재사용된다** — 공통화 근거가 스스로를 배반한다. Check 단계에서 발견해 옮겼고, 수강신청 사이클 시작 전이라 import 7곳으로 끝났다 |
| **D-25** | Plan §3.3 · 본 문서 §4.3 초안 | 수정을 **부분 수정(PATCH 시맨틱)이 아니라 전체 필수 수신(전체 교체)** 으로 한다. 요청 DTO 는 등록과 동일한 필수 검증을 쓰고, `UpdateKlassCommand` 의 `Optional<T>` 와 `isEmpty()` · 서비스의 `applyPeriod` 를 걷어냈다 | 클라이언트 수정 화면이 강의의 **전체 값을 들고 있어** 변경되지 않은 필드도 그대로 실어 보낸다. 따라서 누락·`null`·공백은 "안 바꿈"이 아니라 **입력 오류**다. 부분 수정 규격은 그 오류를 200 으로 받아 **조용히 무시**하며, 사용자는 저장에 성공했다고 믿는다. HTTP 메서드는 `PATCH` 를 **유지한다** — `SecurityConfig` 매처·openapi 오퍼레이션 키·`DOCUMENTED_OPERATIONS`·스니펫 이름까지 번져 위험 대비 이득이 없다. 메서드가 `PATCH` 라는 사실이 부분 수정을 뜻하지 않으며, 전체 교체임을 문서와 javadoc 에 명시한다 |
| **D-26** | ERD 정본 §3.2.5 · 본 문서 §4.3 | `cancellation_period_days` 를 **`DRAFT` 상태에서만 변경할 수 있게 좁힌다.** 다른 상태에서 값을 바꾸려 하면 409 `CANCELLATION_PERIOD_NOT_EDITABLE`. **단 같은 값 재전송은 허용된다**(no-op — `null → null` 포함) | 취소 가능 기간은 **수강생과의 약속**이다. ERD 정본은 이 컬럼을 "NULL 이면 전역 기본값"으로만 규정하고 수정 시점을 제한하지 않았지만, `OPEN` 이 되어 신청자가 생긴 뒤에 바꾸면 **이미 신청한 사람의 취소 조건이 사후에, 그리고 불리하게 바뀐다.** `DRAFT` 는 신청 자체가 불가능하므로(ERD 정본 §2.2 — `OPEN` 만 신청을 받는다) 약속의 상대가 아직 없어 안전하다. **같은 값을 no-op 으로 두는 것은 D-25 의 필연적 귀결이다** — 수정이 전체 필수 수신이라 모든 요청이 이 필드를 항상 싣고 오므로, 무조건 거부하면 `OPEN` 강의의 제목만 바꾸려는 요청까지 409 가 되어 **`OPEN` 강의를 아예 수정할 수 없게 된다.** 전체 교체에서 바꾸지 않은 필드에 현재 값을 실어 보내는 것은 정상 동작이므로 그것은 변경이 아니다. **409 인 근거는 §6.2 기준 그대로다** — 요청 값 자체는 유효하고 현재 리소스 상태와 충돌한다. `INVALID_KLASS_STATUS_TRANSITION` 을 재사용하지 않는 것은 그것이 상태 전이 실패이고 이것은 필드 수정 실패이기 때문이다 (§3.2, §4.3) |
| **D-27** | 본 문서 §4.1 초안 (`PATCH`) | 강의 수정을 **`PUT`** 으로 노출한다. 상태 변경(`/{id}/status`)은 `PATCH` 로 남긴다 | D-25 로 수정이 **전체 교체**가 된 뒤에도 메서드가 `PATCH` 로 남아 있었다. `PATCH` 는 "일부만 고친다", `PUT` 은 "이 표현으로 갈아끼운다"는 뜻이라 **메서드 이름과 동작이 어긋났고**, 클라이언트가 일부만 보내도 되는 것으로 오해하면 400 을 받고 이유를 스펙에서 찾아야 한다. 두 엔드포인트의 메서드가 다른 것이 의도다 — 상태 변경은 진짜로 필드 하나만 바꾼다. 전환 대상 5곳: 컨트롤러 · `SecurityConfig` 매처 · L5 오퍼레이션 맵 · L3 7곳 · L4 2곳 |

---

## 13. 다음 단계

1. [ ] `/pdca do klass-management --scope module-1,module-2`
2. [ ] `/pdca do klass-management --scope module-3,module-4`
3. [ ] `/pdca analyze klass-management`

---

## Version History

| 버전 | 날짜 | 변경 | 작성자 |
|------|------|------|--------|
| 0.1 | 2026-09-02 | 최초 작성. Option C 선택. Plan 잠정 결정 4건 확정. divergence D-14~D-17 등재 | developer2@lulumedic.com |
| 1.0 | 2026-09-02 | **`PATCH` → `PUT` 전환** (D-27). D-25 로 전체 교체가 확정된 뒤에도 메서드가 `PATCH` 로 남아 HTTP 규약과 어긋나 있었다. `/{id}/status` 는 `PATCH` 유지 — 그쪽은 진짜 부분 수정이다. `openapi3.json` 에 `GET,PUT` 반영 확인 | developer2@lulumedic.com |
| 0.9 | 2026-09-02 | **취소 가능 기간을 `DRAFT` 에서만 변경 가능하게 좁힘** (D-26 등재). 취소 가능 기간은 수강생과의 약속이라 신청자가 생긴 뒤 사후 변경이 불리하게 작용할 수 있다 — `DRAFT` 는 신청 자체가 불가능하므로(ERD 정본 §2.2) 그때까지만 열어 둔다. **같은 값 재전송은 no-op 으로 허용**하는데, 이것이 없으면 전체 필수 수신(D-25)에서 모든 요청이 이 필드를 싣고 오므로 `OPEN` 강의를 아예 수정할 수 없게 된다. §3.2 메서드 목록·근거 / §4.3 필드 표·본문 / §6.1 새 에러 코드 / §6.2 근거 표 / §10 nullable / §8.2 L1 6건(#12~17) · §8.4 L2 2건(#9·#10) · §8.5 L3 1건(#17) · §8.6 L4 ③ 2건(#15·#16) 등재. **필드 규칙이므로 path 8 / 오퍼레이션 10 은 불변** — 새 스니펫을 만들지 않고 기존 PATCH 스니펫의 description 에 실었다 | developer2@lulumedic.com |
| 0.8 | 2026-09-02 | **수정을 부분 수정 → 전체 필수 수신으로 전환** (D-25 등재). 사용자가 전제를 부정했다 — 클라이언트 수정 화면은 강의 전체 값을 들고 있어 변경되지 않은 필드도 그대로 실어 보내므로, 누락·`null`·공백은 "안 바꿈"이 아니라 입력 오류다. §4.3 PATCH 절 전면 재작성(`Optional` 상태 표·"`null` 이 한 가지 의미"·"없는 요구에 대비하지 않는다" 박스 제거) / §4.1 표·§2.0 비교 표·§8.1·§4.3 시나리오 3~6·§10 nullable·§11.3 정정 / D-18·D-22 근거에서 부분 수정에 의존한 논거 제거. `PATCH` 메서드는 유지 | developer2@lulumedic.com |
| 0.7 | 2026-09-02 | **수정 경로의 공백 값 구멍 차단.** §4.3 이 "`@NotBlank` 가 막는다"고 약속했으나 `UpdateKlassRequest.description` 에 제약이 **하나도 없어** `"   "` 가 도메인까지 도달했다 — D-18 의 필수값 취지가 수정 경로에서만 무너져 있었다. `@NotBlank` 는 `null` 도 거부해 PATCH 를 깨뜨리므로 `@Pattern` 으로 막고 문서의 부정확한 표현을 정정. `title` 도 `@Size(max)` 만으로는 `""` 가 통과하던 것을 함께 차단. L3 3건 추가 | developer2@lulumedic.com |
| 0.6 | 2026-09-02 | §4.3 의 `KlassPeriod` 초안 정정 — D-22 가 §11.1·§12 에서는 반영됐는데 **API 스펙 섹션만 갱신되지 않아** 그 절만 읽는 사람은 없는 클래스가 있다고 읽었다. C-1 과 같은 "문서 일부만 갱신" 패턴의 재발. divergence 표 ID 순서도 정렬 | developer2@lulumedic.com |
| 0.5 | 2026-09-02 | **Check 단계 갭 반영** (Critical 1 · Important 7 · Minor 7). C-1 PATCH 요청 필드 5개 문서화 / I-1 `cursor` 파라미터 / I-2 두 방어선 실제 구현 — **`@Validated` 를 붙이면 500 이 되는 함정 발견** / I-3 §6.5 표 정정(무토큰 401) / I-6 프록시 테스트 실효화 / 무용 테스트 7종 수정 / D-22~D-24 등재 | developer2@lulumedic.com |
| 0.4 | 2026-09-02 | **비관적 락 제거** (D-21). 구현 중 "지금 막는 것이 없다"가 드러났다 — 상대인 수강신청이 2차 범위다. 제목만 바꾸는 요청까지 락을 잡던 과잉도 함께 해소. 2차 복구 좌표를 코드 3곳에 남김. §2.4 전면 재작성 | developer2@lulumedic.com |
| 0.3 | 2026-09-02 | §2.4 락·상세 조회를 JPQL `@Query` → **파생 쿼리**(`findWithLockById` / `findWithCreatorById` + `@EntityGraph`)로 정정. 이전 근거("파생 쿼리로는 표현 불가")가 **틀렸다** — 수식어를 `By` 앞에 두면 Spring Data 가 무시한다. JPQL 문자열은 파생 쿼리와 같은 실패 등급이라 대안이 되지 못했다 | developer2@lulumedic.com |
| 0.2 | 2026-09-02 | `design-validator` 검증 반영 (Critical 4 · Important 12 · Minor 8). 주요 변경: `DRAFT → CLOSED` 를 ERD 대로 허용 / `description` NOT NULL 로 올려 부분 수정 플래그 제거 / `SecurityConfig` 규칙 순서 교정 / 권한·인증 테스트를 L3 → L4 이관 / §2.4 락 명세·§6.5 Advice 확장·§8.8 L5 신설 / D-18~D-20 등재 | developer2@lulumedic.com |
