# 강의 수강신청 + JWT 인증 ERD 설계서

> **Summary**: 7개 테이블의 논리 데이터 모델을 확정한다. 정원 불변식과 토큰 폐기 불변식을 DB 제약과 트랜잭션 규약으로 보증한다.
>
> **Project**: class (greenfield)
> **Version**: 1.12.0
> **Author**: developer2@lulumedic.com
> **Date**: 2026-08-31
> **Status**: Draft
> **Planning Doc**: [class-enrollment-erd.plan.md](../../01-plan/features/class-enrollment-erd.plan.md)
> **인증 설계 원본**: https://github.com/Chals85/sample-jwt-authentication

---

## Context Anchor

| Key | Value |
|-----|-------|
| **WHY** | 동시 신청 시 정원 초과를 스키마 차원에서 원천 차단하고, 신청 주체를 인증으로 신뢰할 수 있게 만든다 |
| **WHO** | 수강생(ROLE_USER) — 신청·취소·내 목록 조회 / 크리에이터(ROLE_CREATOR) — 강의 등록·상태 관리·수강생 목록 조회 |
| **RISK** | 비정규화 카운터(`enrollment_count`)와 실제 좌석 점유 행 수의 정합성 붕괴(drift) |
| **SUCCESS** | 동시 100건이 잔여 1석에 신청 → 정확히 1건 성공, 99건 거부. `enrollment_count` = 좌석 점유 행 수가 항상 일치 |
| **SCOPE** | Phase 1 인증 테이블 통합 → Phase 2 수강 도메인 논리 ERD → Phase 3 선택 기능 + 인덱스·제약·동시성 검증 |

---

## 1. Overview

### 1.1 설계 목표

1. **정원 불변식의 기계적 보증** — 애플리케이션 로직의 성실성에 의존하지 않고, 락 규약 + CHECK 제약이 오버부킹을 막는다.
2. **좌석 점유의 단일 소재지** — 좌석을 점유하는 행은 `enrollment` 한 테이블에만 존재한다. 대기열은 좌석을 점유하지 않는다.
3. **인증 설계의 무손실 인용** — 참고 저장소의 데이터 모델을 그대로 승계하고, 변경한 지점은 divergence로 명시 추적한다.
4. **조회 요건별 인덱스 대응** — 요건에 명시된 4종 조회가 모두 인덱스를 타고, `COUNT(*)`와 OFFSET에 의존하지 않는다.
5. **DB 이식성** — 논리 모델을 정본으로 하고, 벤더별 표현 차이(특히 부분 유니크)를 명시한다.

### 1.2 설계 원칙

- **불변식은 가장 낮은 계층에서 지킨다** — 앱 → DB 제약 순으로 방어를 겹친다. 앱 버그가 데이터를 깨뜨릴 수 없어야 한다.
- **상태는 삭제하지 않고 남긴다** — 취소·폐기·비활성은 모두 상태 전이로 표현하며 물리 삭제가 없다.
- **락 대상은 하나로 고정한다** — 정원과 관련된 모든 트랜잭션은 `klass` 단일 행을 첫 락으로 잡는다.
- **파생 값은 원천과 같은 트랜잭션에서만 갱신한다** — `enrollment_count`는 좌석 점유 행 변경과 항상 동일 트랜잭션에 묶인다.

### 1.3 범위 외 (명시)

본 문서는 **데이터 모델 설계서**다. 아래는 다루지 않는다.

| 항목 | 이유 |
|------|------|
| 코드 파일 구조 / 레이어 배치 (템플릿 §9, §11) | 구현 결정 사항. ERD 확정 후 별도 판단 |
| API 엔드포인트 명세 (템플릿 §4) | 스키마가 확정되어야 도출 가능. 필요 시 후속 문서 |
| UI/UX, 화면 설계 (템플릿 §5) | 프론트엔드가 범위에 없음 |
| 인증 흐름 로직 (토큰 발급·검증·필터 체인) | 참고 저장소의 것을 그대로 사용 |
| 외부 결제 연동 | 요건상 상태 변경으로 대체 |
| 알림 발송 (대기열 승격 통보 등) | 채널 미정 |
| **`EnrollmentStatusHistory` 감사 로그 테이블 (Plan FR-15)** | **미채택 (YAGNI).** 현 요건에 상태 변경 이력을 조회하는 기능이 없고, 상태별 타임스탬프 컬럼(`created_at`/`confirmed_at`/`cancelled_at`)이 각 전이의 발생 시각을 이미 보존한다. 다만 취소 *원인*(사용자 취소 vs 만료)은 구분되지 않는다 — §2 ⑦ 참조 |

---

## 2. 미결 사항 확정

Plan 단계에서 Design으로 넘긴 6건을 아래와 같이 확정한다.

| # | 항목 | 확정 | 근거 |
|:-:|------|------|------|
| ① | 카운터 집계 기준 | **`enrollment`의 PENDING + CONFIRMED** | Plan v0.4에서 확정. 좌석 점유가 한 테이블에만 존재하도록 대기열 설계를 맞춘 결과 |
| ② | CLOSED 시 PENDING 처리 및 승격 허용 | **기존 PENDING의 결제 확정은 허용, 대기열 승격은 중단** | 두 사안은 성격이 다르므로 분리해 결정한다. 상세 근거는 §2.1 |
| ③ | 시각 타입 | **하이브리드** — 사업 일자는 `LocalDate`, 그 외 시각은 `LocalDateTime` + 주입된 `Clock` | 수강 기간은 "며칠부터 며칠까지"라는 날짜 개념이라 시각·시간대가 의미를 갖지 않는다. `LocalDate`로 바꾸면 시간대 모호성이 원천 제거된다. 반면 토큰 만료·감사 시각은 인증 도메인과의 일관성과 테스트 결정성이 중요해 기존 타입을 유지한다 |
| ④ | 회원가입 API 필요 여부 | **ERD 범위 외** — `users` 테이블이 이미 가입을 수용한다 | 스키마에 영향이 없다. 시딩(`DefaultUserInitializer`)만으로 검증 가능하며, 가입 API 추가 여부는 구현 시 판단 |
| ⑤ | `is_revoked` / `revoked_at` 중복 | **둘 다 유지 (원본 승계)** | 참고 저장소의 `rotate()` 도메인 로직이 boolean을 직접 읽는다. 중복 제거의 이득(단일 진실 공급원)보다 인용 divergence를 늘리는 비용이 크다. 두 값의 정합성은 `rotate()` 한 메서드에서만 갱신되므로 어긋날 경로가 없다 |
| ⑥ | PENDING 만료 기한 | **DIRECT 30분 / WAITLIST 10분** (프로퍼티로 외부화) | 일반 신청은 결제 수단 준비 시간을 고려해 여유를 둔다. 승격은 이미 알림을 받고 대기 중인 상태이므로 짧게 잡아 뒷 순번을 오래 붙잡지 않는다 |

> **⑦ (신규 미결)** — **취소 원인을 데이터에 남길 것인가.** §3.4는 `PENDING → CANCELLED`를 "사용자 취소 / 만료" 한 전이로 묶고 §4.4 5번이 `사용자 취소 요청` 플래그를 입력으로 받지만, **그 플래그 값이 저장되지 않는다.** `ck_enrollment_pending`이 취소 시 `expires_at`을 NULL로 강제하므로 사후에 구분할 단서도 사라진다. 만료율 측정이나 환불 정책 분기가 필요하면 `enrollment.cancel_reason` ENUM(`USER` / `EXPIRED`)을 추가하는 것이 감사 테이블(FR-15)보다 싸다. **현재 요건에는 해당 기능이 없어 미결로 둔다.**

> **⑧ (신규 미결)** — **`refresh_token` 정리 주기.** `revoked_access_token`은 `jwt.revoked-token-cleanup-interval`로 정리되지만 `refresh_token`은 주기가 정해지지 않았다(§5.3). 회전마다 새 행이 쌓이므로 정리가 없으면 무한 증가한다. 만료 후 즉시 삭제할지, 감사 목적으로 일정 기간 보관할지 판단이 필요하다.

### 2.1 ②번 결정의 근거 — 확정과 승격을 분리하는 이유

요건의 문구는 `CLOSED`: 모집 마감 **(신청 불가)** 다. 금지 대상은 **신청**이며, 결제 확정은 신청이 아니라 이미 한 신청의 후속 처리다. 그러나 승격은 다르다.

| 행위 | 명단에 미치는 영향 | 판정 |
|------|--------------------|:----:|
| 기존 PENDING의 결제 확정 | 이미 명단에 있는 사람의 상태 변화 | **허용** |
| 대기열 승격 | **명단에 없던 사람이 새로 들어온다** | **중단** |

"모집 마감"이 막으려는 것이 바로 후자다.

**확정을 허용하는 이유** — PENDING은 이미 좌석을 점유하고 있다(카운터에 포함). 확정을 막으면 그 행의 처리를 정해야 하는데, 두 선택 모두 나쁘다.

| 선택 | 결과 |
|------|------|
| 그냥 둔다 | 좌석이 만료까지 묶인 채 아무도 쓰지 못하고, 사용자는 결제도 못 한다 |
| CLOSED 전환 시 일괄 취소 | 결제 페이지에 있던 사용자의 자리가 그 순간 사라진다. 결제 트랜잭션과 경합한다 |

확정을 허용하면 이 문제가 발생하지 않으며, `expires_at`이 이미 상한을 걸어둔다.

**승격을 중단하는 이유** — 허용하면 명단 확정이 계속 미뤄진다.

```
CLOSED, 정원 10, 전원 CONFIRMED
 → 1명 취소            → 9 → 대기자 승격 → PENDING(10분) → 10
 → 그 사람 미결제 만료  → 9 → 다음 대기자 승격 → PENDING(10분) → 10
 → 또 만료              → 9 → 다음 대기자 승격 → ...

대기자 5명이면 최대 50분. 그 동안 수강생 명단이 계속 바뀐다.
```

크리에이터가 마감을 누르는 목적은 명단을 확정해 교재·강의실 등을 준비하는 것인데, 승격 체인이 그것을 흔든다.

**결과: 명단에 새로 들어오는 사람이 없어지는 시점에 명확한 상한이 생긴다.** (취소로 인한 감소는 취소 가능 기간 내에 계속 가능하다 — 이 결정이 막는 것은 *신규 진입*이다.)

```
명단 확정 = CLOSED 시각 + max(남은 PENDING의 expires_at)  ≤  CLOSED + 30분
```

**대가**: CLOSED 후 취소가 발생하면 그 자리는 빈 채로 끝난다. 다만 마감은 크리에이터의 선택이며, 정원을 채우고 싶다면 마감을 미루면 된다. 빈자리 하나보다 명단이 흔들리는 비용이 크다.

---

### 2.2 ③번 결정에 따른 컬럼 변경

| Plan 표기 | Design 확정 | 타입 |
|-----------|-------------|------|
| `klass.starts_at` | **`klass.starts_on`** | `DATE` |
| `klass.ends_at` | **`klass.ends_on`** | `DATE` |

명명 컨벤션을 확장한다: **시각(timestamp)은 `{동사}_at`, 날짜(date)는 `{동사}_on`.** 컬럼명만 보고 타입을 구분할 수 있다.

#### 타입별 사용 지점

| 타입 | 컬럼 | 이유 |
|------|------|------|
| `DATE` (`LocalDate`) | `klass.starts_on`, `klass.ends_on` | 사업적 날짜. "며칠부터 며칠까지"에 시각·시간대가 의미를 갖지 않는다 |
| `TIMESTAMP` (`LocalDateTime`) | `klass.created_at`, `users.created_at`, `refresh_token.*`, `revoked_access_token.*`, `enrollment.created_at`/`expires_at`/`confirmed_at`/`cancelled_at`, `waitlist.created_at`/`promoted_at` | 순간을 다툰다. 토큰 만료·PENDING 만료·취소 가능 기간 계산은 분 단위 판정이 필요하고, 인증 도메인과 일관성을 유지한다 |
| `INT` (일 수) | `klass.cancellation_period_days` | 기간(duration)이지 시점이 아니다 |

> ⚠️ **남는 경계 하나**: `DATE`와 현재 시각을 비교하는 지점에서는 시간대가 다시 개입한다. "지금이 수강 기간 안인가"를 판정하려면 현재 시각을 날짜로 환산해야 하고, 그 환산은 주입된 `Clock`의 시간대가 결정한다. 따라서 **날짜 비교는 반드시 `LocalDate.now(clock)`으로 얻은 값과 수행하고, `LocalDate.now()`를 직접 호출하지 않는다.** 이 규칙을 지키면 시간대 결정이 `ClockConfig` 한 곳에 모인다.
>
> 참고로 **신청 가능 여부는 수강 기간이 아니라 `status`가 결정한다**(§4.2는 `status = 'OPEN'`만 검사). 요건에 모집 기간 필드가 없고 `OPEN`/`CLOSED`가 그 역할을 하므로, `starts_on`/`ends_on`은 표시·안내용이며 신청 차단 조건이 아니다. 수강 시작 후 신청을 막고 싶다면 크리에이터가 `CLOSED`로 전환한다.

---

## 3. Data Model

### 3.1 ERD

```mermaid
erDiagram
    users ||--o{ user_roles : "권한 보유"
    users ||..o{ refresh_token : "발급"
    users ||..o{ revoked_access_token : "폐기 기록"
    users ||--o{ klass : "개설"
    users ||--o{ enrollment : "신청"
    users ||--o{ waitlist : "대기"
    klass ||--o{ enrollment : "수강 신청"
    klass ||--o{ waitlist : "대기 등록"

    users {
        BIGINT id PK
        VARCHAR_50 username UK "로그인 아이디"
        VARCHAR_100 password "BCrypt 해시"
        BOOLEAN is_enabled "계정 활성 여부"
        TIMESTAMP created_at "가입 시각"
    }

    user_roles {
        BIGINT user_id PK_FK "사용자"
        VARCHAR_20 role PK "ROLE_USER/ADMIN/CREATOR"
    }

    refresh_token {
        BIGINT id PK
        BIGINT user_id "소유자 (값 참조)"
        VARCHAR_64 token_hash UK "SHA-256 hex"
        TIMESTAMP issued_at "발급 시각"
        TIMESTAMP expires_at "만료 시각"
        BOOLEAN is_revoked "폐기 여부"
        TIMESTAMP revoked_at "폐기 시각, NULL 가능"
    }

    revoked_access_token {
        BIGINT id PK
        VARCHAR_36 jti UK "원 토큰 jti"
        BIGINT user_id "소유자 (값 참조)"
        TIMESTAMP expires_at "원 토큰 exp, purge 기준"
        TIMESTAMP revoked_at "로그아웃 시각"
    }

    klass {
        BIGINT id PK
        BIGINT creator_id FK "개설자"
        VARCHAR_200 title "강의 제목"
        TEXT description "강의 내용, 필수"
        DECIMAL price "수강료"
        INT capacity "최대 정원"
        INT enrollment_count "좌석 점유 인원"
        VARCHAR_20 status "DRAFT/OPEN/CLOSED"
        DATE starts_on "수강 시작일"
        DATE ends_on "수강 종료일"
        INT cancellation_period_days "취소 가능 기간, NULL=전역기본"
        TIMESTAMP created_at "등록 시각"
        TIMESTAMP updated_at "최종 수정 시각"
    }

    enrollment {
        BIGINT id PK
        BIGINT klass_id FK "강의"
        BIGINT user_id FK "신청자"
        VARCHAR_20 status "PENDING/CONFIRMED/CANCELLED"
        VARCHAR_20 source "DIRECT/WAITLIST"
        TIMESTAMP created_at "신청 시각"
        TIMESTAMP expires_at "PENDING 만료 예정, NULL 가능"
        TIMESTAMP confirmed_at "결제 확정 시각, NULL 가능"
        TIMESTAMP cancelled_at "취소 시각, NULL 가능"
        BIGINT active_user_key "부분유니크 대체 컬럼"
    }

    waitlist {
        BIGINT id PK
        BIGINT klass_id FK "강의"
        BIGINT user_id FK "대기자"
        INT position "대기 순번"
        VARCHAR_20 status "WAITING/PROMOTED/CANCELLED"
        TIMESTAMP created_at "대기 등록 시각"
        TIMESTAMP promoted_at "승격 시각, NULL 가능"
        BIGINT waiting_user_key "부분유니크 대체 컬럼"
    }
```

> **관계 표기**: 실선(`||--o{`)은 **실제 FK 제약**, 점선(`||..o{`)은 **FK 없는 값 참조**다.
>
> **FK 정책**: *영속적 사업 실체가 사용자를 참조하면 FK를 걸고, 파생적·단기 인증 레코드는 값 참조를 유지한다.* 근거는 §3.1.1.

#### 3.1.1 FK 정책의 근거

참고 저장소가 `refresh_token.user_id`에 FK를 걸지 않은 근거는 **ORM 매핑에 관한 것**이다.

> `@ManyToOne User` 대신 `userId` 값만 들고 있다. 서로 다른 애그리거트를 객체 참조로 묶으면 트랜잭션 경계가 흐려지고, 토큰 하나를 읽을 때 사용자까지 로딩된다.

이 근거가 뒷받침하는 것은 **자바 필드를 `Long userId`로 두는 것**까지다. DDL의 FK 제약은 별개 결정이며, `Long userId`를 쓰면서 FK를 거는 것을 JPA는 막지 않는다. 원본 자체도 일률적이지 않다 — `user_roles.user_id`에는 실제 FK가 걸려 있다.

**고아 행의 피해가 테이블 성격에 따라 다르다.**

| 테이블 | 고아가 됐을 때 | 판정 |
|--------|----------------|:----:|
| `refresh_token`, `revoked_access_token` | 최대 14일 후 만료되고, `sub`가 없는 사용자면 인증이 실패한다. 피해가 자기 완결적 | 값 참조 유지 |
| `klass` | **소유자 없는 강의가 영구히 남는다.** §7의 소유권 검사 `creator_id == sub`를 아무도 통과할 수 없어 상태 변경·수강생 목록 조회가 불가능해진다. 수강생은 이미 신청해 있는데 관리 주체가 없다 | **FK 부착** |
| `enrollment` | 정원을 점유하면서 정작 그 사용자는 자기 신청 목록에서 볼 수 없다 | **FK 부착** |
| `waitlist` | 승격 자격 검증이 불가능한 대기 행이 순번을 차지한다 | **FK 부착** |

**결정적으로 이 설계에는 물리 삭제가 없다.** 사용자 비활성은 `is_enabled = false`이고 강의·신청도 상태로만 다룬다. `ON DELETE`가 발동할 경로가 없으므로 **FK의 런타임 비용은 사실상 0이고, 남는 것은 앱 버그로부터의 보호뿐이다** — 존재하지 않는 `creator_id`로 강의가 생성되는 사고를 DB가 거부한다.

> JPA 매핑은 여전히 `Long userId` / `Long creatorId`를 쓴다. `@ManyToOne`을 쓰지 않으므로 객체 참조의 부작용은 없고, FK만 DDL에 추가된다.

#### 3.1.2 사용자 참조 컬럼의 명명 규칙

`users.id`를 가리키는 컬럼이 6개인데 이름이 두 종류다. 우연이 아니라 규칙이 있다.

> **그 테이블의 행이 사용자가 *만든 것*이면 역할명(`creator_id`), 사용자 *자신의 행위·상태 기록*이면 `user_id`.**

| 테이블 | 행의 성격 | 컬럼명 |
|--------|-----------|--------|
| `klass` | 사용자가 **만든** 강의 | **`creator_id`** |
| `enrollment` | 사용자의 **신청 행위** | `user_id` |
| `waitlist` | 사용자의 **대기 상태** | `user_id` |
| `refresh_token` | 사용자의 **토큰** | `user_id` |
| `revoked_access_token` | 사용자의 **폐기 기록** | `user_id` |
| `user_roles` | 사용자의 **권한** | `user_id` |

**왜 `klass`만 다른가.** `klass.user_id`라고 쓰면 "강의의 사용자"가 개설자인지 수강생인지 모호하다 — 강의에는 두 종류의 사용자가 얽혀 있다. 또한 `klass`에 두 번째 사용자 참조(`approved_by_id`, `co_instructor_id` 등)가 생기는 순간 `user_id` + `user_id_2`가 되어 쓸 수 없다.

성격 구분은 FK 정책과도 일치한다. **강의는 개설자가 비활성화되어도 남아야 할 독립적 실체**이고(그래서 `RESTRICT`), 신청·토큰·권한은 사용자에 종속된 기록이다.

**운영상 유일한 비용**은 "이 사용자와 관련된 모든 행 찾기" 류의 스크립트에서 예외를 하나 기억해야 한다는 점이다.

```sql
SELECT 'enrollment' AS t, id FROM enrollment WHERE user_id = :userId
UNION ALL SELECT 'waitlist',      id FROM waitlist      WHERE user_id = :userId
UNION ALL SELECT 'refresh_token', id FROM refresh_token WHERE user_id = :userId
UNION ALL SELECT 'klass',       id FROM klass       WHERE creator_id = :userId;
--                                                            ^^^^^^^^^^ 유일한 예외
```

예외가 1개면 관리 가능하다. **새 테이블을 추가할 때 위 규칙을 적용해 예외가 임의로 늘어나지 않게 한다.**

> JPA에서는 `@Column(name = "creator_id")`로 매핑을 명시한다. 값 참조(`Long creatorId`)를 쓰고 있으므로 어차피 컬럼명을 명시하는 구조다.

---

### 3.2 테이블 명세

#### 3.2.1 `users` — 사용자 (인용)

| 컬럼 | 타입 | NULL | 기본값 | 설명 |
|------|------|:----:|--------|------|
| `id` | BIGINT IDENTITY | N | auto | PK. **JWT `sub` 클레임과 모든 `user_id` 참조가 가리키는 값** |
| `username` | VARCHAR(50) | N | — | 로그인 아이디. **실명이 아니다.** Spring Security 관례명 |
| `password` | VARCHAR(100) | N | — | BCrypt 해시. 평문은 DB·로그·응답 어디에도 저장되지 않는다 |
| `is_enabled` | BOOLEAN | N | TRUE | 계정 활성 여부. 물리 삭제 대신 이 플래그로 비활성화한다 |
| `created_at` | TIMESTAMP | N | — | 가입 시각. 팩토리가 주입된 `Clock`으로 채운다 (`updatable = false`) |

- PK: `id`
- UNIQUE: `username`
- **divergence**: 원본의 `enabled` → `is_enabled` (boolean 접두어 규칙 적용)

#### 3.2.2 `user_roles` — 사용자 권한 (인용 + 확장)

| 컬럼 | 타입 | NULL | 설명 |
|------|------|:----:|------|
| `user_id` | BIGINT | N | 복합 PK. `users`에 대한 **실제 FK** (`@ElementCollection`이라 애그리거트 내부) |
| `role` | VARCHAR(20) | N | 복합 PK. 권한명 |

- PK: `(user_id, role)`
- FK: `user_id → users(id)` ON DELETE CASCADE ON UPDATE CASCADE
- **divergence**: `ROLE_CREATOR` 값 추가

> 복합 PK 구조라 **한 사용자가 여러 권한을 겸할 수 있다.** 수강생이면서 크리에이터인 경우가 스키마 변경 없이 성립한다.

#### 3.2.3 `refresh_token` — 재발급 토큰 (인용)

| 컬럼 | 타입 | NULL | 기본값 | 설명 |
|------|------|:----:|--------|------|
| `id` | BIGINT IDENTITY | N | auto | PK |
| `user_id` | BIGINT | N | — | 소유자. **값 참조 (FK 없음)** |
| `token_hash` | VARCHAR(64) | N | — | 토큰 원문의 SHA-256 hex. **원문은 저장하지 않는다** — DB 유출 시에도 이 값으로 API 호출이 불가하다 |
| `issued_at` | TIMESTAMP | N | — | 발급 시각 (JWT `iat`) |
| `expires_at` | TIMESTAMP | N | — | 만료 시각 (JWT `exp`). JWT와 **중복 보관**하여 두 값이 어긋나는 상황(수동 DB 편집, 시계 오차)을 `rotate()`가 방어 |
| `is_revoked` | BOOLEAN | N | FALSE | 폐기 여부. **`true`인 토큰의 재사용이 탈취 신호** |
| `revoked_at` | TIMESTAMP | Y | NULL | 폐기 시각 |

- PK: `id` / UNIQUE: `token_hash`
- INDEX: `idx_refresh_token_user_id(user_id)`
- CHECK: `expires_at > issued_at`
- CHECK: `ck_refresh_token_revoked` — **쌍방향** `is_revoked = (revoked_at IS NOT NULL)`
  - §1.2의 "앱 버그가 데이터를 깨뜨릴 수 없어야 한다"를 이 테이블에도 적용한다. §2 ⑤가 두 컬럼을 모두 유지하기로 했으므로 `enrollment`·`waitlist`와 같은 수준의 방어를 거는 것이 일관된다
  - **쌍방향을 택한 결과**: §4.7 4번의 탈취 대응 일괄 무효화가 `is_revoked`만 세우면 제약 위반으로 전량 실패한다. `revoked_at`도 함께 채워야 하며(§4.7 4번에 명시), 이는 "언제 무효화되었는가"가 감사에 필요하므로 올바른 강제다
- **divergence**: 원본의 `revoked` → `is_revoked`

#### 3.2.4 `revoked_access_token` — 폐기된 접근 토큰 (인용)

| 컬럼 | 타입 | NULL | 설명 |
|------|------|:----:|------|
| `id` | BIGINT IDENTITY | N | PK |
| `jti` | VARCHAR(36) | N | 원 토큰의 `jti`(UUID). **해시가 아닌 `jti`를 쓰는 이유** — 토큰을 식별할 뿐 그것만으로 API를 호출할 수 없어 유출돼도 위험하지 않고, 원문 없이 클레임만으로 대조되어 검증 경로가 짧다 |
| `user_id` | BIGINT | N | 소유자. 값 참조 |
| `expires_at` | TIMESTAMP | N | 원 토큰의 `exp`. **purge 기준이지 검증 기준이 아니다.** ⚠️ 실제 `exp`보다 앞당겨 잡으면 아직 유효한 토큰이 블랙리스트에서 먼저 사라져 다시 통과한다 |
| `revoked_at` | TIMESTAMP | N | 로그아웃 시각. 감사 추적용 |

- PK: `id` / UNIQUE: `uq_revoked_access_token_jti(jti)`
- INDEX: `idx_revoked_access_token_expires_at(expires_at)` — 정리 배치용
- **`jti` UNIQUE 인덱스는 성능 요구사항이다.** 보호된 API 요청마다 조회되므로 인덱스가 없으면 블랙리스트가 커질수록 모든 API가 함께 느려진다.

#### 3.2.5 `klass` — 강의 (신규)

> **명명**: 엔티티 `Klass`, 테이블 `klass`, FK 컬럼 `klass_id` — **한 개념에 한 이름**을 쓴다. 자바에서 `Class`는 `java.lang.Class`와 충돌해 선언 자체가 불가능하므로 `Klass`로 우회하며, 이름을 통일했기 때문에 `@Table(name=...)` 매핑이 필요하지 않다. 단수형이라 명명 원칙(단수 snake_case)의 예외도 아니다.

| 컬럼 | 타입 | NULL | 기본값 | 설명 |
|------|------|:----:|--------|------|
| `id` | BIGINT IDENTITY | N | auto | PK. 커서 페이지네이션 정렬 키 |
| `creator_id` | BIGINT | N | — | 개설자. **FK → `users(id)`.** 크리에이터 전용 조회의 소유권 검사 기준 |
| `title` | VARCHAR(200) | N | — | 강의 제목 |
| `description` | TEXT | N | — | 강의 내용. **필수값** (v1.11 변경 — klass-management D-18) |
| `price` | DECIMAL(12,2) | N | — | 수강료. **부동소수점 금지** (오차 배제) |
| `capacity` | INT | N | — | 최대 정원 |
| `enrollment_count` | INT | N | 0 | **좌석 점유 인원** = `enrollment` 중 PENDING + CONFIRMED 개수. 락 대상 행에 있어 갱신 비용이 사실상 0이고, 상세·목록 조회에서 `COUNT(*)`를 제거한다 |
| `status` | VARCHAR(20) | N | 'DRAFT' | 강의 상태 |
| `starts_on` | DATE | N | — | 수강 시작일 |
| `ends_on` | DATE | N | — | 수강 종료일 |
| `cancellation_period_days` | INT | Y | NULL | 취소 가능 기간(일). NULL이면 전역 기본값 적용 |
| `created_at` | TIMESTAMP | N | — | 등록 시각 |
| `updated_at` | TIMESTAMP | N | — | 최종 수정 시각. 생성 시 `created_at`과 같은 값 (v1.11 신규 — klass-management §3.1) |

- PK: `id`
- FK: `creator_id → users(id)` ON DELETE RESTRICT ON UPDATE CASCADE
- CHECK: `capacity > 0`
- CHECK: `enrollment_count >= 0 AND enrollment_count <= capacity` ← **정원 불변식의 최종 방어선**
- CHECK: `price >= 0`
- CHECK: `ends_on >= starts_on`
- CHECK: `cancellation_period_days IS NULL OR cancellation_period_days >= 0`
- INDEX: `idx_klass_status(status, id DESC)` — 목록 조회(상태 필터)
- INDEX: `idx_klass_creator(creator_id, id DESC)` — 크리에이터의 내 강의 목록

> **v1.11 변경 2건** (klass-management 사이클). ① `updated_at` 신규 — 수정 API가 생기면서 "언제 바뀌었나"를 알 길이 필요해졌다. NULL을 허용하지 않는 것은 "수정된 적 없음"이 `created_at == updated_at`으로 이미 표현되기 때문이다. ② `description` NOT NULL — 원 요구사항이 등록 항목으로 "내용"을 나열했고 선택이라는 단서가 없었다. **근거는 이것 하나다** (klass-management D-18).
>
> ⚠️ **②의 근거에서 한 줄을 걷어냈다** (v1.12 정리). v1.11 은 "필수로 두면 PATCH에서 `null`이 '안 바꿈'인지 '지워라'인지 갈리는 모호함이 사라진다"를 함께 적었으나, **klass-management D-25 가 부분 수정을 폐기하고 전체 필수 수신(전체 교체)으로 바꿔 그 논거가 성립하지 않는다** — 전체 교체에는 애초에 그 중의성이 없다(모든 필드가 항상 실려 오므로 `null` 은 언제나 "그 값으로 저장하라"다). **D-18 자체는 유효하다.** 위의 원 요구사항 근거로 홀로 선다.

> **`cancellation_period_days` 는 `DRAFT` 상태에서만 변경할 수 있다** (v1.12 — klass-management D-26). 이 컬럼의 스키마는 그대로다(nullable, `>= 0` CHECK). 좁혀진 것은 **수정 시점**이며, 그 판정은 애플리케이션(`Klass.changeCancellationPeriodDays`)이 한다 — 상태에 따라 갈리는 규칙이라 CHECK 로 표현할 수 없다. 취소 가능 기간은 수강생과의 약속이므로 `OPEN` 이 되어 신청자가 생긴 뒤에 바꾸면 이미 신청한 사람의 취소 조건이 사후에 불리하게 바뀐다. `DRAFT` 는 신청 자체가 불가능하다(§2.2 — `OPEN` 만 신청을 받는다). 위반 시 409 `CANCELLATION_PERIOD_NOT_EDITABLE`. 단 **같은 값 재전송은 허용된다** — 수정이 전체 교체라 모든 요청이 이 필드를 항상 싣고 오기 때문이다 (klass-management Design §4.3 · §12 D-26).

> ⚠️ **정원 축소**: `capacity`를 현재 `enrollment_count`보다 낮게 수정하면 §4.8의 앱 검사가 먼저 거부하고, 우회해도 CHECK가 거부한다. 이는 데이터를 지키는 올바른 동작이며, 애플리케이션은 정원 축소를 시도할 때 이 실패를 사용자에게 설명해야 한다.

#### 3.2.6 `enrollment` — 수강 신청 (신규)

| 컬럼 | 타입 | NULL | 기본값 | 설명 |
|------|------|:----:|--------|------|
| `id` | BIGINT IDENTITY | N | auto | PK. 커서 페이지네이션 정렬 키 |
| `klass_id` | BIGINT | N | — | 강의. **실제 FK** |
| `user_id` | BIGINT | N | — | 신청자. **FK → `users(id)`.** JWT `sub`와 일치해야 한다 |
| `status` | VARCHAR(20) | N | 'PENDING' | 신청 상태 |
| `source` | VARCHAR(20) | N | 'DIRECT' | 신청 출처. 만료 기한 분기에 사용 |
| `created_at` | TIMESTAMP | N | — | 신청 시각 |
| `expires_at` | TIMESTAMP | Y | NULL | **PENDING 만료 예정 시각.** `created_at + (source별 기한)`. `PENDING`이 아니면 NULL |
| `confirmed_at` | TIMESTAMP | Y | NULL | 결제 확정 시각. **취소 가능 기간의 기산점** |
| `cancelled_at` | TIMESTAMP | Y | NULL | 취소 시각 |
| `active_user_key` | BIGINT | Y | (생성) | 부분 유니크 대체 컬럼 (§3.5.1 참조). 활성 상태면 `user_id`, `CANCELLED`면 NULL |

- PK: `id`
- FK: `klass_id → klass(id)` ON DELETE RESTRICT ON UPDATE CASCADE
- FK: `user_id → users(id)` ON DELETE RESTRICT ON UPDATE CASCADE
- UNIQUE: `(klass_id, active_user_key)` ← **동일 사용자의 활성 중복 신청 차단.** 취소 후 재신청은 허용
- CHECK: `status = 'PENDING'` ↔ `expires_at IS NOT NULL`
- CHECK: `status = 'CONFIRMED'` → `confirmed_at IS NOT NULL`
- CHECK: `status = 'CANCELLED'` → `cancelled_at IS NOT NULL`
- INDEX: `idx_enrollment_user(user_id, id DESC)` — 내 신청 목록 + 커서 페이지네이션
- INDEX: `idx_enrollment_klass_status(klass_id, status, id DESC)` — 크리에이터의 수강생 목록
- INDEX: `idx_enrollment_expiry(expires_at)` — 만료 스캔 배치. **부분 인덱스(`WHERE status = 'PENDING'`)를 쓰지 않는다** — §3.5.1의 이유로 H2·MySQL이 지원하지 않는다

> `ON DELETE RESTRICT`인 이유: 신청 이력이 있는 강의는 삭제될 수 없다. 강의 폐강은 `status`로 다룰 문제이며, 물리 삭제는 이 설계에 존재하지 않는다.

#### 3.2.7 `waitlist` — 대기열 (신규, 선택 기능)

| 컬럼 | 타입 | NULL | 기본값 | 설명 |
|------|------|:----:|--------|------|
| `id` | BIGINT IDENTITY | N | auto | PK |
| `klass_id` | BIGINT | N | — | 강의. **실제 FK** |
| `user_id` | BIGINT | N | — | 대기자. **FK → `users(id)`** |
| `position` | INT | N | — | 대기 순번. `klass` 락 하위에서 발급 |
| `status` | VARCHAR(20) | N | 'WAITING' | 대기 상태 |
| `created_at` | TIMESTAMP | N | — | 대기 등록 시각 |
| `promoted_at` | TIMESTAMP | Y | NULL | 승격 시각 |
| `waiting_user_key` | BIGINT | Y | (생성) | 부분 유니크 대체 컬럼. `WAITING`이면 `user_id`, 그 외 NULL |

- PK: `id`
- FK: `klass_id → klass(id)` ON DELETE RESTRICT ON UPDATE CASCADE
- FK: `user_id → users(id)` ON DELETE RESTRICT ON UPDATE CASCADE
- UNIQUE: `(klass_id, position)` ← **순번 중복 물리 차단.** `MAX(position)+1` 계산의 경합을 DB가 막는다
- UNIQUE: `(klass_id, waiting_user_key)` ← 동일 사용자의 중복 대기 차단
- CHECK: `position > 0`
- CHECK: `status = 'PROMOTED'` → `promoted_at IS NOT NULL`
- INDEX: `idx_waitlist_next(klass_id, status, position)` — 다음 승격 대상 조회

> **`waitlist`는 좌석을 점유하지 않는다.** 이것이 이 설계의 핵심 결정이며, 그 결과 `enrollment_count`가 `enrollment` 한 테이블만 보면 검증 가능하다.

### 3.3 ENUM 정의

```
KlassStatus         DRAFT | OPEN | CLOSED
EnrollmentStatus    PENDING | CONFIRMED | CANCELLED
EnrollmentSource    DIRECT | WAITLIST
WaitlistStatus      WAITING | PROMOTED | CANCELLED
Role                ROLE_USER | ROLE_ADMIN | ROLE_CREATOR
TokenType           ACCESS | REFRESH
```

| ENUM | 값 | 의미 |
|------|----|------|
| `KlassStatus` | `DRAFT` | 초안. 신청 불가. 목록·상세는 **개설자에게만** 노출된다(§7) |
| | `OPEN` | 모집 중. **신청 가능한 유일한 상태** |
| | `CLOSED` | 모집 마감. **신규 신청 차단 + 대기열 승격 중단.** 기존 PENDING의 결제 확정만 허용(§2.1) |
| `EnrollmentStatus` | `PENDING` | 신청 완료, 결제 대기. **좌석 점유** |
| | `CONFIRMED` | 결제 완료, 수강 확정. **좌석 점유** |
| | `CANCELLED` | 취소됨(사용자 취소 또는 만료). 좌석 미점유. 종착 상태 |
| `EnrollmentSource` | `DIRECT` | 사용자가 직접 신청 |
| | `WAITLIST` | 대기열 승격으로 생성 |
| `WaitlistStatus` | `WAITING` | 대기 중 |
| | `PROMOTED` | 승격되어 `enrollment`로 전환됨. 종착 상태 |
| | `CANCELLED` | 대기 종료. 사용자의 자발적 포기(§4.9) **또는** 승격 시 부적격 판정(§4.4 9-d) **또는** 강의 마감 시 일괄 정리(§4.8 5번). 종착 상태. 세 원인은 구분해 저장하지 않는다 — §2 ⑦과 같은 성격의 한계 |

> **저장 방식**: `@Enumerated(EnumType.STRING)`. **ordinal 금지** — 값 순서가 바뀌면 기존 데이터가 조용히 다른 의미가 된다.

### 3.4 상태 전이표

#### `KlassStatus`

| From \ To | DRAFT | OPEN | CLOSED |
|---|:-:|:-:|:-:|
| **DRAFT** | — | ✅ 모집 시작 | ✅ 개설 철회 |
| **OPEN** | ⚠️ 초안으로 되돌림 (단, `enrollment_count = 0`인 경우만) | — | ✅ 모집 마감 |
| **CLOSED** | ❌ | ⚠️ 조건부 (아래) | — |

- `OPEN → DRAFT`: 신청자가 있으면 금지. 이미 신청한 사용자의 신청이 무효화되기 때문
- `CLOSED → OPEN`: **재모집을 허용할지는 정책 문제.** 허용 시 `enrollment_count < capacity`를 확인한다 — 데이터 정합성 때문이 아니라(`ck_klass_count`가 이미 상한을 보증하고, 정원이 차면 §4.2가 거부한다) **정원이 찬 강의를 '모집 중'으로 표시하면 사용자가 신청 가능하다고 오해**하기 때문이다. 마감 후 명단이 다시 흔들리는 것도 §2.1의 결정과 상충하므로 **초기 구현은 금지**를 권한다
- `DRAFT → CLOSED`: 초안 상태로 폐기. 신청자가 없으므로 안전

#### `EnrollmentStatus`

| From \ To | PENDING | CONFIRMED | CANCELLED |
|---|:-:|:-:|:-:|
| **PENDING** | — | ⚠️ 결제 확정 (만료 전에만) | ✅ 사용자 취소 / 만료 |
| **CONFIRMED** | ❌ 되돌릴 수 없다 | — | ✅ 취소 가능 기간 내에만 |
| **CANCELLED** | ❌ 종착 | ❌ 종착 | — |

- `PENDING → CONFIRMED` 조건: `expires_at > now`. 만료 시각이 지났으면 §4.6 배치가 아직 처리하지 않았어도 거부한다 (§4.3 4번)
- `CONFIRMED → CANCELLED` 조건: `now <= confirmed_at + COALESCE(klass.cancellation_period_days, 전역기본)`
- `CANCELLED`에서 나가는 전이는 없다. 재신청은 **새 행**을 만든다 (그래서 부분 유니크가 필요하다)
- 모든 좌석 반납 전이(`→ CANCELLED`)는 `enrollment_count` 감소를 동반한다

#### `WaitlistStatus`

| From \ To | WAITING | PROMOTED | CANCELLED |
|---|:-:|:-:|:-:|
| **WAITING** | — | ✅ 승격 | ✅ 대기 포기 |
| **PROMOTED** | ❌ 종착 | — | ❌ 종착 |
| **CANCELLED** | ❌ 종착 | ❌ | — |

- `PROMOTED` 이후의 취소는 `waitlist`가 아니라 생성된 `enrollment` 쪽에서 다룬다

### 3.5 제약 조건 총괄

#### 3.5.1 ⚠️ 부분 유니크의 이식성 문제

"활성 신청만 유일" 제약은 논리적으로 **부분 유니크 인덱스**다.

```sql
-- PostgreSQL: 그대로 지원
CREATE UNIQUE INDEX uq_enrollment_active
  ON enrollment (klass_id, user_id)
  WHERE status <> 'CANCELLED';
```

**그러나 참고 저장소의 스택(H2, MySQL)은 부분 인덱스를 지원하지 않는다.** 따라서 이식 가능한 대체 설계를 정본으로 채택한다.

```sql
-- 정본: 생성 컬럼 + 일반 UNIQUE
-- active_user_key = 활성이면 user_id, CANCELLED면 NULL
-- UNIQUE 인덱스에서 NULL은 서로 충돌하지 않으므로
-- 취소된 행은 몇 개든 공존하고 활성 행만 1건으로 제한된다
ALTER TABLE enrollment
  ADD COLUMN active_user_key BIGINT
  GENERATED ALWAYS AS (CASE WHEN status <> 'CANCELLED' THEN user_id END) STORED;

CREATE UNIQUE INDEX uq_enrollment_active
  ON enrollment (klass_id, active_user_key);
```

동일 기법을 `waitlist.waiting_user_key`에 적용한다 (`WAITING`일 때만 `user_id`).

| 방식 | PostgreSQL | MySQL 8 | H2 2.x |
|------|:-:|:-:|:-:|
| 부분 유니크 인덱스 (`WHERE`) | ✅ | ❌ | ❌ |
| **생성 컬럼 + UNIQUE (정본)** | ✅ | ✅ | ✅ |

> 생성 컬럼을 쓸 수 없는 환경이라면 애플리케이션이 `status` 변경 시 이 키를 함께 갱신한다. 다만 그 순간 정합성이 앱 책임으로 내려오므로 생성 컬럼을 우선한다.

#### 3.5.2 제약 목록

| 테이블 | 종류 | 제약 |
|--------|------|------|
| `users` | UNIQUE | `username` |
| `user_roles` | PK / FK | `(user_id, role)` / `user_id → users(id)` **ON DELETE CASCADE ON UPDATE CASCADE** |
| `refresh_token` | UNIQUE / CHECK | `token_hash` / `expires_at > issued_at` |
| | CHECK | **`is_revoked = (revoked_at IS NOT NULL)`** (쌍방향, §3.2.3) |
| `revoked_access_token` | UNIQUE | `jti` |
| `klass` | FK | `creator_id → users(id)` RESTRICT |
| | CHECK | `capacity > 0` |
| | CHECK | **`enrollment_count BETWEEN 0 AND capacity`** |
| | CHECK | `price >= 0`, `ends_on >= starts_on` |
| | CHECK | `cancellation_period_days IS NULL OR >= 0` |
| `enrollment` | FK | `klass_id → klass(id)` RESTRICT, `user_id → users(id)` RESTRICT |
| | UNIQUE | **`(klass_id, active_user_key)`** |
| | CHECK | 상태별 타임스탬프 정합성 (§3.2.6) |
| `waitlist` | FK | `klass_id → klass(id)` RESTRICT, `user_id → users(id)` RESTRICT |
| | UNIQUE | **`(klass_id, position)`**, `(klass_id, waiting_user_key)` |
| | CHECK | `position > 0` |
| | CHECK | `status = 'PROMOTED'` → `promoted_at IS NOT NULL` |

> **FK 표기 주의**: 위 표의 `RESTRICT`는 `ON DELETE` 정책이며, 모든 FK는 `ON UPDATE CASCADE`를 함께 갖는다(§3.2.5~7, §3.7 참조).

### 3.6 인덱스 설계 — 조회 요건 대응

| 요건 | 쿼리 형태 | 인덱스 |
|------|-----------|--------|
| 강의 목록 (상태 필터) | `WHERE status = ? ORDER BY id DESC` | `idx_klass_status(status, id DESC)` |
| 강의 상세 (신청 인원 포함) | `WHERE id = ?` | PK. **`enrollment_count`가 컬럼이라 조인·집계 없음** |
| 내 신청 목록 + 페이지네이션 | `WHERE user_id = ? AND id < :cursor ORDER BY id DESC LIMIT ?` | `idx_enrollment_user(user_id, id DESC)` |
| 강의별 수강생 목록 (크리에이터) | `WHERE klass_id = ? AND status = ? AND id < :cursor ORDER BY id DESC` | `idx_enrollment_klass_status(klass_id, status, id DESC)` |
| 크리에이터의 내 강의 목록 | `WHERE creator_id = ? ORDER BY id DESC` | `idx_klass_creator(creator_id, id DESC)` |
| PENDING 만료 스캔 | `WHERE status = 'PENDING' AND expires_at <= now` | `idx_enrollment_expiry(expires_at)` |
| 다음 승격 대상 | `WHERE klass_id = ? AND status = 'WAITING' ORDER BY position LIMIT 1` | `idx_waitlist_next(klass_id, status, position)` |
| 블랙리스트 검증 (매 요청) | `WHERE jti = ?` | `uq_revoked_access_token_jti` |
| 블랙리스트 정리 | `WHERE expires_at <= now` | `idx_revoked_access_token_expires_at` |
| 탈취 시 사용자 전체 토큰 무효화 (§4.7 4번) | `WHERE user_id = ?` | `idx_refresh_token_user_id` |

> **커서 페이지네이션을 쓰는 이유**: OFFSET은 페이지가 깊어질수록 앞의 행을 모두 읽어 버려 응답 시간이 선형으로 나빠진다. PK가 단조 증가 `BIGINT`이므로 `id`를 커서로 쓰면 정렬 키가 안정적이고 인덱스만으로 시작 위치를 찾는다.

### 3.7 DDL (논리 → 물리 대응)

> ⚠️ **벤더 전제**: 아래 DDL은 **PostgreSQL 문법**을 기준으로 적었다. §1.1 목표 5(이식성)에 따라 논리 모델은 중립이지만 물리 표현은 벤더마다 다르므로, 다른 DB에서는 아래를 치환한다.
>
> | 구문 | PostgreSQL | MySQL 8 | H2 2.x |
> |------|-----------|---------|--------|
> | 자동 증가 PK | `GENERATED BY DEFAULT AS IDENTITY` | `BIGINT AUTO_INCREMENT` | `GENERATED BY DEFAULT AS IDENTITY` |
> | 생성 컬럼 | `GENERATED ALWAYS AS (...) STORED` | `GENERATED ALWAYS AS (...) STORED` | `GENERATED ALWAYS AS (...)` — `STORED` 미지원 가능, **구현 시 확인 필요** |
> | 부분 유니크 인덱스 | 지원 | 미지원 | 미지원 |
>
> **예약어 점검**: 컬럼명 중 벤더 예약어와 충돌 가능한 것은 `position`(SQL:2016 예약어 — MySQL·PostgreSQL 모두 비예약이나 함수명과 겹침), `role`(PostgreSQL 예약어 — 컬럼명으로는 허용), `source`, `status`, `password`, `description`(모두 비예약)이다. `users`는 `"user"` 예약어를 피해 복수형으로 두었다(§8.2 인용). **구현 시 대상 DB에서 `position`과 `role`을 큰따옴표 없이 쓸 수 있는지 확인한다.**

> 생성 컬럼이 §3.5.1의 부분 유니크 대체 설계의 핵심이므로, **대상 DB에서 생성 컬럼 문법을 가장 먼저 검증한다.** 지원되지 않으면 §3.5.1 각주의 애플리케이션 갱신 방식으로 후퇴한다.

```sql
-- ═══════════════ 인증 도메인 (인용) ═══════════════

CREATE TABLE users (
  id          BIGINT       GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  username    VARCHAR(50)  NOT NULL UNIQUE,
  password    VARCHAR(100) NOT NULL,
  is_enabled  BOOLEAN      NOT NULL DEFAULT TRUE,
  created_at  TIMESTAMP    NOT NULL
);

CREATE TABLE user_roles (
  user_id BIGINT      NOT NULL REFERENCES users(id)
                        ON DELETE CASCADE ON UPDATE CASCADE,
  role    VARCHAR(20) NOT NULL,
  PRIMARY KEY (user_id, role)
);

CREATE TABLE refresh_token (
  id          BIGINT      GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  user_id     BIGINT      NOT NULL,              -- 값 참조 (FK 없음)
  token_hash  VARCHAR(64) NOT NULL UNIQUE,       -- SHA-256 hex, 원문 미저장
  issued_at   TIMESTAMP   NOT NULL,
  expires_at  TIMESTAMP   NOT NULL,
  is_revoked  BOOLEAN     NOT NULL DEFAULT FALSE,
  revoked_at  TIMESTAMP,
  CONSTRAINT ck_refresh_token_period  CHECK (expires_at > issued_at),
  CONSTRAINT ck_refresh_token_revoked CHECK (is_revoked = (revoked_at IS NOT NULL))
);
CREATE INDEX idx_refresh_token_user_id ON refresh_token(user_id);

CREATE TABLE revoked_access_token (
  id          BIGINT      GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  jti         VARCHAR(36) NOT NULL,              -- 원 토큰의 jti
  user_id     BIGINT      NOT NULL,              -- 값 참조
  expires_at  TIMESTAMP   NOT NULL,              -- purge 기준
  revoked_at  TIMESTAMP   NOT NULL
);
-- jti 는 매 요청 조회되는 키다. named unique index 로 승격해 존재를 명시한다
CREATE UNIQUE INDEX uq_revoked_access_token_jti     ON revoked_access_token(jti);
CREATE INDEX idx_revoked_access_token_expires_at   ON revoked_access_token(expires_at);

-- ═══════════════ 수강 도메인 (신규) ═══════════════

CREATE TABLE klass (                            -- entity: Klass (이름 통일, @Table 불필요)
  id                        BIGINT        GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  creator_id                BIGINT        NOT NULL REFERENCES users(id)
                                              ON DELETE RESTRICT ON UPDATE CASCADE,
  title                     VARCHAR(200)  NOT NULL,
  description               TEXT          NOT NULL,
  price                     DECIMAL(12,2) NOT NULL,
  capacity                  INT           NOT NULL,
  enrollment_count           INT           NOT NULL DEFAULT 0,
  status                    VARCHAR(20)   NOT NULL DEFAULT 'DRAFT',
  starts_on                 DATE          NOT NULL,
  ends_on                   DATE          NOT NULL,
  cancellation_period_days  INT,
  created_at                TIMESTAMP     NOT NULL,
  updated_at                TIMESTAMP     NOT NULL,
  CONSTRAINT ck_klass_capacity  CHECK (capacity > 0),
  CONSTRAINT ck_klass_count     CHECK (enrollment_count >= 0
                                         AND enrollment_count <= capacity),
  CONSTRAINT ck_klass_price     CHECK (price >= 0),
  CONSTRAINT ck_klass_period    CHECK (ends_on >= starts_on),
  CONSTRAINT ck_klass_cancel    CHECK (cancellation_period_days IS NULL
                                         OR cancellation_period_days >= 0)
);
CREATE INDEX idx_klass_status  ON klass(status, id DESC);
CREATE INDEX idx_klass_creator ON klass(creator_id, id DESC);

CREATE TABLE enrollment (
  id           BIGINT      GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  klass_id     BIGINT      NOT NULL REFERENCES klass(id)
                             ON DELETE RESTRICT ON UPDATE CASCADE,
  user_id      BIGINT      NOT NULL REFERENCES users(id)
                             ON DELETE RESTRICT ON UPDATE CASCADE,
  status       VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  source       VARCHAR(20) NOT NULL DEFAULT 'DIRECT',
  created_at   TIMESTAMP   NOT NULL,
  expires_at   TIMESTAMP,                        -- PENDING 만료 예정
  confirmed_at TIMESTAMP,
  cancelled_at TIMESTAMP,
  -- 활성 신청만 유일하게 만드는 생성 컬럼 (§3.5.1)
  active_user_key BIGINT GENERATED ALWAYS AS
    (CASE WHEN status <> 'CANCELLED' THEN user_id END) STORED,
  CONSTRAINT ck_enrollment_pending   CHECK ((status =  'PENDING'   AND expires_at   IS NOT NULL)
                                         OR (status <> 'PENDING'   AND expires_at   IS NULL)),
  CONSTRAINT ck_enrollment_confirmed CHECK (status <> 'CONFIRMED'  OR confirmed_at  IS NOT NULL),
  CONSTRAINT ck_enrollment_cancelled CHECK (status <> 'CANCELLED'  OR cancelled_at  IS NOT NULL)
);
CREATE UNIQUE INDEX uq_enrollment_active       ON enrollment(klass_id, active_user_key);
CREATE INDEX idx_enrollment_user               ON enrollment(user_id, id DESC);
CREATE INDEX idx_enrollment_klass_status       ON enrollment(klass_id, status, id DESC);
CREATE INDEX idx_enrollment_expiry             ON enrollment(expires_at);

CREATE TABLE waitlist (
  id           BIGINT      GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  klass_id     BIGINT      NOT NULL REFERENCES klass(id)
                             ON DELETE RESTRICT ON UPDATE CASCADE,
  user_id      BIGINT      NOT NULL REFERENCES users(id)
                             ON DELETE RESTRICT ON UPDATE CASCADE,
  position     INT         NOT NULL,
  status       VARCHAR(20) NOT NULL DEFAULT 'WAITING',
  created_at   TIMESTAMP   NOT NULL,
  promoted_at  TIMESTAMP,
  waiting_user_key BIGINT GENERATED ALWAYS AS
    (CASE WHEN status = 'WAITING' THEN user_id END) STORED,
  CONSTRAINT ck_waitlist_position CHECK (position > 0),
  CONSTRAINT ck_waitlist_promoted CHECK (status <> 'PROMOTED' OR promoted_at IS NOT NULL)
);
CREATE UNIQUE INDEX uq_waitlist_position ON waitlist(klass_id, position);
CREATE UNIQUE INDEX uq_waitlist_waiting  ON waitlist(klass_id, waiting_user_key);
CREATE INDEX idx_waitlist_next           ON waitlist(klass_id, status, position);
```

---

## 4. 동시성 규약

### 4.1 락 획득 순서 (전역 규약)

```
klass → enrollment → waitlist
```

정원과 관련된 **모든** 트랜잭션이 `klass` 행 락을 **가장 먼저** 잡는다. 그 뒤 `enrollment`와 `waitlist`를 어떤 순서로 잡든 데드락이 생기지 않는다 — 모든 경합이 이미 `klass` 한 행에서 직렬화되기 때문이다. §4.4는 `enrollment`(2번) → `waitlist`(9-a) 순서지만, `waitlist`를 먼저 잡아도 무해하다.

**예외는 §4.3 결제 확정과 §4.9 대기 포기**다. 각각 `enrollment` / `waitlist` 단독 행 락만 잡고 그 뒤 아무것도 잡지 않으므로 순환 대기가 성립하지 않는다. 둘 다 `enrollment_count`를 건드리지 않는다는 공통점이 그 근거다.

### 4.2 수강 신청

```
[사전] JwtAuthenticationFilter가 Access 토큰을 검증한다
       ├ typ == ACCESS 확인          (토큰 타입 혼동 공격 차단)
       ├ jti ∉ revoked_access_token  (로그아웃된 토큰 차단)
       └ sub → 인증된 userId 확정

BEGIN TRANSACTION
  1. SELECT * FROM klass WHERE id = :klassId FOR UPDATE      -- 배타 락
  2. IF status <> 'OPEN'                          → REJECT  (모집 중 아님)
  3. IF EXISTS 활성 enrollment(klass_id, user_id)  → REJECT  (중복 신청)
  4. IF enrollment_count >= capacity                → REJECT  (정원 초과)
     └ 대기열은 자동 등록하지 않는다. 사용자가 원하면 §4.5를 별도 호출한다
  5. INSERT enrollment (
       klass_id, user_id, status='PENDING', source='DIRECT',
       created_at=now, expires_at=now + pending-expiry.direct
     )
     └ 3번을 통과했어도 uq_enrollment_active 가 최종 방어한다
  6. UPDATE klass SET enrollment_count = enrollment_count + 1
     └ 초과는 ck_klass_count 가 거부 (앱 버그 최종 방어선)
COMMIT
```

> 4번의 검사와 6번의 갱신 사이에 다른 트랜잭션이 끼어들 수 없는 이유는 1번의 배타 락이다. 이것이 "동시에 여러 사람이 마지막 자리에 신청"을 해결하는 지점이다.
> **3번을 두는 이유**: 제약 위반 예외를 잡아 409를 만드는 것보다 명시적 검사가 읽기 쉽고, §1.2의 "앱 → DB 제약 순으로 방어를 겹친다"에 부합한다. §4.5 3번과도 대칭이 맞는다.
> **4번이 대기열로 자동 분기하지 않는 이유**: 요청하지 않은 사용자를 대기열에 넣는 것은 월권이고, §7이 "대기열 등록"을 별도 작업으로 등재하고 있다. 무엇보다 자동 분기하면 **동시 100건 중 99건이 `WAITING` 행이 되어** Context Anchor의 SUCCESS 기준("99건 거부")과 시나리오 1이 성립하지 않는다.

### 4.3 결제 확정

```
BEGIN TRANSACTION
  1. SELECT * FROM enrollment WHERE id = :id FOR UPDATE
  2. IF enrollment.user_id <> sub  → REJECT 403 (타인의 신청)
  3. IF status <> 'PENDING'        → REJECT (이미 확정·취소·만료됨)
  4. IF expires_at <= now          → REJECT (만료됨 — 배치 실행 전이어도)
  5. UPDATE enrollment SET status='CONFIRMED', confirmed_at=now, expires_at=NULL
COMMIT
```

> `klass` 락이 불필요한 이유: PENDING이 이미 좌석을 점유하고 있으므로 `enrollment_count`가 변하지 않는다.
> **3번의 상태 재확인이 필수다.** 없으면 만료 배치가 방금 취소한 신청을 결제 확정으로 되살린다.
> **4번이 필요한 이유**: 만료 시각이 지났지만 §4.6 배치가 아직 처리하지 않은 PENDING이 존재한다. 이 검사가 없으면 **배치의 실행 시점이 사용자 경험을 좌우한다** — 같은 상황에서 어떤 사용자는 결제에 성공하고 어떤 사용자는 실패한다. 4번은 좌석을 반납하지 않으므로(그것은 배치의 일) `klass` 락 없이 안전하다.

### 4.4 좌석 반납 → 대기열 승격 (취소 / PENDING 만료 공통)

```
BEGIN TRANSACTION
  0. klassId := (무락 조회) SELECT klass_id FROM enrollment WHERE id = :enrollmentId
       └ §4.1 규약상 klass 를 먼저 락해야 하므로 락 전에 소속 강의를 알아낸다
  1. SELECT * FROM klass    WHERE id = :klassId      FOR UPDATE
  2. SELECT * FROM enrollment WHERE id = :enrollmentId FOR UPDATE
  3. IF enrollment.klass_id <> :klassId → ABORT
       └ 0번 이후 강의가 바뀔 수는 없으나, klassId 를 외부 입력으로 받는
         호출자가 있으면 엉뚱한 강의의 카운터가 깎인다 (§1.2 이중 방어)
  4. IF enrollment.status NOT IN ('PENDING','CONFIRMED') → ABORT (이미 처리됨)
  5. IF 사용자 취소 요청:                       -- 만료 배치는 이 블록을 건너뛴다
       a. IF enrollment.user_id <> sub → REJECT 403 (타인의 신청, §7)
       b. IF enrollment.status = 'CONFIRMED':
            IF now > enrollment.confirmed_at
                     + COALESCE(klass.cancellation_period_days,
                                app.enrollment.default-cancellation-period-days)
               → REJECT (취소 가능 기간 초과)
  6. UPDATE enrollment SET status='CANCELLED', cancelled_at=now, expires_at=NULL
  7. UPDATE klass SET enrollment_count = enrollment_count - 1

  -- 대기열 승격 (같은 락 아래에서)
  8. IF klass.status <> 'OPEN' → COMMIT           -- 승격하지 않는다 (§2.1)
  9. lastPos := 0
     LOOP
       a. SELECT * FROM waitlist
            WHERE klass_id = :klassId AND status = 'WAITING'
              AND position > lastPos
            ORDER BY position ASC LIMIT 1 FOR UPDATE
       b. IF 행 없음 → EXIT LOOP                   -- 좌석은 빈 채로 남는다
       c. lastPos := 해당 행의 position
       d. IF NOT (users.is_enabled AND 활성 enrollment(klass_id, user_id) 부재):
            UPDATE waitlist SET status='CANCELLED'  -- 부적격, 건너뛴다
            CONTINUE LOOP
       e. UPDATE waitlist SET status='PROMOTED', promoted_at=now
       f. INSERT enrollment (
            klass_id, user_id, status='PENDING', source='WAITLIST',
            created_at=now, expires_at=now + pending-expiry.waitlist
          )
       g. UPDATE klass SET enrollment_count = enrollment_count + 1
       h. EXIT LOOP                                -- 1건만 승격한다
COMMIT
```

**핵심 성질 3가지**

1. **승격 시 순변화 0** — 7번의 감소와 9-g의 증가가 상쇄된다. 반납된 좌석이 일반 신청자에게 노출되는 틈 없이 대기자에게 이전된다. 승격 대상이 없거나(9-b) 강의가 `OPEN`이 아니면(8번) 순변화는 `-1`이고 좌석은 빈 채로 남는다.
2. **한 트랜잭션 안에서 끝낸다** — 락을 놓고 승격하면 그 틈에 일반 신청자가 좌석을 채간다.
3. **승격은 `OPEN`에서만 일어난다** (§2.1) — **8번**이 `klass.status <> 'OPEN'`이면 승격 없이 커밋한다. `CLOSED`에서 반납된 좌석은 빈 채로 남으며, 이는 명단 확정 시점에 상한을 두기 위한 의도된 선택이다.

### 4.5 대기열 등록 (사용자 명시 요청)

**§4.2의 하위 분기가 아니라 독립 트랜잭션이다.** 정원 초과로 §4.2가 거부한 뒤, 사용자가 대기를 원할 때 별도로 호출한다.

```
BEGIN TRANSACTION
  1. SELECT * FROM klass WHERE id = :klassId FOR UPDATE      -- §4.1 규약
  2. IF klass.status <> 'OPEN' → REJECT (모집 중 아님)
  3. IF EXISTS 활성 enrollment(klass_id, user_id) → REJECT (이미 신청함)
  4. IF EXISTS waitlist(klass_id, user_id, status='WAITING') → REJECT (중복 대기)
  5. IF enrollment_count < capacity → REJECT (자리가 있으니 §4.2로 신청하라)
  6. next := COALESCE(MAX(position), 0) + 1  FROM waitlist WHERE klass_id = :klassId
  7. INSERT waitlist (klass_id, user_id, position = next,
                      status='WAITING', created_at=now)
       └ 순번 경합은 uq_waitlist_position 이 거부
COMMIT
```

> ⚠️ **3번이 필수인 이유**: `uq_enrollment_active`는 `enrollment` INSERT에만 작동하고 `uq_waitlist_waiting`은 중복 *대기*만 막는다. 3번이 없으면 **이미 `CONFIRMED`인 사용자가 같은 강의 대기열에 등록**되어 순번을 차지하고, 승격 시 §4.4 9-d에서 부적격으로 걸러진다.
> **5번이 필요한 이유**: 자리가 있는데 대기열에 넣으면 §4.4의 좌석 반납이 일어날 때까지 승격되지 않아 사용자가 영구히 기다린다. 자리가 있으면 신청으로 안내한다.

> `MAX(position)+1`이 안전한 이유는 `klass` 락 하위에서만 실행되기 때문이다. 락 없이 실행하면 순번이 충돌하고, UNIQUE 제약이 그것을 거부한다(최종 방어선).
> 취소된 대기 행의 순번은 **재사용하지 않고 gap으로 남긴다.** 순번 재배열은 여러 행을 갱신해 락 범위를 넓히고, 순번의 절대값이 사용자에게 의미를 갖지 않는다.

### 4.6 PENDING 만료 배치

```
주기: app.enrollment.pending-expiry-scan-interval (예: PT1M)

FOR EACH id IN (SELECT id FROM enrollment
                 WHERE status = 'PENDING' AND expires_at <= now):
    §4.4 의 좌석 반납 트랜잭션을 실행한다 (건별 독립 트랜잭션)
```

> **건별 트랜잭션인 이유**: 한 트랜잭션에 묶으면 여러 강의의 락을 동시에 보유해 데드락 위험이 커지고, 한 건의 실패가 전체를 롤백한다.
> §4.4의 4번 상태 재확인이 **사용자 결제와의 경합**을 막는다.

### 4.7 Refresh 토큰 회전 (인용)

```
BEGIN TRANSACTION
  1. tokenHash := SHA-256(원문)
  2. SELECT * FROM refresh_token WHERE token_hash = :tokenHash
       └ 조회된 행의 user_id 를 아래 :userId 로 쓴다
  3. IF 없음        → REJECT
  4. IF is_revoked  → 탈취 신호! 별도 트랜잭션(REQUIRES_NEW)으로
                      UPDATE refresh_token
                         SET is_revoked=TRUE, revoked_at=now   -- 둘 다 필수
                       WHERE user_id = :userId AND is_revoked = FALSE
                      후 REJECT
                      └ revoked_at 을 빠뜨리면 ck_refresh_token_revoked 가 거부한다
  5. IF expires_at < now → REJECT
  6. UPDATE refresh_token SET is_revoked=TRUE, revoked_at=now
      WHERE token_hash = :tokenHash
  7. 새 Access/Refresh 쌍 발급, 새 refresh_token 행 INSERT
COMMIT
```

> **4번의 무효화를 같은 트랜잭션에서 하면 안 된다.** 예외를 재전파하는 순간 무효화까지 롤백된다.

### 4.8 강의 상태 전이 · 정원 수정

§3.4의 조건부 전이 두 개(`OPEN → DRAFT`, `CLOSED → OPEN`)와 `capacity` 수정은 모두 **`enrollment_count`를 읽고 상태를 쓰는 read-modify-write**다. §4.1의 규약에 따라 `klass` 락 아래에서 수행한다.

```
BEGIN TRANSACTION
  1. SELECT * FROM klass WHERE id = :klassId FOR UPDATE
  2. IF NOT (roles ∋ ROLE_CREATOR)  → REJECT 403   -- §7: 권한
     IF klass.creator_id <> sub     → REJECT 403   -- §7: 소유권
  3. 화이트리스트 검사 — §3.4 전이표에서 ✅ 또는 ⚠️ 가 아닌 조합은 모두 REJECT
       허용 목록:  DRAFT → OPEN  |  DRAFT → CLOSED  |  OPEN → CLOSED
       조건부:     OPEN → DRAFT   : IF enrollment_count > 0 → REJECT
                                    (신청자가 있으면 금지 — 이미 신청한
                                     사용자의 신청이 무효화된다)
                   CLOSED → OPEN  : 초기 구현에서는 무조건 REJECT (아래 주의)
       그 외 (CLOSED → DRAFT, 자기 전이 등) → REJECT
  4. UPDATE klass SET status = :next
  5. IF :next = 'CLOSED':                      -- 잔여 대기자 정리
       UPDATE waitlist SET status = 'CANCELLED'
        WHERE klass_id = :klassId AND status = 'WAITING'
       └ CLOSED 에서는 승격이 중단되고 CLOSED → OPEN 도 봉쇄되므로,
         남겨두면 영구히 승격되지 않는 유령 행이 된다
COMMIT

-- capacity 수정 (상태 전이와 별개 트랜잭션)
BEGIN TRANSACTION
  1. SELECT * FROM klass WHERE id = :klassId FOR UPDATE
  2. 상태 전이 트랜잭션의 2번과 동일한 권한·소유권 검사
  3. IF new_capacity < enrollment_count → REJECT
       (ck_klass_count 가 최종 방어하지만 앱이 먼저 사용자에게 설명한다)
  4. UPDATE klass SET capacity = :new_capacity
  5. IF new_capacity > 기존 capacity AND klass.status = 'OPEN':
       -- 늘어난 좌석만큼 대기자를 승격한다
       REPEAT (new_capacity - enrollment_count) 회:
           §4.4 의 8~9번 승격 루프를 실행한다 (같은 klass 락 아래에서)
           승격 대상이 없으면 즉시 중단
COMMIT
```

> ⚠️ **3번을 화이트리스트로 둔 이유**: 전이별 검사를 열거 방식으로 두면 **열거에 없는 조합의 기본 동작이 규정되지 않아** 4번의 `UPDATE`가 금지 전이(`CLOSED → DRAFT`)를 그대로 통과시킨다. 허용 목록을 §3.4 전이표에 고정하고 나머지를 거부하면 전이가 추가될 때 누락에 강하다.

> ⚠️ **정원 증가 시 승격이 필요한 이유**(capacity 5번): 승격은 §4.4의 좌석 반납 경로에서만 트리거된다. 정원 10 만석 + 대기자 3명 상태에서 정원을 13으로 올리면 빈 3석이 생기는데, 승격 트리거가 없으면 **신규 직접 신청자가 대기자를 앞지른다.** 아래 `CLOSED → OPEN`과 동일한 구멍이므로 같은 방식으로 막는다.

> ⚠️ **`CLOSED → OPEN`은 초기 구현에서 차단한다** (§3.4의 결론). 허용하려면 **남은 `WAITING` 처리 규약을 먼저 정해야 한다** — `CLOSED`에서 취소가 발생하면 §4.4 7번이 승격을 중단하므로 `WAITING` 행이 남은 채 `enrollment_count`가 줄어든다. 이 상태에서 `OPEN`으로 되돌리면 **정원에 여유가 있고 대기자가 있는 강의**가 만들어지는데, 승격은 §4.4의 좌석 반납 경로에서만 트리거되므로 **신규 신청자가 대기자를 앞지른다.** 같은 경로로 `OPEN → DRAFT`까지 가면 대기자가 `DRAFT` 강의에 유령 상태로 남는다. 전이를 봉쇄하면 이 구멍 전체가 닫힌다.

### 4.9 대기 포기 (WAITING → CANCELLED)

```
BEGIN TRANSACTION
  1. SELECT * FROM waitlist WHERE id = :waitlistId FOR UPDATE
  2. IF waitlist.user_id <> sub  → REJECT 403
  3. IF status <> 'WAITING'      → ABORT (이미 승격되었거나 포기됨)
  4. UPDATE waitlist SET status = 'CANCELLED'
COMMIT
```

> 3번의 상태 재확인이 §4.4의 승격 트랜잭션과의 경합을 막는다. 승격이 먼저 커밋되면 이 트랜잭션은 `PROMOTED`를 보고 중단하며, 사용자는 "이미 자리가 배정되었습니다"를 안내받아야 한다.
> **`klass` 락을 잡지 않는다** — 이 트랜잭션은 `enrollment_count`를 건드리지 않고 `waitlist` 행 락만 잡은 뒤 아무것도 더 잡지 않으므로, §4.3과 동일한 구조의 예외다(순환 대기 불성립). 인기 강의에서 대기 포기가 신청 트랜잭션과 직렬화되는 비용을 피한다.

---

## 5. 정합성 검증 및 복구

### 5.1 검증 쿼리

```sql
-- enrollment_count 가 실제 좌석 점유 행 수와 일치하는가
SELECT k.id, k.title, k.capacity, k.enrollment_count,
       COALESCE(e.actual, 0) AS actual,
       k.enrollment_count - COALESCE(e.actual, 0) AS drift
  FROM klass k
  LEFT JOIN (
       SELECT klass_id, COUNT(*) AS actual
         FROM enrollment
        WHERE status IN ('PENDING','CONFIRMED')
        GROUP BY klass_id
  ) e ON e.klass_id = k.id
 WHERE k.enrollment_count <> COALESCE(e.actual, 0);
-- 결과가 비어 있어야 정상
```

```sql
-- 대기열 순번 중복 (UNIQUE가 있으므로 항상 비어야 함 — 제약 검증용)
SELECT klass_id, position, COUNT(*)
  FROM waitlist GROUP BY klass_id, position HAVING COUNT(*) > 1;

-- 고아 행 — 인증 2개 테이블만 대상 (나머지는 FK가 물리적으로 차단, §3.1.1)
SELECT 'refresh_token' AS t, r.id FROM refresh_token r
  LEFT JOIN users u ON u.id = r.user_id WHERE u.id IS NULL
UNION ALL
SELECT 'revoked_access_token', v.id FROM revoked_access_token v
  LEFT JOIN users u ON u.id = v.user_id WHERE u.id IS NULL;
```

### 5.2 복구 절차 (reconcile)

drift가 발견되면 **`enrollment`를 진실로 간주하고 카운터를 재계산한다.**

```sql
BEGIN;
  SELECT id FROM klass WHERE id = :klassId FOR UPDATE;
  UPDATE klass AS k
     SET enrollment_count = (
           SELECT COUNT(*) FROM enrollment e
            WHERE e.klass_id = k.id
              AND e.status IN ('PENDING','CONFIRMED')
         )
   WHERE k.id = :klassId;
COMMIT;
```

> `enrollment`가 진실인 이유: 그것이 사용자에게 보이는 실제 신청 기록이고, `enrollment_count`는 조회 성능을 위한 파생 값이다. 다만 재계산이 `capacity`를 초과하면 CHECK가 거부하며, 이는 이미 오버부킹이 발생했다는 뜻이므로 수동 판단이 필요하다.

### 5.3 정리(purge) 배치

| 대상 | 기준 | 주기 프로퍼티 |
|------|------|---------------|
| `revoked_access_token` | `expires_at <= now` | `jwt.revoked-token-cleanup-interval` (예: PT10M) |
| `refresh_token` | `expires_at <= now` | **미결 ⑧** — 주기 미정. 정리가 없으면 무한 증가한다 |

> `revoked_access_token` 정리가 없으면 테이블이 로그아웃 횟수만큼 무한히 자라고, 매 요청 조회가 함께 느려진다.

---

## 6. 요건 추적표

> Plan의 FR 전체(FR-01~18, FR-A1~A7)와 기계적으로 대조할 수 있도록 ID 열을 둔다. **누락된 FR이 없어야 한다.**

| FR | 요건 | 충족 수단 | 위치 |
|----|------|-----------|------|
| | **필수 1. 강의 관리** | | |
| FR-01 | 강의 등록 (제목/설명/가격/정원/수강기간) | `klass` 컬럼 | §3.2.5 |
| FR-02 | 강의 상태 DRAFT→OPEN→CLOSED | `KlassStatus` + 전이표 + §4.8 화이트리스트 | §3.3, §3.4, §4.8 |
| FR-03 | 강의 목록 조회 (상태 필터) | `idx_klass_status(status, id DESC)` | §3.6 |
| FR-04 | 강의 상세 조회 (현재 신청 인원 포함) | `klass.enrollment_count` — 집계 쿼리 없음 | §3.2.5 |
| | **필수 2. 수강 신청 관리** | | |
| FR-05 | `Enrollment` 엔티티 (관계·상태·신청/확정/취소 시각) | `enrollment` 전 컬럼 | §3.2.6 |
| FR-06 | 신청 상태 PENDING→CONFIRMED→CANCELLED | `EnrollmentStatus` + 전이표 | §3.3, §3.4 |
| FR-05, 06 | 수강 신청 | `enrollment` INSERT + 신청 트랜잭션 | §4.2 |
| FR-05, 06 | 결제 확정 처리 | `status → CONFIRMED`, `confirmed_at`. 만료 후 거부 | §4.3 |
| FR-05, 06 | 수강 취소 | `status → CANCELLED`, `cancelled_at`, 카운터 감소 | §4.4 |
| **FR-07** | **동일 사용자의 활성 중복 신청 차단** | `uq_enrollment_active` + §4.2 3번 명시 검사 (이중 방어) | §3.5.1, §4.2 |
| FR-10 | 내 수강 신청 목록 조회 | `idx_enrollment_user(user_id, id DESC)` + `user_id == JWT sub` | §3.6, §7 |
| | **필수 3. 정원 관리 규칙** | | |
| FR-08 | 정원 초과 신청 거부 | 락 하위 검사 + `ck_klass_count` 이중 방어 | §4.2, §3.5.2 |
| FR-08 | 마지막 자리 동시 신청 | `SELECT ... FROM klass ... FOR UPDATE` | §4.1, §4.2 |
| FR-09 | 카운터 정합성 보증 | `CHECK (enrollment_count BETWEEN 0 AND capacity)` + 검증·복구 절차 | §3.5.2, §5 |
| | **선택 구현** | | |
| FR-11 | FR-11 | 취소 가능 기간 제한 | `enrollment.confirmed_at` + `klass.cancellation_period_days` | §4.4 5-b |
| FR-12 | 대기열 엔티티 · 등록 | `waitlist` + `WaitlistStatus` + 명시 요청 트랜잭션 | §3.2.7, §3.4, §4.5 |
| FR-18 | 대기열 승격 규약 | 좌석 반납 트랜잭션 7~8번 | §4.4 |
| FR-13 | 강의별 수강생 목록 (크리에이터 전용) | `klass.creator_id` + `ROLE_CREATOR` + 전용 인덱스 | §3.6, §7 |
| FR-14 | 신청 내역 페이지네이션 | 커서 방식 (`id` 기준) + 복합 인덱스 | §3.6 |
| **FR-15** | **감사 로그 테이블 필요성 판단** | **미채택 (YAGNI).** 판단 근거와 남는 한계(취소 원인 미구분)를 기록 | §1.3, §2 ⑦ |
| | **인증 (추가 통합)** | | |
| FR-A1 | 사용자 식별 | `users` + JWT `sub` | §3.2.1 |
| FR-A2 | 다중 권한 보유 | `user_roles` 복합 PK — 수강생·크리에이터 겸용 가능 | §3.2.2 |
| FR-A3 | `ROLE_CREATOR` 추가 | `Role` enum 확장 | §3.3 |
| FR-A4 | `refresh_token` 편입 (회전·재사용 감지) | 해시 저장 + `ck_refresh_token_revoked` + 회전 규약 | §3.2.3, §4.7 |
| FR-A5 | `revoked_access_token` 편입 (로그아웃 즉시 차단) | `jti` 블랙리스트 + purge 배치 | §3.2.4, §5.3 |
| FR-A6 | 인증 테이블 값 참조 원칙 및 근거 | 고아 행 피해가 자기 완결적임을 근거로 유지 | §3.1.1 |
| FR-A7 | 크리에이터 권한 검증 경로 | `roles ∋ ROLE_CREATOR` AND `creator_id == sub` | §7, §4.8 2번 |
| | **신규 도출 (Plan 이후)** | | |
| FR-16 | PENDING 좌석 점유 만료 정책 | `enrollment.expires_at` + 만료 배치 | §4.6 |
| FR-17 | `EnrollmentSource` + 출처별 만료 기한 | `source` ENUM (DIRECT 30분 / WAITLIST 10분) | §3.3, §4.2, §4.4 |

---

## 7. 권한 검증 지점

| 작업 | 검증 조건 |
|------|-----------|
| 강의 목록 / 상세 조회 | **선택적 인증.** 토큰 없이도 조회 가능하되 `DRAFT`는 제외. 토큰이 있으면 `creator_id == sub`인 `DRAFT`도 포함 |
| 강의 등록 | `roles ∋ ROLE_CREATOR` |
| 강의 상태 변경 / 수정 | `roles ∋ ROLE_CREATOR` **AND** `klass.creator_id == sub` |
| 강의별 수강생 목록 | `roles ∋ ROLE_CREATOR` **AND** `klass.creator_id == sub` |
| 수강 신청 | 유효한 Access 토큰. `enrollment.user_id := sub` |
| **결제 확정** | 유효한 Access 토큰 **AND `enrollment.user_id == sub`** — 타인의 PENDING을 확정할 수 없다 (§4.3 2번) |
| 수강 취소 | 유효한 Access 토큰 **AND `enrollment.user_id == sub`** |
| 내 신청 목록 | `enrollment.user_id == sub` (타인 조회 불가) |
| 대기열 등록 | 유효한 Access 토큰. `waitlist.user_id := sub` (대입) |
| 대기열 포기 | 유효한 Access 토큰 **AND `waitlist.user_id == sub`** (검사, §4.9 2번) |

> **권한만으로는 부족하다.** `ROLE_CREATOR`를 가진 사용자가 남의 강의 수강생 목록을 볼 수 있으면 안 되므로, 소유권 검사(`creator_id == sub`)가 항상 동반된다.

---

## 8. 검증 시나리오

설계가 의도대로 작동하는지 확인할 시나리오다. 테스트 코드는 구현 단계에서 작성한다.

| # | 시나리오 | 기대 결과 |
|:-:|----------|-----------|
| 1 | 잔여 1석에 100건 동시 신청 | 정확히 1건 성공, 99건 정원 초과 거부. `enrollment_count == capacity` |
| 2 | 정원 초과 상태에서 신청 | **거부** (§4.2 4번). `enrollment_count` 불변. 대기열 등록은 사용자가 §4.5를 별도 호출 |
| 3 | 동일 사용자가 같은 강의에 2회 신청 | 2회차 거부 — §4.2 3번의 명시적 검사, 통과해도 `uq_enrollment_active`가 최종 방어 |
| 4 | 취소 후 같은 강의에 재신청 | 성공 (CANCELLED는 유니크 대상에서 제외) |
| 5 | CONFIRMED 1건 취소, 대기자 3명 존재 | 1순위만 승격. `enrollment_count` 순변화 0. 2·3순위는 `WAITING` 유지 |
| 6 | 취소 2건 동시 발생, 대기자 1명 | 승격은 1건만. 같은 대기자가 두 번 승격되지 않음 |
| 7 | PENDING 만료 시각 도달 | 자동 `CANCELLED`, 카운터 감소, 대기자 승격 |
| 8 | 사용자 결제와 만료 배치 동시 실행 | 한쪽만 성공. **결제 성공 시 만료 처리가 ABORT** (§4.4 4번) |
| 9 | 취소 가능 기간 초과 후 취소 시도 | 거부. `enrollment_count` 불변 |
| 10 | `CLOSED` 전환 후 기존 PENDING 결제 (만료 전) | 성공 (§2.1). 만료 후라면 거부 (§4.3 4번) |
| 11 | `CLOSED` 상태에서 신규 신청 | 거부 |
| 12 | `CLOSED` 상태에서 취소 발생, 대기자 존재 | **승격 없음.** `enrollment_count` 감소만 발생하고 좌석은 빈 채로 남는다 (§2.1) |
| 13 | `CLOSED` 전환 후 남은 PENDING이 모두 확정 또는 만료 | `CLOSED + 30분` 이후 명단에 **신규 추가가 발생하지 않는다.** 취소로 인한 감소는 취소 가능 기간 내에 계속 가능하다 (§4.4는 `CLOSED`에서도 취소를 막지 않는다) |
| 14 | `capacity`를 `enrollment_count`보다 작게 수정 | 앱 검사(§4.8 capacity 3번)에서 거부. 앱을 우회해도 `ck_klass_count`가 최종 거부 |
| 15 | 로그아웃 후 남은 Access 토큰으로 신청 | 401. 블랙리스트 차단 |
| 16 | 폐기된 Refresh 토큰 재사용 | 거부 + 해당 사용자 전체 토큰 무효화 |
| 17 | 다른 크리에이터의 강의 수강생 목록 조회 | 403 (소유권 검사) |
| 18 | `OPEN → DRAFT`, 신청자 0명 | 성공 |
| 19 | `OPEN → DRAFT`, 신청자 존재 | 거부 (§4.8 3번 조건부) |
| 20 | `CANCELLED` 신청에 대해 결제 확정 / 취소 재시도 | 각각 거부 (§4.3 3번 · §4.4 4번) |
| 21 | 대기자 2명, 1순위 `is_enabled = false`, 2순위 적격 | 1순위 `CANCELLED` 처리 후 **2순위 승격** (§4.4 9-d). 순변화 0. 적격 대기자가 없으면 9-b로 `EXIT`하며 순변화 `-1` |
| 22 | `DRAFT` 강의에 신청 시도 | 거부 (§4.2 2번) |
| 23 | 이미 `CONFIRMED`인 사용자가 같은 강의 대기열 등록 시도 | 거부 (§4.5 3번) |
| 24 | `CLOSED → DRAFT` 전이 시도 | 거부 (§4.8 3번 화이트리스트) |
| 25 | `CLOSED → OPEN` 전이 시도 | 거부 (초기 구현 차단, §4.8 주의) |
| 26 | `DRAFT → OPEN` 전이 | 성공 |
| 27 | `CONFIRMED` 신청을 `PENDING`으로 되돌리기 시도 | 거부 (§3.4 금지 전이) |
| 28 | `PROMOTED` 대기 행 포기 시도 | 거부 (§4.9 3번) |
| 29 | 만료 시각이 지난 PENDING을 배치 실행 전에 결제 | 거부 (§4.3 4번) |
| 30 | `ROLE_CREATOR`가 회수된 사용자가 자기 강의 상태 변경 | 거부 (§4.8 2번 권한 검사) |
| 31 | 탈취 감지 일괄 무효화 시 `revoked_at` 미설정 | `ck_refresh_token_revoked` 위반으로 전량 실패 (§4.7 4번) |
| 32 | **타인의 신청 취소 시도** | **403** (§4.4 5-a) |
| 33 | 타인의 PENDING 결제 확정 시도 | 403 (§4.3 2번) |
| 34 | `PENDING` 신청을 사용자가 직접 취소 | 성공. 카운터 감소 + 대기자 승격 |
| 35 | `WAITING` 대기 정상 포기 | 성공 (§4.9). 순번은 gap으로 남는다 |
| 36 | 대기 포기 후 같은 강의에 재대기 | 성공 (`waiting_user_key`가 NULL이 되어 유니크 충돌 없음) |
| 37 | `DRAFT → CLOSED` 전이 | 성공 (신청자 없음) |
| 38 | `OPEN → CLOSED` 시 대기자 3명 존재 | 3명 모두 `CANCELLED` (§4.8 5번). 유령 `WAITING` 행이 남지 않음 |
| 39 | 정원 10 만석 + 대기자 3명 → 정원 13으로 증가 | 대기자 3명 모두 승격 (§4.8 capacity 5번). 신규 신청자가 앞지르지 못함 |
| 40 | 대기열 등록 시 자리가 있는 경우 | 거부 (§4.5 5번) — 신청으로 안내 |
| 41 | 정합성 검증 쿼리 (§5.1) — **마지막에 실행** | 시나리오 1~40을 모두 수행한 뒤에도 결과가 비어 있음 |

---

## 9. 인용 divergence 목록

참고 저장소(`Chals85/sample-jwt-authentication`) 대비 변경 사항 전체다.

| 대상 | 원본 | 본 설계 | 이유 |
|------|------|---------|------|
| `users.enabled` | `enabled` | **`is_enabled`** | boolean 접두어 컨벤션 |
| `refresh_token.revoked` | `revoked` | **`is_revoked`** | boolean 접두어 컨벤션 |
| `Role` enum | `ROLE_USER`, `ROLE_ADMIN` | **+ `ROLE_CREATOR`** | 크리에이터 권한 필요 |
| 시각 타입 | 전부 `LocalDateTime` | **사업 일자만 `LocalDate`** | 수강 기간의 시간대 모호성 제거 (§2 ③) |
| FK 정책 | 애그리거트 간 FK 없음 (DDL) | **인증 2개 테이블만 값 참조 유지. 수강 도메인 3개는 `users`에 FK 부착** | 원본의 근거는 ORM 매핑(`@ManyToOne` 회피)에 관한 것이며 DDL FK는 별개 결정이다. 영속적 사업 실체는 고아가 되면 복구 불가한 피해가 남고, 물리 삭제가 없는 설계라 FK 비용이 0이다 (§3.1.1) |
| — | — | `klass`, `enrollment`, `waitlist` 신규 | 수강 도메인 |

### 9.1 Plan 대비 정정

| 항목 | Plan v0.5 | Design | 사유 |
|------|-----------|--------|------|
| User 참조 FK | "사용자 결정: `creator_id`, `user_id`는 값 참조(FK 없음)" (§7.2) | **수강 도메인 3개 테이블에 FK 부착** | Plan 이후 재검토. 원본의 no-FK 근거가 ORM 매핑에 관한 것임을 확인 (§3.1.1). **Plan §7.2·§5 리스크는 v0.6, §7.3 다이어그램은 v0.7에서 갱신 완료** |
| CLOSED 시 승격 | "승격 허용 여부 미결" (§9 미결 ②) | **중단** | §2.1 |
| 강의 테이블명 | `classes` → `klass` (Plan v0.5에서 함께 갱신됨) | `klass` | 일치 |

**원본에서 무손실로 승계한 것**: 토큰 해시 저장, 회전·재사용 감지, `jti` 블랙리스트, `expires_at`의 purge 기준 역할, `BIGINT IDENTITY` PK, 주입된 `Clock`, `@Enumerated(STRING)`.

---

## 10. 다음 단계

1. [ ] ERD 리뷰 및 승인
2. [ ] 미결 사항 §2의 확정 6건 확인 (특히 ③ 시각 타입) **및 열어둔 미결 ⑦(`cancel_reason`)·⑧(`refresh_token` 정리 주기)을 그대로 두고 진입할지 판단**
3. [ ] 구현 진입 시 별도 판단: 코드 구조(레이어 배치), API 명세, 회원가입 API 포함 여부

---

## Version History

| 버전 | 날짜 | 변경 내용 | 작성자 |
|------|------|-----------|--------|
| 1.12 | 2026-09-02 | **스키마 무변경 — 근거 정리와 규칙 등재 2건.** ① §3.2.5 의 v1.11 주의 박스에서 `description` NOT NULL 의 근거 한 줄(**"부분 수정에서 `null` 의 중의성이 사라진다"**)을 걷어냈다 — klass-management **D-25** 가 부분 수정을 폐기하고 전체 필수 수신으로 바꿔 **그 논거가 거짓이 됐다**(전체 교체에는 애초에 중의성이 없다). D-18 자체는 원 요구사항 근거로 유효하므로 결론은 그대로다. ② `cancellation_period_days` 의 **`DRAFT` 전용 수정 규칙**을 §3.2.5 에 등재 (klass-management **D-26**). 컬럼표·DDL·mermaid 는 건드리지 않았다 — 좁혀진 것은 스키마가 아니라 수정 시점이고, 상태에 따라 갈리는 규칙이라 CHECK 로 표현할 수 없어 애플리케이션이 판정한다 | developer2@lulumedic.com |
| 1.11 | 2026-09-02 | **`klass` 컬럼 2건 변경** (klass-management 사이클). ① `updated_at` 신규 — 수정 API가 생기면서 최종 수정 시각이 필요해졌다. NOT NULL이며 생성 시 `created_at`과 같은 값이 들어간다("수정된 적 없음"을 별도 NULL로 표현하지 않는다). ② `description` NULL 허용 → **NOT NULL** — 원 요구사항이 등록 항목으로 "내용"을 나열했고, 필수로 두면 부분 수정(PATCH)에서 `null`의 중의성이 사라진다 (klass-management D-18). 반영 지점 3곳: §3.1 mermaid · §3.2.5 컬럼표 · §3.7 DDL | developer2@lulumedic.com |
| 1.10 | 2026-09-01 | `revoked_access_token.token_id` → **`jti`** (컬럼·제약·인덱스·본문 전체). 담기는 값이 JWT `jti` 클레임인데 `token_id` 는 토큰 값 자체로 오해되고, 본문 설명은 이미 "jti"라고 적고 있어 이름과 설명이 어긋나 있었다. RFC 7519 표준 용어로 통일 | developer2@lulumedic.com |
| 1.9 | 2026-09-01 | 컬럼명 `confirmed_count` → **`enrollment_count`** (47건). 이름이 `CONFIRMED`만 센다고 읽히지만 실제로는 PENDING+CONFIRMED를 세고 결제 확정 시 값이 변하지 않아 두 지점에서 오해를 부르던 문제 해소. 요건 문서의 "현재 신청 인원"과 어휘를 일치시켰다. 시나리오 32 결번 정리(1~41 연속) | developer2@lulumedic.com |
| 1.8 | 2026-08-31 | **독립 재검증(3차) 반영.** Critical 1건: §4.4에 소유권 검사가 없어 타인의 신청을 취소할 수 있었던 것 수정(만료 배치와 공유 경로이므로 조건부 5-a). Important: §4.2 정원 초과 시 거부로 변경하고 §4.5를 독립 트랜잭션화(Context Anchor·시나리오 1과의 모순 해소), `capacity` 증가 시 승격 트리거 추가, `OPEN → CLOSED` 시 잔여 `WAITING` 일괄 정리, §4.4에 `klass_id` 일치 검증 + §4.6 파라미터 보완, 시나리오 13 문구 한정. 미결 ⑧(`refresh_token` 정리 주기) 신설. 시나리오 32건 → 41건. Minor 12건 | developer2@lulumedic.com |
| 1.7.1 | 2026-08-31 | Minor 2건 정리: 시나리오 번호를 1~32로 연속 정리(17 결번·`12-a` 하위번호 해소, n-2), §6 추적표에 FR ID 열 추가 및 **추적표에 없던 FR-07·FR-09·FR-15 행 보완**(n-8). FR-01~18·A1~A7 전체가 기계적으로 대조 가능해졌다 | developer2@lulumedic.com |
| 1.7 | 2026-08-31 | **재검증(2차) 반영.** Critical 1건: §5.1·§5.2 SQL의 별칭 교체가 절반만 적용되어 실행 불가였던 것 복구(N-8, v1.6이 만든 회귀). Important: `ck_refresh_token_revoked` 논리식 확정(쌍방향)+DDL 추가+§4.7 4번 병기, §3.4에 `PENDING→CONFIRMED` 만료 조건, §4.8을 화이트리스트 방식으로 재작성(금지 전이 차단)+role 검사 추가+`CLOSED→OPEN` 봉쇄 및 잔여 WAITING 구멍 기록, §4.5 라벨 4-a~4-d 재번호, 시나리오 13 이원화, §4.9 락 완화. 시나리오 23건 → 31건. Minor 8건 | developer2@lulumedic.com |
| 1.6.1 | 2026-08-31 | v1.6 수정이 남긴 결함 3건 정리: §4.4 핵심성질 3의 단계 참조(8번→7번), §4.3 만료 검사를 산문에서 의사코드로 편입, §4.2에 명시적 중복 검사 추가(§1.2 이중 방어 원칙·§4.5 4-a와 대칭) | developer2@lulumedic.com |
| 1.6 | 2026-08-31 | **design-validator 검증 반영.** Critical 1건: §4.4 승격에 `klass.status = 'OPEN'` 조건 누락 수정. Important: 승격 루프의 다음 순번 진행 경로, §4.5 활성 신청 검사, 부분 인덱스 표기 통일, 제약 총괄표 누락 2건, `refresh_token` 상태↔시각 CHECK, §4.8/§4.9 트랜잭션 규약 신설, §7 결제 확정 권한, FR-15 판단, Plan 대비 divergence. 시나리오 6건 추가, Minor 10건 | developer2@lulumedic.com |
| 1.5 | 2026-08-31 | 강의 테이블명 `classes` → **`klass`**, FK 컬럼 `class_id` → **`klass_id`**. 엔티티·테이블·컬럼 이름을 통일해 `@Table` 매핑을 제거하고, 단수형 명명 원칙의 예외를 없앰 | developer2@lulumedic.com |
| 1.4 | 2026-08-31 | 사용자 참조 컬럼의 명명 규칙을 §3.1.2로 명시 — 사용자가 만든 것은 역할명(`creator_id`), 사용자 자신의 행위·상태 기록은 `user_id`. `klass`만 다른 이유와 운영상 비용(예외 1개) 기록 | developer2@lulumedic.com |
| 1.3 | 2026-08-31 | **FK 정책 정정**: `klass.creator_id`, `enrollment.user_id`, `waitlist.user_id`에 실제 FK 부착 (인증 2개 테이블은 값 참조 유지). 근거를 §3.1.1로 신설 — 원본의 근거는 ORM 매핑에 관한 것이고 DDL FK는 별개 결정이며, 물리 삭제가 없는 설계에서 FK 비용은 0이다. §5.1 고아 행 탐지 범위를 인증 2개로 축소 | developer2@lulumedic.com |
| 1.2 | 2026-08-31 | ③ 시각 타입 하이브리드 확정. 타입별 사용 지점 표 추가, `DATE`↔현재 시각 비교 시 `LocalDate.now(clock)` 사용 규칙 명시, `starts_on`/`ends_on`이 신청 차단 조건이 아님을 명문화 | developer2@lulumedic.com |
| 1.1 | 2026-08-31 | §2 ② 정정: CLOSED에서 대기열 승격 **중단**으로 변경(기존 PENDING 결제 확정은 허용 유지). 확정과 승격의 성격 차이 및 승격 체인이 명단 확정을 지연시키는 문제를 §2.1로 신설. §4.4 규약과 서술의 불일치 해소 | developer2@lulumedic.com |
| 1.0 | 2026-08-31 | ERD 확정. 7개 테이블 전 컬럼 명세, ENUM 4종, 상태 전이표 3종, 제약·인덱스, 동시성 규약 7종, 정합성 검증·복구, 검증 시나리오 17건. Plan 미결 6건 확정 | developer2@lulumedic.com |
