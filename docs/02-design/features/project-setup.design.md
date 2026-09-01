# 프로젝트 기본 세팅 설계서 (project-setup)

> **Summary**: 검증된 인증 자산을 인용해 이식하고, 확정된 ERD의 7개 테이블을 얹어 실행 가능한 뼈대를 만든다. 인용 시 컨벤션 정합을 위한 divergence 13건을 명시 추적한다.
>
> **Project**: klass (greenfield)
> **Version**: 0.5
> **Author**: developer2@lulumedic.com
> **Date**: 2026-09-01
> **Status**: Draft
> **Planning Doc**: [project-setup.plan.md](../../01-plan/features/project-setup.plan.md)
> **ERD 정본**: [class-enrollment-erd.design.md](./class-enrollment-erd.design.md) (v1.10)
> **컨벤션 원본**: [class-enrollment-erd.plan.md](../../01-plan/features/class-enrollment-erd.plan.md) §7.2 · §8.2
> **인증 원본**: https://github.com/Chals85/sample-jwt-authentication

### Pipeline References

| Phase | Document | Status |
|-------|----------|--------|
| Phase 1 | ERD 정본 §3 Data Model | ✅ |
| Phase 2 | `class-enrollment-erd.plan.md` §8.2 + 본 문서 §10 | ✅ |
| Phase 3 | Mockup | N/A (백엔드) |
| Phase 4 | 본 문서 §4 API Specification | ✅ |

---

## Context Anchor

| Key | Value |
|-----|-------|
| **WHY** | 확정된 ERD를 실행 가능한 형태로 만든다. 설계 문서만으로는 정원 불변식도 인증 흐름도 검증할 수 없다 |
| **WHO** | 이 저장소의 개발자 — 2차(수강신청 로직)를 얹을 기반을 받는 사람 |
| **RISK** | **QueryDSL × Hibernate 7 / Boot 4 / Java 25 조합의 미검증.** 빌드 자체가 서지 않으면 이후 전부가 막힌다 |
| **SUCCESS** | `./gradlew build` 통과 + `bootRun` 기동 + 로그인→`/v1/users/me` 성공 + 7개 테이블 생성 + Redoc/Swagger 렌더링 |
| **SCOPE** | Phase 1 빌드 환경 검증(스파이크 3종) → Phase 2 인증 이식 → Phase 3 수강 도메인 엔티티 → Phase 4 문서 파이프라인 |

---

## 1. Overview

### 1.1 설계 목표

1. **검증된 로직의 무손실 이식** — 원본의 토큰 회전·재사용 감지·블랙리스트를 로직 변경 없이 옮긴다. 바뀌는 것은 이름과 위치뿐이며, 그 외 모든 차이는 §12에 등재한다.
2. **한 개념에 한 이름** — DB 컬럼부터 API 응답 JSON까지 같은 개념이 같은 이름을 갖는다.
3. **리스크의 조기 격리** — 미검증 요소를 첫 모듈에서 판정한다. 나중에 터지면 이미 쌓은 것이 함께 무너진다.
4. **divergence의 명시 추적** — 추적되지 않는 차이는 나중에 버그로 읽힌다.

### 1.2 설계 원칙

- **도메인은 프레임워크를 모른다** — 엔티티는 JPA/Jakarta 어노테이션 외 Spring 타입을 import하지 않는다.
- **상태 변경은 의도가 드러나는 메서드로만** — public setter를 두지 않는다.
- **규칙은 도메인 안에 둔다** — 서비스에 `if (token.isRevoked()) throw ...`가 생기면 규칙이 새어나간 신호다.
- **테스트가 문서를 만든다** — RestDocs 스니펫이 없으면 빌드가 실패한다.

---

## 2. Architecture Options

### 2.0 아키텍처 비교

| 기준 | A: 원본 최대 인용 | B: 전면 재작성 | **C: 인용 + 컨벤션 정합** |
|------|:-:|:-:|:-:|
| **접근** | 복사 후 패키지 치환만 | 원본은 참조, 전부 신규 작성 | 로직 보존 + 이름 정합 |
| **신규 파일** | ~89 (main 75 + test 14) | ~89 | ~89 |
| **원본 대비 수정** | 0 | 전량(89) | 이름 변경 지점만 |
| **복잡도** | 낮음 | 높음 | 낮음 |
| **스키마 일관성** | ❌ 인증 `enabled` / 수강 `is_*` 로 분열 | ✅ | ✅ |
| **검증 로직 보존** | ✅ | ❌ 엣지 케이스 재유입 위험 | ✅ |
| **공수** | 낮음 | 높음 | 중간 |
| **리스크** | 컨벤션 부채 | 인증 버그 | 이름 변경 누락(§12로 통제) |

**Selected: Option C** — **근거**: A는 ERD 정본 §3이 확정한 `is_enabled` / `is_revoked`를 지킬 수 없어 인증과 수강 도메인의 스키마 명명이 갈린다. B는 Plan §7.2의 *"재설계 이익이 없다"*는 확정과 배치된다.

> **사용자 결정으로 C가 강화됐다.** 원본과 `class-enrollment-erd.plan.md` §8.2는 *"자바 필드는 접두어 없이 두고 `@Column(name=...)`으로 매핑"* 이었으나, **자바 필드명·접근자·DTO·API 응답 JSON까지 전 계층에 `is` 접두어를 적용**한다. 상세는 §10.1과 §12 D-1.

### 2.1 컴포넌트 다이어그램

```
                    ┌──────────────────────────────────────┐
   HTTP ───────────▶│        adapter.in.web                │
                    │  AuthController / UserController     │
                    │  GlobalExceptionControllerAdvice     │
                    └───────────────┬──────────────────────┘
                                    │ (UseCase 포트)
                    ┌───────────────▼──────────────────────┐
                    │        application.service           │
                    │  AuthService / UserService           │
                    │  RefreshTokenBreachHandler           │
                    │  AccessTokenVerificationService      │
                    │  RevokedAccessTokenCleaner (@Scheduled)│
                    └───────┬───────────────────┬──────────┘
                            │ (out 포트)         │
              ┌─────────────▼──────┐   ┌────────▼────────────────┐
              │  adapter.out.      │   │  adapter.out.           │
              │  persistence       │   │  security / token       │
              │  (JPA Repository)  │   │  DomainAuthentication-  │
              │                    │   │  Provider (§7 순서 보장) │
              └─────────┬──────────┘   └─────────────────────────┘
                        │
                  ┌─────▼─────┐          ┌──────────────────────────┐
                  │  domain   │◀─────────│  infrastructure          │
                  │  (엔티티)  │          │  SecurityConfig / Filter │
                  └───────────┘          │  ClockConfig / Scheduling│
                                         └──────────────────────────┘
```

### 2.2 데이터 흐름

**로그인**
```
POST /v1/auth/login
  → AuthController → LoginUseCase → AuthService
  → CredentialsVerifierPort → AuthenticationManager → DomainAuthenticationProvider
       └─ 비밀번호 검증 → (통과 후) 계정 상태 검사   ★ 순서가 §7 보안 요건
  → TokenGeneratorPort (Nimbus) → access + refresh 발급
  → TokenHasherPort (SHA-256) → RefreshToken 저장 (원문 미저장)
  → TokenResponse
```

**재발급 (회전 + 재사용 감지)**
```
POST /v1/auth/reissue
  → TokenParserPort 파싱 → typ == REFRESH 확인 (TOKEN_TYPE_MISMATCH)
  → SHA-256 해싱 → RefreshToken 조회 (REFRESH_TOKEN_NOT_FOUND)
  → RefreshToken.rotate(now)
       ├─ 이미 폐기됨 → REFRESH_TOKEN_REUSED → RefreshTokenBreachHandler
       │                  → revokeAllByUserId() 벌크 UPDATE → 401
       └─ 정상 → isRevoked = true, revokedAt = now → 새 토큰 쌍 발급
```

**보호 자원 접근**
```
GET /v1/users/me  (Authorization: Bearer ...)
  → JwtAuthenticationFilter → AccessTokenVerificationService
  → 서명·만료 검증 → typ == ACCESS 확인 → 블랙리스트 조회(jti)
  → SecurityContext 설정 → UserController → DB 재조회 → UserResponse
```

### 2.3 의존성

| 컴포넌트 | 의존 대상 | 목적 |
|----------|-----------|------|
| `AuthService` | `CredentialsVerifierPort`, `TokenGeneratorPort`, `TokenHasherPort`, `RefreshToken*Port` | 인증 유스케이스 조립 |
| `CredentialsVerifierPort` 구현 | `AuthenticationManager` → `DomainAuthenticationProvider` | **검사 순서 보장(§7)** |
| `NimbusJwtAdapter` | `starter-security-oauth2-resource-server` | JwtEncoder/JwtDecoder |
| `RevokedAccessTokenCleaner` | `SchedulingConfig`(`@EnableScheduling`) | 블랙리스트 정리(FR-11) |
| 엔티티 전체 | `ClockConfig`가 주입하는 `Clock` | 시각 결정성 |
| 수강 엔티티 3종 | 없음 (2차에서 서비스가 붙는다) | 스키마 확정 |

---

## 3. Data Model

> ERD 정본 §3이 논리 모델의 정본이다. 본 절은 **JPA 매핑으로의 대응**만 다룬다.

### 3.1 엔티티 / 테이블 목록

**엔티티 6개 ↔ 테이블 7개** (`user_roles`는 `User.roles`의 `@ElementCollection`이라 독립 엔티티가 아니다).

| # | 엔티티 | 테이블 | 출처 | 1차 구현 깊이 |
|:-:|--------|--------|------|---------------|
| 1 | `User` | `users` | 원본 이식 | 엔티티 + Repository + Service + Controller |
| — | (`User.roles`) | `user_roles` | 원본 이식 | `@ElementCollection` |
| 2 | `RefreshToken` | `refresh_token` | 원본 이식 | 엔티티 + Repository + Service |
| 3 | `RevokedAccessToken` | `revoked_access_token` | 원본 이식 | 엔티티 + Repository + Cleaner |
| 4 | `Klass` | `klass` | **신규** | **엔티티 + Repository까지** |
| 5 | `Enrollment` | `enrollment` | **신규** | **엔티티 + Repository까지** |
| 6 | `Waitlist` | `waitlist` | **신규** | **엔티티 + Repository까지** |

### 3.2 ENUM 6종

```
Role              ROLE_USER | ROLE_ADMIN | ROLE_CREATOR
TokenType         ACCESS | REFRESH
KlassStatus       DRAFT | OPEN | CLOSED
EnrollmentStatus  PENDING | CONFIRMED | CANCELLED
EnrollmentSource  DIRECT | WAITLIST
WaitlistStatus    WAITING | PROMOTED | CANCELLED
```

전부 `@Enumerated(EnumType.STRING)`. **ordinal 금지.**

### 3.3 boolean 필드 매핑 (전 계층 통일)

| 계층 | `User` 활성 여부 | `RefreshToken` 폐기 여부 |
|------|------------------|--------------------------|
| DB 컬럼 | `is_enabled` | `is_revoked` |
| 엔티티 필드 | `isEnabled` | `isRevoked` |
| 엔티티 접근자 | `isEnabled()` | `isRevoked()` |
| 내부/응답 DTO | `UserResult.isEnabled`, `UserResponse.isEnabled` | — |
| API 응답 JSON | `data.isEnabled` | — |

```java
@Column(name = "is_enabled", nullable = false)
private boolean isEnabled = true;
public boolean isEnabled() { return isEnabled; }
```

> `SecurityUserDetails`는 record 컴포넌트를 `isEnabled`로 두면 자동 생성 접근자가 `UserDetails.isEnabled()` 계약을 만족해 **명시적 오버라이드가 불필요해진다**(§12 D-7).

> ⚠️ **컴파일러가 잡아주지 않는 지점 3곳.** 이름을 바꿔도 컴파일은 통과하고 **런타임에 실패**한다. §12 D-1 체크리스트 참조.
>
> | # | 위치 | 형태 | 실패 시점 |
> |:-:|------|------|-----------|
> | 1 | `RefreshTokenJpaRepository` `@Query` | JPQL 문자열 `r.revoked` (2회) | **Hibernate 부트스트랩 — 앱이 기동조차 안 된다.** FR-08 경로 |
> | 2 | `UserTest` | `getDeclaredField("enabled")` | 테스트 실행 |
> | 3 | `SpringSecurityCredentialsAdapterTest` | `setField(user, "enabled", false)` | 테스트 실행 |
>
> 추가로 `UserControllerTest`의 RestDocs `fieldWithPath("data.enabled")`는 문서 생성 단계에서 실패한다.

### 3.4 시각 타입 매핑 (ERD 정본 §2.2)

| 타입 | 대상 | 규칙 |
|------|------|------|
| `LocalDate` | `klass.starts_on`, `klass.ends_on` | **`LocalDate.now(clock)`만 사용** |
| `LocalDateTime` | `*_at` 전부 | 주입된 `Clock`으로 생성 |
| `int` | `klass.cancellation_period_days` | 기간이지 시점이 아니다 |

**무인자 `now()` 호출 금지.** 시간대 결정을 `ClockConfig` 한 곳에 모은다.

### 3.5 FK 정책 (ERD 정본 §3.1.1)

| 대상 | DDL FK | JPA 매핑 |
|------|:------:|----------|
| `klass.creator_id` | ✅ `fk_klass_creator` | **`@ManyToOne(LAZY) User creator`** |
| `enrollment.klass_id`, `enrollment.user_id` | ✅ `fk_enrollment_klass` / `fk_enrollment_user` | **`@ManyToOne(LAZY)`** |
| `waitlist.klass_id`, `waitlist.user_id` | ✅ `fk_waitlist_klass` / `fk_waitlist_user` | **`@ManyToOne(LAZY)`** |
| `user_roles.user_id` | ✅ **CASCADE** | `@CollectionTable` |
| `refresh_token.user_id` | ❌ 값 참조 | `Long userId` |
| `revoked_access_token.user_id` | ❌ 값 참조 | `Long userId` |

**수강 도메인은 `@ManyToOne(LAZY)`를 쓴다**(D-13). 인증 2개 테이블만 값 참조를 유지한다 — 그쪽은 고아 행 피해가 자기 완결적이라는 근거가 ERD §3.1.1에 따로 있다.

> ⚠️ `LAZY` 이므로 **목록 조회에서 연관 엔티티를 함께 쓸 때는 fetch join 을 명시**해야 N+1 이 나지 않는다. 2차의 강의 목록·수강생 목록이 첫 후보다.

### 3.6 제약 — ERD §3.5.2 전수 대응

> ⚠️ **`@Table(check = ...)`는 존재하지 않는 API다.** `jakarta.persistence.Table`에 `check` 속성이 없다. **Hibernate `@Check(constraints = "...")`** 또는 `schema.sql`을 쓴다.

> **왜 1차부터 넣는가** (2026-09-01 논의, 유지로 결정). "초기에는 제약을 늦춘다"는 통상의 조언은
> ① 무엇이 불변식인지 아직 모르거나 ② 스키마 변경에 마이그레이션 비용이 붙을 때 성립한다.
> 이 프로젝트는 둘 다 아니다 — ERD가 v1.10까지 확정됐고, `ddl-auto: create-drop` 이라 제약 수정
> 비용이 어노테이션 한 줄이다.
>
> 오히려 2차의 작업(비관적 락·카운터 증감·대기열 승격)이 **틀려도 조용한** 종류의 코드다.
> 카운터가 1 어긋나도 예외가 나지 않고 나중에 "정원 10인데 11명"으로 발견된다. `ck_klass_count`
> 가 있으면 그 트랜잭션이 즉시 실패한다. 가장 어려운 코드를 쓰는 구간에 안전망을 빼는 셈이 되므로
> 1차부터 둔다.
>
> 대가는 있다. `ck_enrollment_pending` 같은 상태↔타임스탬프 쌍방향 제약은 2차의 상태 전이 구현에서
> 자주 걸릴 것이다 (예: `PENDING → CONFIRMED` 시 `expires_at` 을 비우지 않으면 위반). 그것이 목적이다
> — 만료 시각이 남은 CONFIRMED 행은 만료 배치가 잘못 집어갈 수 있는 데이터다. **Flyway 도입 시점에는
> 이 판단을 다시 검토한다** (그때부터는 제약 변경에 마이그레이션이 붙는다).

| 제약명 | 테이블 | 내용 |
|--------|--------|------|
| — (UNIQUE) | `users` | `username` |
| — (PK/FK) | `user_roles` | PK `(user_id, role)` / FK CASCADE |
| — (UNIQUE) | `refresh_token` | `token_hash` |
| `ck_refresh_token_period` | `refresh_token` | `expires_at > issued_at` |
| `ck_refresh_token_revoked` | `refresh_token` | **`is_revoked = (revoked_at IS NOT NULL)`** 쌍방향 |
| — (UNIQUE) | `revoked_access_token` | `jti` |
| `ck_klass_capacity` | `klass` | **`capacity > 0`** |
| `ck_klass_count` | `klass` | **`enrollment_count BETWEEN 0 AND capacity`** ← 정원 불변식 |
| `ck_klass_price` | `klass` | `price >= 0` |
| `ck_klass_period` | `klass` | `ends_on >= starts_on` |
| `ck_klass_cancel` | `klass` | `cancellation_period_days IS NULL OR >= 0` |
| `ck_enrollment_pending` / `_confirmed` / `_cancelled` | `enrollment` | 상태별 타임스탬프 정합성 (ERD §3.2.6) |
| — (UNIQUE) | `enrollment` | `(klass_id, active_user_key)` |
| `ck_waitlist_position` | `waitlist` | `position > 0` |
| `ck_waitlist_promoted` | `waitlist` | `PROMOTED` → `promoted_at IS NOT NULL` |
| — (UNIQUE) | `waitlist` | `(klass_id, position)`, `(klass_id, waiting_user_key)` |

> **제약명은 ERD 정본 표기를 그대로 쓴다.** `ck_klass_capacity`는 `capacity > 0`이고, 정원 불변식은 `ck_klass_count`다 — 두 이름을 바꿔 쓰면 ERD와 기계적 대조가 불가능해진다.

#### 3.6.1 ⚠️ 생성 컬럼 — module-1에서 가장 먼저 검증한다

`active_user_key` / `waiting_user_key`는 ERD §3.7에서 **생성 컬럼**이다.

```sql
active_user_key BIGINT GENERATED ALWAYS AS
  (CASE WHEN status <> 'CANCELLED' THEN user_id END) STORED
```

ERD 정본이 두 곳에서 경고를 남겼고, 설계는 이를 그대로 승계한다.

- ERD §3.7 벤더 비교표: *"H2 2.x — `STORED` 미지원 가능, **구현 시 확인 필요**"*
- ERD §3.7 주석: *"생성 컬럼이 부분 유니크 대체 설계의 핵심이므로, **대상 DB에서 생성 컬럼 문법을 가장 먼저 검증한다.** 지원되지 않으면 애플리케이션 갱신 방식으로 후퇴한다."*

**후퇴 경로**: 생성 컬럼이 안 되면 `active_user_key`를 일반 컬럼으로 두고 상태 전이 시 애플리케이션이 갱신한다. 이 경우 DB 단독 보증이 약해지므로 §12에 divergence로 등재한다.

**DDL 생성 경로**: `ddl-auto: create-drop`은 생성 컬럼·복합 CHECK를 완전히 표현하지 못한다. `schema.sql` 병행 시 `spring.jpa.defer-datasource-initialization: true`가 필요하다(그렇지 않으면 Hibernate가 테이블을 만들기 전에 `schema.sql`이 실행된다). module-1이 이 조합까지 판정한다.

### 3.7 인덱스 — ERD §3.6 전수 대응

| 인덱스 (ERD 정본 표기) | 대응 요건 |
|------------------------|-----------|
| `idx_users_username` (unique) | 로그인 |
| `idx_refresh_token_hash` (unique) | 재발급 조회 |
| `idx_refresh_token_user_id` | 탈취 시 전체 무효화 (FR-08) |
| `idx_revoked_access_token_jti` (unique, `jti`) | 블랙리스트 검증 (매 요청) |
| `idx_revoked_access_token_expires_at` | 블랙리스트 정리 (FR-11) |
| `idx_klass_status` (`status`, `id DESC`) | 강의 목록 + 커서 페이지네이션 |
| `idx_klass_creator` (`creator_id`, `id DESC`) | 크리에이터 내 강의 목록 |
| `idx_enrollment_user` (`user_id`, `id DESC`) | 내 신청 목록 |
| `idx_enrollment_klass_status` (`klass_id`, `status`, `id DESC`) | 크리에이터 수강생 목록 |
| `idx_enrollment_expiry` (`expires_at`) | PENDING 만료 스캔 (2차 소비) |
| `idx_waitlist_next` (`klass_id`, `status`, `position`) | 다음 승격 대상 (2차 소비) |

> **블랙리스트 인덱스 이름은 D-11로 정리됐다.** 원본은 `idx_revoked_access_token_id`, ERD는 `uq_revoked_access_token_id`로 갈려 있었으나, 컬럼이 `jti`가 되면서 양쪽 모두 `..._jti` 로 통일됐다.

### 3.8 예약어 점검 (ERD §3.7)

H2에서 큰따옴표 없이 쓸 수 있는지 module-4에서 확인: **`position`**(SQL:2016 예약어, 함수명과 겹침), **`role`**. 충돌 시 `@Column(name = "\"position\"")` 또는 컬럼명 변경 후 §12 등재.

---

## 4. API Specification

> 1차는 **인증 4개 엔드포인트**만 노출한다.

### 4.1 엔드포인트 목록

| Method | Path | 설명 | 인증 |
|--------|------|------|:----:|
| POST | `/v1/auth/login` | 로그인, 토큰 쌍 발급 | ❌ |
| POST | `/v1/auth/reissue` | Refresh 회전 후 재발급 | ❌ |
| POST | `/v1/auth/logout` | Access 블랙리스트 등록 + Refresh 폐기 | ✅ |
| GET | `/v1/users/me` | 내 정보 조회 | ✅ |

### 4.2 상세

#### `POST /v1/auth/login`

```json
// Request
{ "username": "chals", "password": "test" }

// 200 OK
{ "data": { "accessToken": "eyJ...", "refreshToken": "eyJ...", "tokenType": "Bearer" } }
```

| 실패 | 코드 | 비고 |
|------|------|------|
| 아이디 없음 / 비밀번호 불일치 | `INVALID_CREDENTIALS` (401) | **두 경우를 구분하지 않는다** — 구분하면 사용자 열거가 가능 |
| 비활성 계정 | `USER_DISABLED` (401) | **구분해서 응답한다.** 비밀번호 검증을 통과한 뒤에만 도달하므로 정보가 새지 않는다 |

> 이 두 줄이 §7의 계정 열거 방지 요건과 짝을 이룬다. 순서를 보장하는 것은 `DomainAuthenticationProvider`이며(§9.4), 표준 `DaoAuthenticationProvider`로 대체하면 검사 순서가 뒤집혀 **보안 요건이 조용히 깨진다**.

#### `POST /v1/auth/reissue`

```json
// Request
{ "refreshToken": "eyJ..." }

// 200 OK — 새 토큰 쌍. 이전 refresh 는 폐기된다
{ "data": { "accessToken": "...", "refreshToken": "...", "tokenType": "Bearer" } }
```

| 실패 | 코드 |
|------|------|
| DB에 없는 토큰 | `REFRESH_TOKEN_NOT_FOUND` (401) |
| **이미 폐기된 토큰의 재사용** | `REFRESH_TOKEN_REUSED` (401) + 해당 사용자 전체 토큰 무효화 |
| 만료 | `REFRESH_TOKEN_EXPIRED` (401) |
| `typ`이 REFRESH가 아님 | `TOKEN_TYPE_MISMATCH` (401) |

#### `POST /v1/auth/logout`

```json
// Request — refreshToken 만 받는다
{ "refreshToken": "eyJ..." }

// 200 OK
{ "data": null }
```

`userId`·`jti`·`exp`는 요청 본문이 아니라 **인증된 principal(`AuthenticatedUser`)에서 채운다.** 클라이언트가 남의 토큰을 폐기하도록 두지 않기 위함이다.

#### `GET /v1/users/me`

```json
// 200 OK
{ "data": { "id": 1, "username": "chals", "roles": ["ROLE_USER"],
            "isEnabled": true, "createdAt": "2026-09-01T10:00:00" } }
```

> `isEnabled` — §3.3 전 계층 통일 결정에 따라 원본의 `enabled`에서 변경(§12 D-1).

토큰 클레임이 아니라 **DB를 다시 읽는다.** `isEnabled`·`createdAt`은 토큰에 없고 권한 변경이 즉시 반영되어야 한다.

### 4.3 문서 산출물

| 경로 | 렌더러 | 용도 |
|------|--------|------|
| `/docs/api-guide.html` | Redoc | 읽기용 정본 |
| `/docs/api-test.html` | Swagger UI | Try it out |
| `/docs/openapi3.json` | — | RestDocs 스니펫에서 생성. **커밋하지 않는다** |
| `/v3/api-docs`, `/swagger-ui.html` | springdoc | 보조 (R-1류 사고 시 승격용 안전망) |

---

## 5. UI/UX Design

**N/A** — 백엔드 전용. 사람이 보는 화면은 §4.3의 문서 페이지 2개와 `/h2-console`이 전부다.

---

## 6. Error Handling

### 6.1 에러 코드 — 원본 17종 전수

**`AuthError` (9)**

| 코드 | HTTP | 상황 |
|------|:----:|------|
| `INVALID_CREDENTIALS` | 401 | 아이디 없음 / 비밀번호 불일치 (구분하지 않음) |
| `TOKEN_EXPIRED` | 401 | Access 만료 |
| `TOKEN_INVALID` | 401 | 서명 위조·형식 오류 |
| `TOKEN_REVOKED` | 401 | **Access 블랙리스트** 적중 |
| `REFRESH_TOKEN_NOT_FOUND` | 401 | DB에 없는 refresh |
| `REFRESH_TOKEN_REUSED` | 401 | **폐기된 refresh 재사용** → 전체 무효화 동반 |
| `REFRESH_TOKEN_EXPIRED` | 401 | refresh 만료 |
| `TOKEN_TYPE_MISMATCH` | 401 | `typ` 클레임 불일치 |
| `UNAUTHENTICATED` | 401 | 인증 정보 없음 |

**`UserError` (2)**: `USER_NOT_FOUND`(404), `USER_DISABLED`(401)

**`CommonError` (6)**: `VALIDATION_ERROR`(400), `ACCESS_DENIED`(403), `MALFORMED_REQUEST`(400), `NOT_FOUND`(404), `METHOD_NOT_ALLOWED`(405), `INTERNAL_ERROR`(500)

> **`TOKEN_REVOKED`와 `REFRESH_TOKEN_REUSED`를 혼동하지 말 것.** 전자는 로그아웃된 Access, 후자는 탈취 신호다. 후자만 전체 토큰 무효화를 동반한다.

### 6.2 응답 형식

```json
{ "error": { "code": "REFRESH_TOKEN_REUSED", "message": "...", "details": {} } }
```

`GlobalExceptionControllerAdvice`가 `BusinessException`을 단일 지점에서 변환한다. 필터 단계 실패는 `@ControllerAdvice` 바깥이므로 `CustomAuthenticationEntryPoint`(401) / `CustomAccessDeniedHandler`(403)가 **같은 형식으로** 응답한다.

---

## 7. Security Considerations

- [x] **비밀번호는 BCrypt 해시로만 저장**
- [x] **Refresh 토큰은 SHA-256 해시로 저장** — DB 유출 시에도 API 호출 불가
- [x] **토큰 회전 + 재사용 감지** — `REFRESH_TOKEN_REUSED` 시 사용자 전체 토큰 무효화
- [x] **Access 블랙리스트** (`jti` 기준) + **정리 배치** (`@EnableScheduling` 필수)
- [x] **계정 열거 방지** — 활성 여부 검사는 비밀번호 검증 **이후**. 보장 주체는 `DomainAuthenticationProvider` 생성자 (§9.4)
- [x] **CSRF 명시적 비활성화** — Security 7은 API에도 CSRF 기본 적용. 끄지 않으면 모든 POST가 403
- [x] **Stateless 세션**
- [x] **로그아웃 대상은 principal에서** — 요청 본문의 userId를 믿지 않는다
- [ ] **⚠️ `jwt.secret` 평문** — 1차 한정. **실서비스 전 환경변수 분리 필수**
- [ ] HTTPS 강제, Rate Limiting — 배포 단계 (1차 범위 외)

---

## 8. Test Plan

> 테스트는 검증 수단이자 **문서 생성원**이다. 스니펫이 없으면 `openapi3.json`이 나오지 않고 빌드가 실패한다.

### 8.1 테스트 범위

| 레벨 | 대상 | 도구 | 원본 파일 수 |
|------|------|------|:----:|
| **L1: 도메인 단위** | 엔티티 규칙 | JUnit 5 | 3 |
| **L2: 어댑터/서비스** | 포트 구현, 서비스 조립 | JUnit 5 + Mockito | 6 |
| **L3: 컨트롤러 + 문서** | 엔드포인트 계약 + RestDocs | MockMvc + restdocs-api-spec | 3 |
| **L4: 통합 E2E** | 인증 전 흐름 | `@SpringBootTest` | 1 |
| **L5: 문서 산출물** | 생성된 스펙 서빙 검증 | `documentationTest` | 1 |

**원본 14개 전수 승계** — 아래 §8.2~§8.4가 14개를 모두 덮는다.

### 8.2 L1 / L2 시나리오

| # | 원본 테스트 | 시나리오 | 기대 | FR |
|:-:|-------------|----------|------|:--:|
| 1 | `UserTest` | 권한 없이 `register` | `IllegalArgumentException` | — |
| 2 | `UserTest` | 비활성 계정 `verifyEnabled()` | `USER_DISABLED` | — |
| 3 | `RefreshTokenTest` | 정상 `rotate()` | `isRevoked=true`, `revokedAt` 기록 | FR-07 |
| 4 | `RefreshTokenTest` | 폐기된 토큰 `rotate()` | `REFRESH_TOKEN_REUSED` | FR-08 |
| 5 | `RefreshTokenTest` | 만료 토큰 `rotate()` | `REFRESH_TOKEN_EXPIRED` | FR-07 |
| 6 | `RevokedAccessTokenTest` | 폐기 기록 생성 | 만료 시각 보존 | FR-09 |
| 7 | `RevokedAccessTokenCleanerTest` | 만료분 정리 | 대상만 삭제 | **FR-11** |
| 8 | `RevokedAccessTokenRepositoryAdapterTest` | 저장/조회 왕복 | 일치 | FR-09 |
| 9 | `NimbusJwtAdapterTest` | 발급 후 파싱 | 클레임 왕복 일치 | FR-06 |
| 10 | `AccessTokenVerificationServiceTest` | 블랙리스트 적중 / `typ` 불일치 | `TOKEN_REVOKED` / `TOKEN_TYPE_MISMATCH` | FR-09, FR-10 |
| 11 | `SpringSecurityCredentialsAdapterTest` | 비활성 계정 / 잘못된 비밀번호 | **`USER_DISABLED` / `INVALID_CREDENTIALS` 구분** | FR-06 |
| 12 | `AuthServiceTest` | 재사용 감지 시 | 사용자 전체 토큰 무효화 | **FR-08** |
| 13 | **신규** | 수강 3엔티티 영속화 + 제약 위반 | 저장 성공 / 위반 거부 | **FR-03, FR-04, FR-05** |
| 14 | **신규** | QueryDSL Q클래스 단순 조회 | 컴파일 + 동작 | **FR-02** |
| 15 | **신규** | 스키마 메타데이터 조회 | 7테이블·인덱스 존재 | **FR-03, FR-15** |
| 16 | **신규** | `DefaultUserInitializer` 2회 실행 | 계정 2개, 중복 생성 없음 | **FR-14** |

> #13·#15·#16은 Plan의 High 요구사항(FR-03·04·05·14·15)이 "육안 확인"에만 의존하지 않도록 신설했다.

### 8.3 L3 시나리오 (엔드포인트 계약 + 문서)

| # | 원본 테스트 | 엔드포인트 | 시나리오 | 기대 | FR |
|:-:|-------------|------------|----------|------|:--:|
| 1 | `AuthControllerTest` | `POST /v1/auth/login` | 유효 자격 증명 | 200, 토큰 쌍 | FR-06 |
| 2 | `AuthControllerTest` | `POST /v1/auth/login` | 잘못된 비밀번호 | 401 `INVALID_CREDENTIALS` | FR-06 |
| 3 | `AuthControllerTest` | `POST /v1/auth/reissue` | 유효 refresh | 200, 새 토큰 쌍 | FR-07 |
| 4 | `AuthControllerTest` | `POST /v1/auth/reissue` | 재사용 | 401 `REFRESH_TOKEN_REUSED` | FR-08 |
| 5 | `AuthControllerTest` | `POST /v1/auth/logout` | 인증됨 | 200 | FR-09 |
| 6 | `UserControllerTest` | `GET /v1/users/me` | 토큰 없음 | 401 | FR-10 |
| 7 | `UserControllerTest` | `GET /v1/users/me` | 유효 토큰 | 200, **`data.isEnabled`** | FR-10 |

(`BaseControllerTest`는 공통 설정 클래스)

### 8.4 L4 통합 시나리오 (`AuthFlowIntegrationTest`)

| # | 흐름 | 성공 기준 | FR |
|:-:|------|-----------|:--:|
| 1 | 로그인 → `/v1/users/me` | 200, 본인 정보 | FR-06, FR-10 |
| 2 | 로그인 → 재발급 → 새 토큰으로 접근 | 200, 이전 refresh는 거부 | FR-07 |
| 3 | 로그인 → 로그아웃 → 같은 access로 접근 | **401** (블랙리스트 적중) | FR-09 |
| 4 | 재발급 2회 중 첫 refresh 재사용 | 401 + 해당 사용자 모든 refresh 무효 | FR-08 |

Plan §4.1 DoD *"인증 도메인 테스트가 원본과 동등하게 통과"*의 판정 근거가 이 표다.

### 8.5 L5 문서 산출물 (`DocumentationIntegrationTest`)

페이지가 참조하는 `openapi3.json`이 실제로 서빙되는가, 유효한 JSON인가, 모든 엔드포인트를 담았는가. **`test`에서 제외**하고 별도 태스크로 둔다(`generatedDocument`가 `test`에 의존하므로 같이 두면 순환).

### 8.6 FR 추적표

| FR | 검증 수단 |
|----|-----------|
| FR-01 빌드 통과 | `./gradlew build` (CI 판정) |
| FR-02 QueryDSL Q클래스 | §8.2 #14 |
| FR-03 7테이블 생성 + **FK 6종** | §8.2 #13, #15 + **FK 검증 2건** |
| FR-04 CHECK 제약 | §8.2 #13, #15 |
| FR-05 활성 중복 차단 | §8.2 #13 |
| FR-06 로그인 | §8.2 #9·#11, §8.3 #1·#2, §8.4 #1 |
| FR-07 회전 | §8.2 #3·#5, §8.3 #3, §8.4 #2 |
| FR-08 재사용 감지 | §8.2 #4·#12, §8.3 #4, §8.4 #4 |
| FR-09 로그아웃 블랙리스트 | §8.2 #6·#8·#10, §8.3 #5, §8.4 #3 |
| FR-10 인증 가드 | §8.2 #10, §8.3 #6·#7, §8.4 #1 |
| FR-11 블랙리스트 정리 | §8.2 #7 + **`@EnableScheduling` 존재 확인** |
| FR-12 Redoc | §8.5 |
| FR-13 Swagger UI | §8.5 |
| FR-14 멱등 시딩 | §8.2 #16 |
| FR-15 인덱스 생성 | §8.2 #15 |

### 8.7 시드 데이터

| 엔티티 | 최소 | 필수 |
|--------|:----:|------|
| `User` | 2 | `ROLE_USER` 1 + **`ROLE_CREATOR` 1** |

> ⚠️ 원본 `DefaultUserProperties`는 **단일 계정 record**(`username`/`password`/`roles`)이고 `application.yml`도 단수 구조다. 계정 2개를 시딩하려면 **`List<DefaultUser>` 구조로 바꾸고 `DefaultUserInitializer`의 시딩 루프도 변경**해야 한다(§12 D-6).

---

## 9. Clean Architecture

### 9.1 레이어 구조 (헥사고날)

| 레이어 | 책임 | 위치 |
|--------|------|------|
| **Adapter (in)** | HTTP 수신, DTO 변환, 예외 → 응답 | `*/adapter/in/web/` |
| **Application** | 유스케이스 조립, 트랜잭션 경계 | `*/application/{port,service,dto}/` |
| **Domain** | 엔티티, 불변식, 상태 전이 | `*/domain/` |
| **Adapter (out)** | JPA, BCrypt, Nimbus 구현 | `*/adapter/out/{persistence,security,token}/` |
| **Infrastructure** | 프레임워크 설정, 필터, 부트스트랩 | `infrastructure/` |

### 9.2 의존 규칙

```
adapter.in ──▶ application.port.in
                     │
               application.service ──▶ domain
                     │                   ▲
                     ▼                   │
              application.port.out ◀── adapter.out
                                          │
                                    infrastructure
```

`domain`은 아무것도 참조하지 않는다.

### 9.3 Import 규칙

| 위치 | 허용 | 금지 |
|------|------|------|
| `domain` | JPA/Jakarta 어노테이션, JDK | **Spring 타입 전부**, 애플리케이션 포트 |
| `application.service` | `domain`, `port.*` | `adapter.*`, 웹/JPA 타입 |
| `adapter.out` | `domain`, `port.out` | `application.service`, `adapter.in` |
| `adapter.in` | `port.in`, 자신의 DTO | `domain` 엔티티 직접 노출, `adapter.out` |

### 9.4 이번 기능의 레이어 배치

| 컴포넌트 | 레이어 | 비고 |
|----------|--------|------|
| `User`, `RefreshToken`, `RevokedAccessToken` | Domain | 원본 이식 |
| `Klass`, `Enrollment`, `Waitlist` | Domain | 신규 |
| `AuthService`, `UserService`, `RefreshTokenBreachHandler`, `AccessTokenVerificationService` | Application | |
| `RevokedAccessTokenCleaner` | Application | `@Scheduled` — `SchedulingConfig` 필요 |
| `*JpaRepository`, `*RepositoryAdapter` | Adapter(out) | |
| `NimbusJwtAdapter`, `Sha256TokenHasherAdapter`, `BcryptPasswordHasherAdapter` | Adapter(out) | |
| **`DomainAuthenticationProvider`** | Adapter(out).security | **§7 검사 순서 보장의 소재지.** 표준 `DaoAuthenticationProvider`로 대체 금지 |
| `DomainUserDetailsService`, `SecurityUserDetails`, `SpringSecurityCredentialsAdapter` | Adapter(out).security | |
| `AuthController`, `UserController` | Adapter(in) | |
| `SecurityConfig`, **`AuthenticationConfig`**, `JwtAuthenticationFilter`, `JwtKeyConfig`, `JwtProperties` | Infrastructure | `AuthenticationConfig`가 `ProviderManager`에 `DomainAuthenticationProvider` 주입 |
| `ClockConfig`, **`SchedulingConfig`**, `OpenApiConfig` | Infrastructure | |
| `DefaultUserInitializer`, `DefaultUserProperties` | Infrastructure.bootstrap | D-6으로 구조 변경 |
| `CustomAuthenticationEntryPoint`, `CustomAccessDeniedHandler`, `AuthenticatedUser` | Infrastructure.security | |

---

## 10. Coding Convention Reference

### 10.1 명명 규칙

| 대상 | 규칙 | 예 |
|------|------|-----|
| 테이블 | 단수 snake_case (예약어만 복수) | `klass` / `users` |
| 엔티티 | 테이블명과 동일 | `Klass` ↔ `klass` |
| 컬럼 | snake_case. 시각 `_at`, **날짜 `_on`**, 기간 `_days` | `starts_on`, `confirmed_at` |
| **boolean** | **DB `is_`·`has_`·`can_` / 자바 필드·접근자·DTO·JSON 모두 `is` 접두어** | `is_enabled` ↔ `isEnabled` ↔ `"isEnabled"` |
| 사용자 참조 | 사용자가 *만든 것* `creator_id` / 사용자 *자신의 기록* `user_id` | `klass.creator_id` |
| 엔티티 접근자 | **Lombok `@Getter`** (JavaBeans). boolean 필드가 `is` 로 시작하면 그대로 | `getUsername()`, `isEnabled()` |
| 제약·인덱스 | ERD 정본 표기 (§3.6·§3.7) | `ck_klass_count` |
| 패키지 | 도메인별 수직 분할 | `com.toby.klass.auth.domain` |

> boolean 규칙은 원본 및 `class-enrollment-erd.plan.md` §8.2 방침(*"자바 필드는 접두어 없이"*)을 **사용자 결정으로 뒤집은 것**이다(§12 D-1). `isIsEnabled()` 문제는 접근자를 `isEnabled()`로 직접 정의해 회피한다 — Lombok `@Getter`를 쓰지 않아 자동 생성 규칙의 영향을 받지 않는다.

### 10.2 주석 규약

- 문서·주석은 **한국어**, 식별자는 영어
- 주요 결정에 `Design Ref: §n` 부착
- **왜 그렇게 했는지**를 적는다

### 10.3 프로퍼티 (Plan §8.3 전수)

```yaml
spring:
  application.name: klass                                    # D-5
  datasource.url: jdbc:h2:mem:klass;MODE=MySQL;DB_CLOSE_DELAY=-1
  jpa:
    hibernate.ddl-auto: create-drop
    open-in-view: false
    defer-datasource-initialization: true                    # schema.sql 병행 시 (§3.6.1)
jwt:
  issuer: klass                                              # D-5
  secret: <Base64, 32바이트 이상>                             # ⚠️ 1차 한정 평문
  access-token-validity: PT30M
  refresh-token-validity: P14D
  revoked-token-cleanup-interval: PT10M
app:
  default-user: [...]                                        # D-6: 리스트 구조로 변경
```

`app.enrollment.*` 4종은 2차 (Plan §2.2).

---

## 11. Implementation Guide

### 11.1 파일 구조

```
klass/
├── build.gradle                    ← 원본 + QueryDSL
├── settings.gradle                 ← rootProject.name = 'klass'
├── gradle/wrapper/                 ← 9.7.1
├── docs/                           ← 복사 완료
└── src/
    ├── main/java/com/toby/klass/
    │   ├── KlassApplication.java
    │   ├── auth/         adapter{in.web{controller,dto}, out{persistence,security,token}},
    │   │                 application{dto,port{in,out{,dto}},service}, domain{,error}
    │   ├── user/         동일 구조
    │   ├── klass/        domain/ + adapter/out/persistence/       ← 신규
    │   ├── enrollment/   domain/ + adapter/out/persistence/       ← 신규
    │   ├── waitlist/     domain/ + adapter/out/persistence/       ← 신규
    │   ├── common/       adapter/in/web/{advice,dto}, domain/error
    │   └── infrastructure/ bootstrap, config{Clock,Scheduling,OpenApi},
    │                       security/{config,filter,jwt,principal,exception}
    ├── main/resources/
    │   ├── application.yml
    │   ├── schema.sql              ← 생성 컬럼·CHECK (§3.6.1 판정 결과에 따라)
    │   └── static/docs/{api-guide.html, api-test.html}
    └── test/java/com/toby/klass/   ← 원본 14 + 신규 4
```

### 11.2 구현 순서

1. [ ] **스파이크 3종** — QueryDSL×Lombok / 생성 컬럼 / CHECK 제약 (§11.3 module-1)
2. [ ] 빌드 환경 + wrapper + **문서 파이프라인 배선 + 스모크 RestDocs 1건** (빌드를 초록으로 유지)
3. [ ] `common` + `infrastructure.config` 골격
4. [ ] 인증/사용자 도메인 이식 (D-1 적용)
5. [ ] Security 설정 + 필터 + 시딩(D-6)
6. [ ] 수강 엔티티 3 + Repository + 제약·인덱스
7. [ ] RestDocs 테스트 전량 + 문서 산출물 검증

### 11.3 Session Guide

#### Module Map

| 모듈 | Scope Key | 내용 | 예상 턴 |
|------|-----------|------|:------:|
| **환경 + 스파이크 3종** | `module-1` | wrapper, `build.gradle`, ① **Lombok + querydsl-apt 공존** 하 Q클래스 생성, ② H2 **생성 컬럼**(`GENERATED ALWAYS AS ... STORED`) DDL 반영, ③ Hibernate `@Check` CHECK 제약 DDL 반영 + `defer-datasource-initialization` 조합, `application.yml` 골격 | 20-25 |
| **공통 골격 + 문서 배선** | `module-2` | `common`(에러 17종·응답 래퍼), `ClockConfig`, `SchedulingConfig`, `GlobalExceptionControllerAdvice`, 문서 파이프라인 태스크 **+ 스모크 RestDocs 테스트 1건 + `DocumentationIntegrationTest` 골격 + `SecurityConfig`의 `WebSecurityCustomizer`** | 20-25 |
| **인증 도메인 이식** | `module-3` | `auth` + `user` 전체 (엔티티 3, 포트, 서비스, 어댑터, `DomainAuthenticationProvider`, Security 설정, 필터, 시딩 D-6). **D-1 이름 변경 적용** | 40-50 |
| **수강 엔티티** | `module-4` | `Klass`/`Enrollment`/`Waitlist` + Repository + 제약·인덱스 + **예약어 점검(§3.8)** | 25-30 |
| **문서 산출물** | `module-5` | RestDocs 테스트 전량(L3), Redoc/Swagger 페이지, `documentationTest` 완성 | 20-25 |

> **`SecurityConfig`가 module-2로 앞당겨진 이유** (실행 중 확인): `spring-boot-starter-security`가
> 클래스패스에 있으면 Boot가 **모든 요청에 폼 로그인을 요구하는 기본 체인**을 만든다. 그러면
> `/docs/openapi3.json` 요청에 로그인 페이지 HTML이 돌아와 `documentationTest`가 JSON 파싱에서
> 깨진다. 그래서 문서 경로를 필터 체인에서 빼는 `WebSecurityCustomizer`만 module-2에 두고,
> `SecurityFilterChain`(CSRF·stateless·JWT 필터)은 예정대로 module-3에서 같은 클래스에 더한다.
>
> **`documentationTest`의 소유**: 골격과 빌드 배선은 module-2, 실제 검증 내용은 module-5. module-2에서 스모크 테스트 1건을 함께 넣는 이유는, 원본 `build.gradle`이 `bootJar`/`bootRun`을 `generatedDocument`에 묶어 두어 **스니펫이 하나도 없으면 module-3~4 내내 `build`와 `bootRun`이 실패**하기 때문이다. 그러면 세션 2~4 동안 FR-01의 성공 판정 기준이 사라진다.

#### 권장 세션 계획

| 세션 | 단계 | 범위 | 턴 |
|------|------|------|:--:|
| 1 | Plan + Design | 전체 | 완료 |
| 2 | Do | `--scope module-1,module-2` | 40-50 |
| 3 | Do | `--scope module-3` | 40-50 |
| 4 | Do | `--scope module-4,module-5` | 45-55 |
| 5 | Check + Report | 전체 | 30-40 |

> **module-1을 먼저 두는 이유**: 빌드 스크립트와 애노테이션 프로세서 구성은 **모든 후속 모듈의 컴파일에 영향을 준다.** 또한 생성 컬럼 지원 여부가 §3.6.1의 후퇴 경로를 결정하므로, 엔티티를 다 쓴 뒤 알면 module-4를 다시 써야 한다.

---

## 12. 인용 divergence 목록

| ID | 항목 | 원본 | 본 프로젝트 | 근거 |
|:--:|------|------|-------------|------|
| **D-1** | **boolean 명명** | 필드 `enabled`/`revoked`, 컬럼 동일 | **전 계층 `isEnabled`/`isRevoked`, 컬럼 `is_*`, API JSON `isEnabled`** | **사용자 결정.** ERD 정본 §3이 `is_` 컬럼을 확정했고, 사용자가 자바 필드·DTO·응답까지 통일하도록 지시. `class-enrollment-erd.plan.md` §8.2의 "필드는 접두어 없이" 방침을 뒤집는다 |
| D-2 | 패키지 / 앱 클래스 | `com.toby.jwtauth` / `JwtAuthApplication` | `com.toby.klass` / `KlassApplication` | 프로젝트 정체성 |
| D-3 | QueryDSL | 없음 | `querydsl-jpa:5.1.0:jakarta` + `querydsl-apt:5.1.0:jakarta` + jakarta API 프로세서 | 사용자 지정. R-1 |
| D-4 | 수강 도메인 | 없음 | `Klass`/`Enrollment`/`Waitlist` 3엔티티 | ERD 정본 §3 |
| D-5 | 식별자 문자열 | `sample-jwt-authentication` / `jwtauth` / `jwt-auth` | `klass` | `jwt.issuer`, DB명, **`spring.application.name`**, `settings.gradle` |
| D-6 | 시딩 계정 | **단일 계정 record** | **`List` 구조 + `ROLE_CREATOR` 계정 추가** | ERD 정본 §7 권한 검증 확인용. `DefaultUserProperties`·`DefaultUserInitializer`·`application.yml` 3곳 변경 |
| D-7 | `SecurityUserDetails` | 명시적 `@Override isEnabled()` | 컴포넌트명이 계약과 일치해 **오버라이드 제거** | D-1 파생 |
| **D-8** | 인덱스명 | 원본 `idx_revoked_access_token_id` / ERD `uq_revoked_access_token_id` | **`idx_revoked_access_token_jti`** | 원본과 ERD의 표기가 갈려 있었다. D-11로 컬럼이 `jti`가 되면서 양쪽을 `..._jti` 로 통일 |
| **D-9** | **생성 컬럼 `STORED`** | ERD 정본 DDL은 `... END) STORED` | **`STORED` 제거** — `BIGINT GENERATED ALWAYS AS (CASE WHEN status <> 'CANCELLED' THEN user_id END)` | **module-1 판정 확정(2026-09-01).** H2 2.x가 `STORED`를 문법 오류로 거부한다(`[*]STORED`). 제거하면 값 계산과 UNIQUE 차단이 모두 정상 동작하므로 §3.6.1의 애플리케이션 갱신 후퇴는 **불필요**. ⚠️ **실 DB(MySQL/PostgreSQL) 전환 시 `STORED`를 되붙여야 한다** — 없으면 매 조회마다 재계산되는 가상 컬럼이 된다 |
| **D-10** | **Boot 4 테스트 어노테이션 패키지** | `org.springframework.boot.test.autoconfigure.*` | `org.springframework.boot.{data.jpa,webmvc,restdocs}.test.autoconfigure.*` | Boot 4가 테스트 자동설정을 모듈별로 재배치했다. ⚠️ **실제로는 작업이 불필요했다** — 원본 테스트가 이미 Boot 4 형태였다. 새로 쓰는 테스트에서만 주의하면 된다 |
| **D-13** | **수강 도메인 연관관계** | ERD §3.1.1: `Long` 값 참조 + `@ManyToOne` 미사용 | **`@ManyToOne(fetch = LAZY)`** | **사용자 결정(2026-09-01).** Check 단계에서 FK 5개가 DDL 에 생성되지 않은 것이 발견됐다(G-1) — 값 참조만으로는 Hibernate 가 FK 를 만들지 않는데 ERD §3.1.1 은 FK 부착을 요구했다. `@ManyToOne` 이 FK 를 자동 생성해 그 요구를 충족한다. **인증 2개 테이블은 값 참조 유지** (고아 행 피해가 자기 완결적이라는 ERD 근거가 별도로 있음). 대가: 목록 조회 시 fetch join 을 명시하지 않으면 N+1 |
| **D-12** | **엔티티 접근자 규약** | record 스타일 수동 접근자 (`user.id()`), Lombok 미사용 | **Lombok `@Getter`** (`user.getId()`) | **사용자 결정(2026-09-01).** 원본은 DTO(record)와 표기를 맞추려 손으로 썼으나, JavaBeans 규약이 Java 생태계의 일반 관행이고 MapStruct·Thymeleaf·Spring Data projection 등 getter 를 전제하는 도구의 도입 여지를 남긴다. 신규 인력의 기대와도 맞다. 접근자 47개 제거(약 200줄 감소). ⚠️ **`User.roles` 는 `@Getter(AccessLevel.NONE)` 로 제외** — 불변 뷰를 돌려주는 방어적 복사가 Lombok getter 로 대체되면 내부 `LinkedHashSet` 이 그대로 노출된다 |
| **D-11** | **`tokenId` → `jti`** | 필드·컬럼·포트 전부 `tokenId` / `token_id` | **전 계층 `jti`**, 컬럼 `jti`, 인덱스 `idx_revoked_access_token_jti` | **사용자 지적(2026-09-01).** 담기는 값은 JWT의 `jti` 클레임인데 `tokenId`는 "토큰 값 자체"로 오해된다. 원본조차 주석은 전부 "jti"라고 설명해 **이름과 설명이 어긋나 있었다.** RFC 7519 표준 용어로 통일했다. ERD 정본도 함께 갱신(v1.10) |

### D-1 적용 체크리스트

**컴파일러가 잡는 것**
- [ ] `User.java` — 필드·생성자·`verifyEnabled()`·접근자
- [ ] `RefreshToken.java` — 필드·생성자·`rotate()`·접근자
- [ ] `SecurityUserDetails.java` — record 컴포넌트 + 오버라이드 제거 (D-7)
- [ ] `UserResult.java`, `UserResponse.java` — 컴포넌트명
- [ ] `AuthService`, `RefreshTokenBreachHandler` — 호출부

**⚠️ 컴파일러가 잡지 못하는 것**
- [ ] **`RefreshTokenJpaRepository`** — `@Query` JPQL의 `r.revoked` **2회**. 놓치면 **앱이 기동하지 않는다**
- [ ] `UserTest` — `getDeclaredField("enabled")` 문자열
- [ ] `SpringSecurityCredentialsAdapterTest` — `setField(user, "enabled", ...)` 문자열
- [ ] `UserControllerTest` — RestDocs `fieldWithPath("data.enabled")`

**최종 확인** (단어 경계 기반 — `.revoked()` 패턴만으로는 JPQL을 놓친다)
```bash
grep -rnE '\benabled\b|\brevoked\b' src/ | grep -v isEnabled | grep -v isRevoked
```

### D-2 적용 체크리스트

- [ ] 전 소스의 패키지 선언·import
- [ ] `build.gradle` — `com.toby.jwtauth.integration.DocumentationIntegrationTest` 문자열 **3곳**(`test` 제외 필터, `documentationTest` 필터)
- [ ] `build.gradle` — `openapi3 { title, description }`
- [ ] `settings.gradle` — `rootProject.name`

---

## 13. 다음 단계

1. [ ] 본 설계서 리뷰
2. [ ] `/pdca do project-setup --scope module-1,module-2` — **스파이크 3종 판정부터**
3. [ ] 판정 결과에 따라 §3.6.1 후퇴 경로 적용 여부 확정 (D-9 갱신)

---

## Version History

| 버전 | 날짜 | 변경 내용 | 작성자 |
|------|------|-----------|--------|
| 0.5 | 2026-09-01 | D-13 추가 — 수강 도메인을 `@ManyToOne(LAZY)` 로 전환해 Check 단계 Critical(G-1: FK 5개 미생성)을 해소. §3.5 FK 표를 실제 제약명으로 갱신하고 N+1 주의를 명시. FK 검증 테스트 2건 신설 | developer2@lulumedic.com |
| 0.4 | 2026-09-01 | D-12 추가 — 엔티티 접근자를 Lombok `@Getter` 로 전환(6개 엔티티, 접근자 47개 제거). `User.roles` 는 방어적 복사 보존을 위해 `AccessLevel.NONE` 으로 제외. §10.1 명명 규칙 갱신 | developer2@lulumedic.com |
| 0.3 | 2026-09-01 | D-11 추가 — 사용자 지적으로 `tokenId` → `jti` 전 계층 통일(엔티티·컬럼·포트·principal·파생 쿼리·인덱스). ERD 정본도 v1.10 으로 함께 갱신. D-10 은 실제로 작업이 불필요했음을 기록 | developer2@lulumedic.com |
| 0.2 | 2026-09-01 | **design-validator 검증 반영.** Critical 3건: ① D-1 체크리스트에 `RefreshTokenJpaRepository` JPQL 누락(놓치면 기동 실패) + 안전망 grep을 단어 경계로 확장, ② `@Table(check=)` 오표기 정정(Hibernate `@Check`) 및 ERD의 생성 컬럼 검증 지시를 §3.6.1·module-1로 승계, ③ 로그인 실패 응답이 §4.2↔§6.1 모순 + 원본 실동작과 불일치했던 것 정정. Important: 제약 표 ERD 전수 대응 및 `ck_klass_capacity`↔`ck_klass_count` 이름 충돌 해소, 인덱스 4건 추가·명칭 통일(D-8), 에러 코드 8→17종, L4 시나리오 표·FR 추적표(§8.6) 신설, 문서 파이프라인 소유권 정리(module-2 스모크 테스트), module-1에 Lombok×apt 명시, `DomainAuthenticationProvider`·`SchedulingConfig` 배치 명시, D-6 프로퍼티 구조 변경 기록. 인용 좌표 4곳 정정(ERD design §7.2/§8.2 → plan §7.2/§8.2). divergence 7→9건 | developer2@lulumedic.com |
| 0.1 | 2026-09-01 | 최초 작성. Option C 선택, boolean `is` 전 계층 통일(D-1), 모듈 5개 분해 | developer2@lulumedic.com |
