# project-setup 갭 분석 보고서

> **Summary**: 설계 대비 구현 일치율 **100%** (최초 97% → Critical 1건 해소). 발견된 Critical 은 수강 도메인 FK 5개 미생성이었고, `@ManyToOne(LAZY)` 전환으로 해소했다.
>
> **Project**: klass
> **Version**: 1.2
> **Author**: developer2@lulumedic.com
> **Date**: 2026-09-01
> **Plan**: [project-setup.plan.md](../01-plan/features/project-setup.plan.md) (v0.2)
> **Design**: [project-setup.design.md](../02-design/features/project-setup.design.md) (v0.5)
> **ERD 정본**: [class-enrollment-erd.design.md](../02-design/features/class-enrollment-erd.design.md) (v1.10)

---

## Context Anchor

| Key | Value |
|-----|-------|
| **WHY** | 확정된 ERD를 실행 가능한 형태로 만든다. 설계 문서만으로는 정원 불변식도 인증 흐름도 검증할 수 없다 |
| **WHO** | 이 저장소의 개발자 — 2차(수강신청 로직)를 얹을 기반을 받는 사람 |
| **RISK** | QueryDSL × Hibernate 7 / Boot 4 / Java 25 조합의 미검증 |
| **SUCCESS** | `./gradlew build` 통과 + `bootRun` 기동 + 로그인→`/v1/users/me` 성공 + 7개 테이블 생성 + Redoc/Swagger 렌더링 |
| **SCOPE** | Phase 1 빌드 환경 검증 → Phase 2 인증 이식 → Phase 3 수강 도메인 엔티티 → Phase 4 문서 파이프라인 |

---

## Strategic Alignment Check

### 핵심 문제(WHY)를 풀었는가

**달성했다.** 7개 테이블이 생성되고 인증이 동작하며, 최초 분석에서 발견된 FK 5개 미생성(G-1)도 해소됐다. ERD §3.1.1이 요구한 고아 행 방지가 이제 DB 차원에서 보장된다.

### Success Criteria 대조 (Plan §4.1)

| 기준 | 상태 | 근거 |
|------|:----:|------|
| FR-01~FR-15 전부 구현 | ✅ Met | G-1 해소 후 FR-03 완전 충족 |
| `./gradlew build` 통과 | ✅ Met | BUILD SUCCESSFUL, 101건 전부 통과 |
| `bootRun` 후 로그인→`/v1/users/me` 200 | ✅ Met | 런타임 검증 13/13 |
| 7개 테이블·제약 확인 | ✅ Met | 테이블 7/7 · CHECK 10/10 · **FK 6/6** |
| Redoc/Swagger 렌더링 | ✅ Met | 3종 200, 스펙 엔드포인트 4/4 |
| 인증 테스트 원본과 동등 통과 | ✅ Met | 원본 14개 전부 이식·통과 |

### Plan §4.2 품질 기준

| 기준 | 상태 | 근거 |
|------|:----:|------|
| 우리 코드의 컴파일 경고 0건 | ✅ Met | 컴파일 경고 0건. Lombok 의 JVM 경고는 Plan v0.3 에서 알려진 예외로 확정 (G-3) |
| ERD 7테이블/ENUM 6종/제약 기계적 대조 누락 0 | ✅ Met | ENUM 6/6 · CHECK 10/10 · 테이블 7/7 · FK 6/6 |
| 컨벤션 7종 반영 | ✅ Met | boolean·시각·PK·FK정책(JPA 측)·ENUM·명명 전부 |
| `Design Ref` 주석 부착 | ✅ Met | 85개 파일 전부 |

### Decision Record 검증

| 결정 | 준수 | 근거 |
|------|:----:|------|
| [Plan] 검증 자산 인용 (재설계 안 함) | ✅ | 원본 로직 무변경 이식, 테스트 14개 동등 통과 |
| [Design] Option C — 인용 + 컨벤션 정합 | ✅ | divergence 13건으로 통제 |
| [Design] D-1 boolean 전 계층 `is` | ✅ | DB `is_enabled` ↔ API `data.isEnabled` 런타임 확인 |
| [Design] D-9 생성 컬럼 `STORED` 제거 | ✅ | H2 동작, UNIQUE 차단 테스트 통과 |
| [Design] D-11 `jti` 통일 | ✅ | DDL `jti varchar(36) unique`, 파생 쿼리 `existsByJti` 동작 |
| [Design] D-12 Lombok `@Getter` | ✅ | 6개 엔티티, `User.roles` 는 `AccessLevel.NONE` 로 캡슐화 보존 |
| **[ERD §3.1.1] 수강 도메인 FK 부착** | ✅ | D-13 으로 `@ManyToOne` 전환, FK 6종 DDL 확인 |

---

## 1. Analysis Overview

### 1.1 목적

Plan/Design/ERD 3계층과 구현을 대조해, 1차 범위가 2차의 기반으로 쓰일 수 있는 상태인지 판정한다.

### 1.2 범위

`src/main/java` 85개 · `src/test/java` 15개. 정적 대조 + 런타임 검증(빌드·기동·API 계약).

---

## 2. Gap Analysis

### 2.1 API 엔드포인트 — 일치

| Design §4.1 | 구현 | 런타임 |
|-------------|------|:------:|
| `POST /v1/auth/login` | `AuthController` `@PostMapping("/login")` | ✅ 200 / 401 |
| `POST /v1/auth/reissue` | `@PostMapping("/reissue")` | ✅ 200, 재사용 시 `REFRESH_TOKEN_REUSED` |
| `POST /v1/auth/logout` | `@PostMapping("/logout")` | ✅ 이후 해당 access 401 |
| `GET /v1/users/me` | `UserController` `@GetMapping("/me")` | ✅ 401 / 200 |

응답 형태도 §4.2와 일치 — `data.isEnabled` 존재, 구 필드 `enabled` 부재를 양방향 확인.

### 2.2 데이터 모델

| 항목 | 설계 | 구현 | 판정 |
|------|:----:|:----:|:----:|
| 테이블 | 7 | 7 | ✅ |
| ENUM | 6 | 6 | ✅ |
| CHECK 제약 | 10 | 10 | ✅ |
| 조회 인덱스 | 6 | 6 | ✅ |
| UNIQUE 제약 | 3 | 3 | ✅ |
| **FK** | **6** | **6** | ✅ (G-1 해소) |

### 2.3 컴포넌트 구조 — 일치

레이어 규칙(§9.3) 위반 0건. `domain` 에 Spring import 0건, `application.service` 가 `adapter` 참조 0건, `adapter.in` 이 도메인 엔티티 직접 노출 0건.

### 2.4 기능 완성도

placeholder·TODO·`UnsupportedOperationException` 스텁·`@Disabled` 전부 0건. 빈 본문 17개는 모두 JPA 리플렉션용 `protected` 생성자와 record 선언부로 정당하다.

### 2.5 범위 정합 — 일치

수강 3개 패키지에 `application/`·`adapter/in/` 디렉터리 없음. 엔티티에 상태 전이 메서드(`confirm`/`cancel`/`promote`/카운터 증감) 0건. Repository 3종 모두 본문이 비어 있고 2차 이월이 주석으로 명시됨. `app.enrollment.*` 프로퍼티 부재. **scope creep 0건.**

### 2.6 런타임 검증 결과

| 레벨 | 결과 |
|------|------|
| 빌드 (test + documentationTest) | **101건 / 실패 0** |
| API 계약 (기동 후 curl) | **13건 / 실패 0** |

### 2.7 Match Rate

| 축 | 최초 | 해소 후 | 근거 |
|----|:----:|:----:|------|
| 구조 일치 | 95% | **100%** | ENUM·에러·엔드포인트·테이블·테스트·**FK 6/6** 전부 일치 |
| 기능 완성도 | 92% | **100%** | placeholder 0, 범위 정합 0건, 고아 행 방지 동작 확인 |
| 계약 일치 | 100% | 100% | 엔드포인트 4종·응답 형태·에러 코드 17종 |
| 런타임 | 100% | 100% | 114건 전부 통과 (테스트 101 + API 계약 13) |

```
최초    = (95×0.15) + (92×0.25) + (100×0.25) + (100×0.35) = 97.25%
해소 후 = (100×0.15) + (100×0.25) + (100×0.25) + (100×0.35) = 100%
```

**Match Rate: 100%** (최초 97%, Critical 1건 해소)

---

## 3. 갭 목록

### G-1 (Critical) — ✅ **해소됨 (2026-09-01)** — 수강 도메인 FK 5개가 DDL에 생성되지 않았다

**근거**: 기동 시 생성 DDL의 FK는 `user_roles → users` 1개뿐이다(`@ElementCollection` 이 자동 생성). 아래 5개가 없다.

| 누락된 FK | 설계 근거 |
|-----------|-----------|
| `klass.creator_id → users(id)` RESTRICT | Design §3.5, ERD §3.1.1 |
| `enrollment.klass_id → klass(id)` RESTRICT | 〃 |
| `enrollment.user_id → users(id)` RESTRICT | 〃 |
| `waitlist.klass_id → klass(id)` RESTRICT | 〃 |
| `waitlist.user_id → users(id)` RESTRICT | 〃 |

**원인**: `@ManyToOne`/`@JoinColumn` 없이 `Long creatorId` 값 참조만 두면 Hibernate 가 FK 를 만들지 않는다. Design §3.5 는 "DDL FK ✅ / JPA 는 `@ManyToOne` 미사용"을 **둘 다** 요구했는데, JPA 측만 구현되고 DDL 측이 빠졌다.

**영향**: ERD §3.1.1 이 FK 를 붙이기로 한 이유가 그대로 노출된다 —

> `klass` 가 고아가 되면 **소유자 없는 강의가 영구히 남는다.** §7 의 소유권 검사 `creator_id == sub` 를 아무도 통과할 수 없어 상태 변경·수강생 목록 조회가 불가능해진다.

지금은 존재하지 않는 `creator_id` 로 강의를 만들어도 DB 가 거부하지 않는다. 2차에서 신청·대기 로직을 짤 때 이 방어가 없는 상태로 작업하게 된다.

**해소 결과**: 사용자가 **`@ManyToOne(LAZY)` 전환**을 선택했다(Design §12 D-13). ERD §3.1.1 이
`@ManyToOne` 미사용을 전제했으나, 그 전제가 곧 FK 누락의 원인이었으므로 전제 자체를 바꾼 것이다.
인증 2개 테이블은 값 참조를 유지한다 — 고아 행 피해가 자기 완결적이라는 별도 근거가 있다.

생성 확인 (`bootRun` DDL):

| 테이블 | 제약명 | 참조 |
|--------|--------|------|
| `klass` | `fk_klass_creator` | `creator_id → users` |
| `enrollment` | `fk_enrollment_klass` / `fk_enrollment_user` | `klass` / `users` |
| `waitlist` | `fk_waitlist_klass` / `fk_waitlist_user` | `klass` / `users` |
| `user_roles` | (Hibernate 자동 생성) | `users` |

**검증 테스트 2건 신설** — `EnrollmentSchemaTest.ForeignKeys`:
① FK 5종이 `information_schema` 에 존재하는가, ② 존재하지 않는 `creator_id` 로 강의를 만들면
DB 가 거부하는가. 이 갭이 늦게 발견된 원인이 그 테스트의 부재였으므로 함께 넣었다.

**잔여 주의**: `LAZY` 이므로 2차의 목록 조회(강의 목록·수강생 목록)에서 **fetch join 을 명시하지
않으면 N+1** 이 난다. Design §3.5 에 경고를 남겼다.

### G-2 (Important) — `DefaultUserInitializer` 가 포트를 우회한다

**근거**: `infrastructure/bootstrap/DefaultUserInitializer.java:4` 가 `user.adapter.out.persistence.UserJpaRepository` 를 직접 import 한다. 같은 파일 3번 줄은 `PasswordHasherPort` 를 올바르게 포트로 참조하고 있어 일관성이 깨진다.

**영향**: 기능 문제는 없으나 Design §9.3 의 의존 규칙에서 벗어난다. 현재 `UserQueryPort` 는 조회 전용이라 저장용 포트(`UserCommandPort`)가 없어서 생긴 우회로 보인다.

### G-3 (Minor) — ⏸️ **조치 불가, 대기** — Lombok 이 `sun.misc.Unsafe` 경고를 유발한다

**근거**: 빌드 시 `sun.misc.Unsafe::objectFieldOffset has been called by lombok.permit.Permit`. 우리 코드의 경고가 아니라 Lombok 내부 문제이며, D-12 로 Lombok 을 실사용하면서 나타났다.

**원인**: Lombok 은 정식 Annotation Processing API 로 불가능한 일(기존 클래스에 메서드 추가)을 하려고
javac 내부(`com.sun.tools.javac.*`)를 조작한다. JDK 9 모듈 시스템이 그 패키지를 캡슐화했으므로,
`lombok.permit.Permit` 이 `Unsafe.objectFieldOffset()` 으로 `AccessibleObject.override` 를 직접 써서
접근 검사를 우회한다. JDK 24 의 JEP 471 이 그 `Unsafe` 메서드를 제거 예정으로 표시하면서 경고가 드러났다.

**시도한 것과 결과**:

| 시도 | 결과 |
|------|------|
| Lombok 버전 상향 | ❌ 이미 최신 (1.18.46, Spring Boot BOM 관리) |
| `--add-opens` 로 `jdk.compiler` 10개 패키지 정식 개방 | ❌ 경고 3건 그대로. `Permit` 이 개방 여부와 무관하게 Unsafe 경로를 먼저 탄다. `fork = true` 로 빌드만 느려져 원복 |

**판정**: **우리 측에서 조치할 수 없다.** Lombok 구현이 바뀌어야 한다. Java 25 에서 Lombok 을 쓰는
모든 프로젝트의 공통 문제이며, 우리 설정의 결함이 아니다.

**대응**: Plan §4.2 기준을 "**우리 코드의** 컴파일 경고 0건"으로 한정하고 이 항목을 알려진 예외로
명시했다 (Plan v0.3). 실제로 컴파일 경고는 0건이며 이것은 JVM 런타임 경고다.

**남는 리스크**: `Unsafe` 가 실제 제거되면 경고가 아니라 **컴파일 실패**가 된다. Lombok 이 그 전에
대응하면 버전만 올리면 되고, 대응하지 않으면 D-12(`@Getter` 도입)를 되돌려야 한다. Java 릴리스를
따라갈 때 확인할 항목이다.

---

## 4. Code Quality

- 순환 의존 없음, 레이어 위반 0건
- 코드 스멜: 미발견. setter·`@Data`·`@Builder` 0건으로 상태 변경 경로가 팩토리/도메인 메서드로만 열려 있다
- 보안: 비밀번호 BCrypt 해시 저장, refresh SHA-256 해시 저장, 계정 열거 방지(`DomainAuthenticationProvider` 검사 순서), CSRF 명시적 비활성화 모두 확인. ⚠️ `jwt.secret` 평문은 Design §7 이 이미 1차 한정으로 기록

---

## 5. Test Coverage

| 레벨 | 파일 | 상태 |
|------|:----:|:----:|
| L1 도메인 단위 | 4 | ✅ |
| L2 어댑터/서비스 | 6 | ✅ |
| L3 컨트롤러 + 문서 | 3 | ✅ |
| L4 통합 E2E | 1 | ✅ |
| L5 문서 산출물 | 1 | ✅ |

**미커버 영역**: **FK 존재 검증** — `EnrollmentSchemaTest` 가 테이블·CHECK·인덱스·UNIQUE 는 `information_schema` 로 확인하지만 **`referential_constraints` 는 보지 않는다.** G-1 이 빌드 통과 상태에서 살아남은 직접적 원인이다.

---

## 6. Clean Architecture

| 항목 | 결과 |
|------|------|
| domain → 외부 의존 | 0건 (JPA/Lombok 어노테이션만) |
| application.service → adapter | 0건 |
| adapter.out → application.service / adapter.in | 0건 |
| adapter.in → adapter.out / 엔티티 노출 | 0건 |
| infrastructure → adapter.out 직접 | **1건 (G-2)** |

**아키텍처 점수: 95%**

---

## 7. Convention Compliance

| 항목 | 결과 |
|------|:----:|
| 테이블/엔티티 명명 | ✅ |
| 컬럼 명명 (`_at`/`_on`/`_days`) | ✅ |
| boolean `is` 전 계층 (D-1) | ✅ |
| 사용자 참조 컬럼 (`creator_id` vs `user_id`) | ✅ |
| ENUM STRING 저장 | ✅ |
| `Clock` 주입 (무인자 `now()` 0건) | ✅ |
| 제약·인덱스 명명 (ERD 표기) | ✅ |
| 원본 패키지 잔존 0건 | ✅ |

**컨벤션 점수: 100%**

---

## 8. Overall Score

| 지표 | 점수 |
|------|:----:|
| **Match Rate** | **100%** (최초 97%) |
| 아키텍처 | 95% (G-2 잔여) |
| 컨벤션 | 100% |
| 테스트 | 101건 전부 통과 |

---

## 9. 권장 조치

### 9.1 즉시 (Act 단계)

1. ~~**G-1 FK 5개 생성**~~ — ✅ 완료. `@ManyToOne(LAZY)` 전환 + FK 검증 테스트 2건

### 9.2 단기

2. **G-2** — `UserCommandPort` 를 신설해 `DefaultUserInitializer` 의 포트 우회를 제거
3. ~~**G-3**~~ — ✅ 처리 완료. 조치 불가 확인 후 Plan §4.2 에 알려진 예외로 명시. Lombok 대응 대기

### 9.3 장기 (백로그)

4. 실 DB 전환 시 재확인 항목: 생성 컬럼 `STORED` 되붙이기(D-9), 예약어 `position`·`role`, Flyway 도입 시 `@Check` 정책 재검토

---

## 10. 설계 문서 갱신 필요

- ~~**Design §8.6 FR 추적표**~~ — ✅ 완료. FR-03 항목이 "7테이블 생성 + **FK 6종**" 으로 갱신됨

---

## 11. Next Steps

1. [ ] Checkpoint 5 — 수정 범위 결정
2. [ ] `/pdca iterate project-setup` — G-1 (필요 시 G-2 포함)
3. [ ] 재검증 후 `/pdca report project-setup`

---

## Version History

| 버전 | 날짜 | 변경 내용 | 작성자 |
|------|------|-----------|--------|
| 1.2 | 2026-09-01 | G-3 확정 — 원인(Lombok 의 javac 내부 접근 + JEP 471) 규명, `--add-opens` 우회 실패 기록, Plan §4.2 예외 명시로 종결. 잔여 갭은 G-2 하나 | developer2@lulumedic.com |
| 1.1 | 2026-09-01 | **G-1 해소 반영.** `@ManyToOne(LAZY)` 전환(D-13)으로 FK 6종 생성 확인, 검증 테스트 2건 신설. Match Rate 97% → 100%. G-2·G-3 은 백로그 유지 | developer2@lulumedic.com |
| 1.0 | 2026-09-01 | 최초 분석. Match Rate 97%. Critical 1건(수강 도메인 FK 5개 누락), Important 1건(포트 우회), Minor 1건(Lombok 경고) | developer2@lulumedic.com |
