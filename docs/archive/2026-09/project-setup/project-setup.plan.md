# 프로젝트 기본 세팅 계획서 (project-setup)

> **Summary**: 확정된 ERD를 실행 가능한 Spring Boot 4 프로젝트로 세운다. 빌드 환경·7개 엔티티·JWT 인증·OpenAPI 산출물까지가 1차 범위다.
>
> **Project**: klass (greenfield)
> **Version**: 0.3
> **Author**: developer2@lulumedic.com
> **Date**: 2026-09-01
> **Status**: Draft
> **선행 설계**: [class-enrollment-erd.design.md](../../../02-design/features/class-enrollment-erd.design.md) (v1.9)
> **인증 원본**: https://github.com/Chals85/sample-jwt-authentication

---

## Executive Summary

| 관점 | 내용 |
|------|------|
| **Problem** | ERD는 v1.9까지 확정됐지만 저장소에 코드가 한 줄도 없다. Design §1.3이 "코드 구조·레이어 배치는 구현 결정 사항"으로 미뤄둔 빈칸이 그대로 남아 있어, 누구도 설계를 실행해 볼 수 없다 |
| **Solution** | 검증된 자산(`sample-jwt-authentication`, 일치율 97.1%)의 빌드 환경과 인증 도메인을 인용하고, 그 위에 Design §3의 7개 엔티티를 얹는다. 수강신청 비즈니스 로직은 2차로 분리한다 |
| **Function/UX Effect** | `./gradlew bootRun` 한 번으로 기동해 로그인 → 토큰 발급 → 보호 자원 호출이 동작하고, `/docs/api-guide.html`(Redoc)·`/docs/api-test.html`(Swagger UI)로 API를 눈으로 확인할 수 있다 |
| **Core Value** | **설계와 코드가 처음으로 만나는 지점을 만든다.** 이후 모든 기능은 검증된 뼈대 위에 얹히므로, 2차의 동시성 규약 구현이 환경 문제와 뒤엉키지 않는다 |

---

## Context Anchor

> 선행 ERD 설계서의 Context Anchor를 승계하되, 본 문서의 범위(기반 구축)에 맞게 조정했다.

| Key | Value |
|-----|-------|
| **WHY** | 확정된 ERD를 실행 가능한 형태로 만든다. 설계 문서만으로는 정원 불변식도 인증 흐름도 검증할 수 없다 |
| **WHO** | 이 저장소의 개발자 — 2차(수강신청 로직)를 얹을 기반을 받는 사람 |
| **RISK** | **QueryDSL × Hibernate 7 / Boot 4 / Java 25 조합의 미검증.** 빌드 자체가 서지 않으면 이후 전부가 막힌다 |
| **SUCCESS** | `./gradlew build` 통과 + `bootRun` 기동 + 로그인→`/v1/users/me` 성공 + 7개 테이블 생성 + Redoc/Swagger 렌더링 |
| **SCOPE** | Phase 1 빌드 환경 검증(스파이크 3종) → Phase 2 인증 이식 → Phase 3 수강 도메인 엔티티 → Phase 4 문서 파이프라인 |

---

## 1. Overview

### 1.1 Purpose

`class-enrollment-erd` 설계서(v1.9)가 확정한 데이터 모델을 동작하는 Spring Boot 애플리케이션으로
구현한다. 본 문서의 목표는 **기능 완성이 아니라 기반 확립**이다 — 이후 기능은 이 뼈대 위에 얹는다.

### 1.2 Background

Design §1.3은 아래를 명시적으로 범위 외로 두었다.

> 코드 파일 구조 / 레이어 배치 (템플릿 §9, §11) — 구현 결정 사항. ERD 확정 후 별도 판단
> API 엔드포인트 명세 (템플릿 §4) — 스키마가 확정되어야 도출 가능

그리고 §10 다음 단계 3번이 그 판단을 요구한다: *"구현 진입 시 별도 판단: 코드 구조(레이어 배치),
API 명세, 회원가입 API 포함 여부."* 본 문서가 그 판단이다.

인증을 새로 설계하지 않는 이유는 Plan §7.2에 이미 확정되어 있다 — 참고 저장소의 토큰 회전·재사용
감지·블랙리스트가 검증되어 있어 **재설계 이익이 없다.**

### 1.3 Related Documents

- 선행 계획: `docs/01-plan/features/class-enrollment-erd.plan.md`
- 선행 설계: `docs/02-design/features/class-enrollment-erd.design.md` (v1.9, 정본)
- 인증·환경 원본: `~/Work/sample-jwt-authentication` (`Chals85/sample-jwt-authentication`)

---

## 2. Scope

### 2.1 In Scope

- [ ] Gradle 9.7.1 wrapper + Java 25 툴체인 + Spring Boot 4.1.1 빌드 환경
- [ ] QueryDSL 5.1.0 (jakarta) 도입 및 Q클래스 생성 검증
- [ ] 헥사고날 패키지 구조 (`com.toby.klass`) 확립
- [ ] 인증 4개 엔티티 (`users`, `user_roles`, `refresh_token`, `revoked_access_token`)
- [ ] 수강 3개 엔티티 (`klass`, `enrollment`, `waitlist`) — **엔티티 + Repository까지**
- [ ] ENUM 6종 + 제약(CHECK, 부분 유니크 대체 컬럼) + 조회 요건 4종 인덱스
- [ ] JWT 인증 전체 이식 — 로그인/로그아웃/재발급/필터/회전/재사용 감지/블랙리스트
- [ ] `application.yml` — 인증 프로퍼티 6종 승계
- [ ] RestDocs → OpenAPI3 → Redoc + Swagger UI 문서 파이프라인
- [ ] 기본 계정 시딩 (`ROLE_USER` + `ROLE_CREATOR`)

### 2.2 Out of Scope

- **수강신청 비즈니스 로직** — Design §4의 동시성 규약 7종(비관적 락, 카운터 갱신, 대기열 승격,
  PENDING 만료 배치). 2차로 분리한다. 엔티티만 먼저 세워야 환경 문제와 로직 문제가 섞이지 않는다
- **수강 도메인 UseCase / Service / Controller** — 위와 같은 이유
- **`app.enrollment.*` 프로퍼티 4종** — 소비처(만료 배치·취소 기간 검증)가 전부 2차다.
  엔티티만 세우는 이번 범위에서 값만 먼저 넣으면 쓰이지 않는 설정이 남는다
- 회원가입 API — Design §2 ④가 "ERD 범위 외, 시딩으로 검증 가능"으로 확정
- 실 DB 전환 (H2 → MySQL/PostgreSQL), Docker Compose
- 프론트엔드, 결제 연동, 알림 발송
- `EnrollmentStatusHistory` 감사 테이블 — Design §1.3에서 YAGNI로 미채택 확정

---

## 3. Requirements

### 3.1 Functional Requirements

| ID | 요구사항 | 우선순위 | 상태 |
|----|----------|:--------:|------|
| FR-01 | `./gradlew build`가 통과한다 (test + documentationTest 포함) | High | Pending |
| FR-02 | QueryDSL Q클래스가 생성되고 컴파일된다 | High | Pending |
| FR-03 | 7개 테이블이 기동 시 생성된다 (ENUM은 STRING 저장) | High | Pending |
| FR-04 | `CHECK (enrollment_count <= capacity)` 제약이 DDL에 존재한다 | High | Pending |
| FR-05 | 활성 중복 신청/대기를 DB가 차단한다 (부분 유니크 대체 컬럼) | High | Pending |
| FR-06 | `POST /v1/auth/login`이 access + refresh 토큰을 발급한다 | High | Pending |
| FR-07 | `POST /v1/auth/reissue`가 refresh를 회전하고 이전 것을 폐기한다 | High | Pending |
| FR-08 | 폐기된 refresh 재사용 시 해당 사용자 전체 토큰이 무효화된다 | High | Pending |
| FR-09 | `POST /v1/auth/logout`이 access를 블랙리스트에 올린다 | High | Pending |
| FR-10 | `GET /v1/users/me`가 토큰 없이는 401, 유효 토큰으로는 200을 반환한다 | High | Pending |
| FR-11 | 만료된 블랙리스트 항목이 주기적으로 정리된다 | Medium | Pending |
| FR-12 | `/docs/api-guide.html`(Redoc)이 openapi3.json을 렌더링한다 | High | Pending |
| FR-13 | `/docs/api-test.html`(Swagger UI)에서 Try it out으로 호출된다 | High | Pending |
| FR-14 | `ROLE_USER` / `ROLE_CREATOR` 계정이 멱등 시딩된다 | Medium | Pending |
| FR-15 | 조회 요건 4종에 대응하는 인덱스가 생성된다 | Medium | Pending |

### 3.2 Non-Functional Requirements

| 범주 | 기준 | 측정 방법 |
|------|------|-----------|
| 재현성 | 외부 인프라·환경변수 없이 `./gradlew bootRun`만으로 기동 | 클린 클론 후 실행 |
| 테스트 결정성 | 시각 의존 로직이 주입된 `Clock`만 사용 (`now()` 직접 호출 0건) | `grep -rn "LocalDateTime.now()\|LocalDate.now()"` 결과가 `ClockConfig` 외 없음 |
| 문서 정합성 | 문서 없이는 빌드·실행이 불가 (의도된 결합) | `bootJar`가 `generatedDocument`에 의존 |
| 보안 | 비밀번호는 BCrypt 해시로만 저장, 평문이 DB·로그·응답 어디에도 없음 | 코드 검토 + 응답 스키마 확인 |
| 이식 정확성 | 원본 패키지 잔존 0건 | `grep -r "com.toby.jwtauth"` 결과 없음 |

---

## 4. Success Criteria

### 4.1 Definition of Done

- [ ] FR-01 ~ FR-15 전부 구현
- [ ] `./gradlew build` 통과 (문서 검증 태스크 포함)
- [ ] `bootRun` 후 로그인 → 토큰 → `/v1/users/me` 200 확인
- [ ] `/h2-console`에서 7개 테이블과 제약 육안 확인
- [ ] Redoc / Swagger UI 두 페이지 렌더링 확인
- [ ] 인증 도메인 테스트가 원본과 동등하게 통과

### 4.2 Quality Criteria

- [ ] **우리 코드의 컴파일 경고 0건** (annotation processor 경고 포함)
  - ⚠️ 예외: Lombok 1.18.46 이 `sun.misc.Unsafe::objectFieldOffset` 을 호출해 JVM 이 deprecation
    경고를 낸다. Lombok 이 javac 내부 AST 를 조작하려고 모듈 캡슐화를 우회하는 구조에서 나오며,
    **우리 측에서 조치할 수 없다** — 최신 버전이고 `--add-opens` 로도 해소되지 않음을 확인했다.
    Lombok 의 대응을 기다린다 (분석서 G-3)
- [ ] Design §3의 7테이블 / ENUM 6종 / 제약과 기계적으로 대조해 누락 0건
- [ ] `class-enrollment-erd.plan.md` §8.2 컨벤션 7종 전부 코드에 반영
- [ ] 주요 결정 지점에 `Design Ref: §n` 주석 부착 (원본 관례 승계)

---

## 5. Risks and Mitigation

| ID | 리스크 | 영향 | 가능성 | 대응 |
|:--:|--------|:----:|:------:|------|
| **R-1** | **QueryDSL × Hibernate 7 / Boot 4 / Java 25 조합이 공개적으로 검증되지 않았다.** `querydsl-apt`가 Q클래스를 못 만들거나 Lombok과 processor 충돌 가능 | High | Medium | **do 단계 최우선 스파이크로 격리.** 엔티티 1개 + Q클래스 생성만 먼저 확인. 실패 시 ① processor 순서/옵션 조정 → ② Spring Data JPA `Specification` → ③ 2차로 연기. 1차에 복잡한 동적 조회가 없어 연기 비용이 낮다 |
| R-2 | H2가 부분 유니크 인덱스를 지원하지 않음 | Medium | High | Design이 이미 보조 컬럼(`active_user_key` / `waiting_user_key`)으로 우회 설계 — 그대로 구현 |
| R-3 | `ddl-auto: create-drop`에서 CHECK 제약이 누락됨 | High | Medium | Hibernate `@Check` 또는 `schema.sql` 병행 (`@Table`에는 `check` 속성이 없다). **정원 불변식은 DB 제약이 최종 방어선**(Design §1.2)이라 빠뜨리면 설계 원칙이 무너진다 |
| R-4 | 패키지 치환 누락으로 이식이 반만 됨 | Medium | Medium | 이식 후 `grep -r "com.toby.jwtauth"`로 잔존 확인 (NFR) |
| R-5 | Boot 4 분리 모듈 누락 (`resttestclient` 등) | Medium | Low | 원본 `build.gradle` 주석이 이미 경고 — 의존성 목록을 기계적으로 대조 |
| R-6 | Security 7의 CSRF 기본 적용으로 모든 POST가 403 | High | Low | 원본이 `csrf.disable()`을 명시하고 주석으로 이유를 남김 — 그대로 승계 |

---

## 6. Impact Analysis

> greenfield 저장소다. 기존 소비자가 없어 파괴적 변경의 위험이 없다.

### 6.1 Changed Resources

| 리소스 | 유형 | 변경 내용 |
|--------|------|-----------|
| `klass` 저장소 전체 | 신규 | 빈 저장소 → Spring Boot 프로젝트 |
| `docs/**` | 신규 (복사 완료) | `class/docs`의 Plan/Design 복사. 원본 보존 |
| 7개 테이블 | 신규 | 기동 시 `create-drop`으로 생성 |

### 6.2 Current Consumers

없음. 유일한 외부 의존은 **참고 저장소의 단방향 인용**이며, 원본을 수정하지 않는다.

| 리소스 | 관계 | 영향 |
|--------|------|------|
| `sample-jwt-authentication` | 읽기 전용 인용 (복사 후 패키지 치환) | 원본 무변경 |
| `class/docs` | 읽기 전용 복사 | 원본 무변경 |

### 6.3 Verification

- [x] 기존 소비자 없음 확인 (빈 저장소)
- [x] 원본 저장소를 수정하지 않음
- [ ] 이식 후 원본과의 동작 동등성 확인 (인증 테스트 통과)

---

## 7. Architecture Considerations

### 7.1 Project Level Selection

| Level | 특징 | 적합 대상 | 선택 |
|-------|------|-----------|:----:|
| **Starter** | 단순 구조 | 정적 사이트 | ☐ |
| **Dynamic** | 기능 기반 모듈, 백엔드 포함 | 백엔드 있는 웹앱 | ☑ |
| **Enterprise** | 엄격한 레이어 분리, DI | 대규모 트래픽 | ☐ |

선행 Plan §7.1의 판단을 승계한다 — 레이어 분리 수준은 헥사고날이라 Enterprise에 가깝지만
서비스 규모는 Dynamic이다.

### 7.2 Key Architectural Decisions

| 결정 항목 | 선택지 | 선택 | 근거 |
|-----------|--------|------|------|
| 빌드 도구 | Maven / Gradle | **Gradle 9.7.1 (wrapper)** | 원본 승계. **Java 25 툴체인은 Gradle 9.1+ 필요** |
| Java | 21 / 25 | **25** | 사용자 지정. 원본이 이미 25로 동작 |
| 프레임워크 | Boot 3 / **Boot 4.1.1** | **Boot 4.1.1** | 사용자 지정 + 원본 동일. 스타터명 변경 주의(`starter-web` → `starter-webmvc`) |
| DB (1차) | H2 / MySQL / PostgreSQL | **H2 (`MODE=MySQL`, 인메모리)** | 사용자 결정. 외부 인프라 없이 기동해야 1차 검증이 빠르다. 실 DB는 2차 |
| 패키지 / group | — | **`com.toby.klass` / `com.toby`** | 사용자 결정. 원본 group 유지로 이식 시 치환 범위 최소화 |
| 레이어 구조 | 계층형 / **헥사고날** | **헥사고날 (Port↔Adapter)** | 원본 관례 승계. 도메인이 인프라에 의존하지 않아 동시성 규약(2차)을 순수 도메인으로 표현 가능 |
| 동적 쿼리 | JPQL / Criteria / **QueryDSL** | **QueryDSL 5.1.0 (jakarta)** | 사용자 지정. ⚠️ 원본에 없는 신규 요소 — R-1 |
| API 문서 | springdoc 단독 / **RestDocs 정본 + springdoc 보조** | **RestDocs 정본** | 원본 승계. **테스트가 통과해야 문서가 나오므로 문서와 동작이 어긋날 수 없다.** springdoc은 R-1류 사고 시 승격용 안전망 |
| 문서 렌더러 | Swagger만 / **Redoc + Swagger** | **둘 다** | 사용자 지정. Redoc은 읽기용, Swagger UI는 Try it out 호출용 |
| 수강 도메인 1차 깊이 | 전체 / **엔티티+Repository** | **엔티티+Repository** | Design §4 동시성 규약은 별도 집중이 필요하다. 환경 리스크(R-1)와 로직 리스크를 분리한다 |

### 7.3 Clean Architecture Approach

```
com.toby.klass
├── auth/            adapter{in.web{controller,dto}, out.persistence, out.security, out.token}
│                    application{dto, port.in, port.out, service}, domain{, error}
├── user/            동일 구조
├── klass/           ← 신규. 1차는 domain + adapter.out.persistence
├── enrollment/      ← 신규. 동일
├── waitlist/        ← 신규. 동일
├── common/          adapter.in.web{advice, dto}, domain.error
└── infrastructure/  bootstrap, config, security{config, filter, jwt, principal, exception}
```

의존 방향은 항상 **adapter → application(port) → domain**. 도메인은 JPA 외 어떤 프레임워크도
모른다.

---

## 8. Convention Prerequisites

### 8.1 기존 프로젝트 컨벤션

- [ ] `CLAUDE.md` 코딩 컨벤션 섹션 — 없음 (사용자 전역 규칙: 한국어 문서·주석)
- [ ] `docs/01-plan/conventions.md` — 없음
- [x] **참고 저장소의 관례를 사실상의 컨벤션으로 채택** — `build.gradle` 구성, 헥사고날 패키지,
      JavaDoc `Design Ref: §n` 주석 규약
- [x] **`class-enrollment-erd.plan.md` §8.2가 확정한 스키마 컨벤션** — 아래 8.2로 승계

### 8.2 정의/검증할 컨벤션

| 범주 | 현재 | 정의할 내용 | 우선순위 |
|------|------|-------------|:--------:|
| 테이블 명명 | Design 확정 | 단수 snake_case. 복수는 `users`(예약어)·`user_roles`(복합 PK)뿐 | High |
| 엔티티 명명 | Design 확정 | **엔티티명 = 테이블명** → `Klass`/`klass`, `@Table` 매핑 불필요 | High |
| 컬럼 명명 | Design 확정 | 시각 `{동사}_at`, **날짜 `{동사}_on`**, 기간 `{명사}_days`, FK `{entity}_id` | High |
| boolean | Design 확정 | 컬럼은 `is_`/`has_`/`can_` 접두어, **자바 필드는 접두어 없이 + `@Column(name=...)`** (`isIsRevoked()` 회피) | High |
| ENUM 저장 | Design 확정 | `@Enumerated(EnumType.STRING)`. **ordinal 금지** | High |
| 시각 처리 | Design 확정 | 주입된 `Clock`만 사용. `LocalDate.now(clock)` — **인자 없는 `now()` 금지** | High |
| PK | Design 확정 | 전 테이블 `BIGINT IDENTITY` | High |
| FK | Design §3.1.1 | 수강 도메인은 DDL FK 부착, 인증 2개 테이블은 값 참조. **JPA는 전부 `Long`**(`@ManyToOne` 미사용) | High |
| 사용자 참조 컬럼 | Design §3.1.2 | 사용자가 *만든 것*은 `creator_id`, 사용자 *자신의 기록*은 `user_id` | High |
| 문서화 언어 | 전역 규칙 | 주석·문서는 한국어, 식별자는 영어 | High |
| 주석 규약 | 원본 관례 | 주요 결정에 `Design Ref: §n` 부착 | Medium |

### 8.3 필요한 프로퍼티

이번 범위는 **인증 6종 + 시딩**뿐이다. Design §8.3의 수강 프로퍼티 4종은 소비처가 2차라 함께 미룬다.

| 프로퍼티 | 목적 | 값 | 신규 |
|----------|------|-----|:----:|
| `spring.datasource.url` | DB 연결 | `jdbc:h2:mem:klass;MODE=MySQL;DB_CLOSE_DELAY=-1` | ☐ |
| `jwt.issuer` | 토큰 발급자 | `klass` (원본에서 변경) | ☐ |
| `jwt.secret` | HS256 서명 키 | Base64 32바이트 이상. ⚠️ 1차는 평문 기본값, **실서비스 전 환경변수 분리 필수** | ☐ |
| `jwt.access-token-validity` | Access 만료 | `PT30M` | ☐ |
| `jwt.refresh-token-validity` | Refresh 만료 | `P14D` | ☐ |
| `jwt.revoked-token-cleanup-interval` | 블랙리스트 정리 주기 | `PT10M` (미설정 시 테이블 무한 증가) | ☐ |
| `app.default-user.*` | 시딩 계정 | `chals`/`ROLE_USER` + **크리에이터 계정 추가** | ☐ |

> **2차로 미루는 것** (Design §8.3): `app.enrollment.default-cancellation-period-days`,
> `pending-expiry.direct`(`PT30M`) / `.waitlist`(`PT10M`), `pending-expiry-scan-interval`(`PT1M`).
> 값은 Design §2 ⑥에서 이미 확정됐으므로 2차에서 그대로 가져다 쓰면 된다.

### 8.4 Pipeline Integration

| Phase | 상태 | 문서 위치 |
|-------|:----:|-----------|
| Phase 1 (Schema) | ☑ | `docs/02-design/features/class-enrollment-erd.design.md` §3 |
| Phase 2 (Convention) | ☑ | 위 §8.2 (`class-enrollment-erd.plan.md` §8.2 승계) |

스키마와 컨벤션이 선행 문서에 이미 확정되어 있어 별도 파이프라인 문서를 만들지 않는다.

---

## 9. 미결 사항

Design이 열어둔 2건을 **그대로 두고 진입**한다. 1차 요건에 해당 기능이 없다.

| # | 항목 | 판단 |
|:-:|------|------|
| ⑦ | `enrollment.cancel_reason` (사용자 취소 vs 만료 구분) | 보류. 만료율 측정·환불 정책 분기가 생기면 ENUM 1컬럼 추가 (감사 테이블보다 싸다) |
| ⑧ | `refresh_token` 정리 주기 | 보류하되 **2차에서 반드시 확정.** 회전마다 행이 쌓이므로 정리가 없으면 무한 증가한다 |

---

## 10. Next Steps

1. [ ] 본 계획서 리뷰 및 승인
2. [ ] `/pdca design project-setup` — 3가지 아키텍처 옵션 비교 후 모듈 분해, 구현 순서 확정
3. [ ] `/pdca do project-setup --scope module-1` — **R-1 QueryDSL 스파이크부터**

---

## Version History

| 버전 | 날짜 | 변경 내용 | 작성자 |
|------|------|-----------|--------|
| 0.3 | 2026-09-01 | §4.2 품질 기준 정정 — "빌드 경고 0건" 을 "우리 코드의 컴파일 경고 0건" 으로 한정하고 Lombok × Java 25 의 `sun.misc.Unsafe` 경고를 알려진 예외로 명시 (조치 불가 확인) | developer2@lulumedic.com |
| 0.2 | 2026-09-01 | 범위 재확인(사용자). 7개 엔티티 + 인증 처리까지가 이번 Task이고 나머지는 순차 Task로 분리됨을 확정. `app.enrollment.*` 프로퍼티 4종을 2차로 이관 — 소비처(만료 배치·취소 검증)가 모두 2차라 이번 범위에 쓰이지 않는 설정이 남는 문제 | developer2@lulumedic.com |
| 0.1 | 2026-09-01 | 최초 작성. 범위(스캐폴딩+7엔티티+인증), 요구사항 15건, 리스크 6건 정의. DB=H2, 패키지=`com.toby.klass`, 수강 비즈니스 로직 2차 분리 확정 | developer2@lulumedic.com |
