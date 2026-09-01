# 강의 수강신청 + JWT 인증 ERD 설계 계획서

> **Summary**: 강의(Klass) 관리와 수강신청(Enrollment)을 다루는 시스템의 논리 데이터 모델을 설계한다. 정원 초과를 데이터 모델 차원에서 방지하고, 기존 JWT 인증 설계(`sample-jwt-authentication`)의 인증 테이블을 통합한다.
>
> **Project**: class (greenfield)
> **Version**: 0.8.0
> **Author**: developer2@lulumedic.com
> **Date**: 2026-08-31
> **Status**: Draft

---

## Executive Summary

| 관점 | 내용 |
|------|------|
| **Problem** | 강의 신청은 "마지막 한 자리"에 여러 사용자가 동시에 몰리는 구조다. 데이터 모델이 정원을 보장하지 않으면 애플리케이션 로직이 아무리 정교해도 오버부킹이 발생한다. 동시에 "누가 신청했는지"를 신뢰하려면 인증이 선행되어야 하며, 신청·취소는 되돌리기 어려운 행위이므로 토큰 탈취에 대한 방어도 스키마가 지탱해야 한다. |
| **Solution** | 두 도메인을 하나의 논리 ERD로 통합한다. **수강 도메인**은 `Klass`에 비정규화 카운터 `enrollment_count`를 두고 비관적 락으로 정원을 직렬화하며, `Enrollment`는 상태 머신 + 부분 유니크 제약으로 중복 신청을 차단한다. **인증 도메인**은 기존 설계를 그대로 인용해 `users` / `user_roles` / `refresh_token`(회전·재사용 감지) / `revoked_access_token`(로그아웃 즉시 차단)을 도입한다. |
| **Function/UX효과** | 정원 초과 신청이 구조적으로 불가능해지고, 강의 상세 조회 시 신청 인원을 COUNT 쿼리 없이 즉시 반환한다. 로그인 후 발급된 Access 토큰으로 신청·취소가 가능하며, 로그아웃 시 남은 유효 기간 동안의 토큰 재사용이 차단된다. |
| **Core Value** | "정원은 절대 초과되지 않는다"와 "폐기된 토큰으로는 신청할 수 없다"는 두 불변식(invariant)을 애플리케이션 코드가 아니라 데이터 모델과 DB 제약이 보증한다. |

---

## Context Anchor

| Key | Value |
|-----|-------|
| **WHY** | 동시 신청 시 정원 초과를 스키마 차원에서 원천 차단하고, 신청 주체를 인증으로 신뢰할 수 있게 만든다 |
| **WHO** | 수강생(ROLE_USER) — 신청·취소·내 목록 조회 / 크리에이터(ROLE_CREATOR) — 강의 등록·상태 관리·수강생 목록 조회 |
| **RISK** | 비정규화 카운터(`enrollment_count`)와 실제 `Enrollment` 행 수의 정합성 붕괴(drift) |
| **SUCCESS** | 동시 100건이 잔여 1석에 신청 → 정확히 1건 성공, 99건 거부. `enrollment_count` = 좌석 점유 행 수가 항상 일치 |
| **SCOPE** | Phase 1 인증 테이블 통합(users·user_roles·refresh_token·revoked_access_token) → Phase 2 수강 도메인 논리 ERD(Klass·Enrollment) → Phase 3 선택 기능(Waitlist·취소정책) + 인덱스·제약·동시성 검증 |

---

## 1. Overview

### 1.1 Purpose

강의 등록/조회, 수강 신청/확정/취소, 정원 관리 규칙, 그리고 이를 수행할 주체를 식별하는 JWT 인증까지 포함한 **논리 데이터 모델(ERD)** 을 확정한다. 산출물은 Mermaid 논리 ERD + 엔티티 명세 + 제약/인덱스 규칙 + 동시성 처리 규약이며, 각 테이블에 JPA/DDL 대응 표현을 병기한다.

### 1.2 Background

**수강 도메인**의 난이도는 CRUD가 아니라 정원 관리 규칙에 집중되어 있다.

- 단순히 `COUNT(*) < capacity` 를 확인하고 INSERT하면 Read-Modify-Write 사이의 틈에서 두 트랜잭션이 모두 통과한다 (TOCTOU).
- 따라서 정원 검사와 신청 생성은 **동일 트랜잭션 내에서 직렬화**되어야 하며, 무엇을 락의 대상으로 삼을지가 스키마 설계의 결과물이다.
- 본 계획은 `Klass` 행을 락 대상으로 삼고 카운터를 함께 갱신하는 방식을 채택한다.

**인증 도메인**은 새로 설계하지 않는다. 사용자가 선행 설계한 `Chals85/sample-jwt-authentication` (Spring Boot + JPA, 헥사고날 아키텍처)의 데이터 모델을 인용하며, 그 설계의 핵심 판단 3가지를 그대로 승계한다.

1. **Refresh 토큰은 원문이 아닌 SHA-256 해시로 저장** — DB가 유출돼도 저장 값으로 API를 호출할 수 없다.
2. **토큰 회전 + 재사용 감지** — 이미 폐기된 Refresh 토큰의 재사용은 탈취 신호로 간주하고 해당 사용자의 전체 토큰을 무효화한다.
3. **로그아웃 시 Access 토큰 `jti`를 블랙리스트에 등록** — JWT는 무상태라 발급한 토큰을 회수할 수 없으므로, 이 테이블이 그 구멍을 메운다. 대가로 보호된 요청마다 조회가 1회 추가된다.

### 1.3 Related Documents

- **인증 설계 원본**: https://github.com/Chals85/sample-jwt-authentication
  - `docs/01-plan/features/jwt-auth-example.plan.md`
  - `docs/02-design/features/jwt-auth-example.design.md` (§3 Data Model, §3.4 JWT 클레임 구조)
- 요구사항: 사용자 제공 요건 (필수 3항목 + 선택 4항목)
- 다음 산출물: `docs/02-design/features/class-enrollment-erd.design.md`

---

## 2. Scope

### 2.1 In Scope

**수강 도메인 (신규 설계)**
- [ ] 논리 ERD 작성 (Mermaid `erDiagram`) + 테이블별 JPA/DDL 대응 표현 병기
- [ ] 엔티티 정의: `Klass`, `Enrollment`, `Waitlist`, `EnrollmentStatusHistory`(선택)
- [ ] ENUM 정의: `KlassStatus`, `EnrollmentStatus`, `WaitlistStatus`, `EnrollmentSource`
- [ ] 상태 전이 다이어그램 및 허용/금지 전이 표
- [ ] 정원 관리를 위한 동시성 제어 규약 (비관적 락 + 카운터 컬럼)
- [ ] 쿼리 패턴별 인덱스 설계 (상태 필터, 내 신청 목록, 수강생 목록, 커서 페이지네이션)
- [ ] 선택 기능 4종의 스키마 반영: 취소 가능 기간, 대기열, 크리에이터 권한, 페이지네이션

**인증 도메인 (기존 설계 인용 + 확장)**
- [ ] `users`, `user_roles`, `refresh_token`, `revoked_access_token` 테이블을 통합 ERD에 편입
- [ ] `Role` enum에 `ROLE_CREATOR` 추가 (기존 `ROLE_USER`, `ROLE_ADMIN` 유지)
- [ ] 인증 도메인 ↔ 수강 도메인의 참조 방식 및 FK 정책 명문화
- [ ] JWT 클레임(`sub`=userId, `roles`, `typ`, `jti`)과 권한 검증 지점의 매핑

**공통**
- [ ] 제약 조건 목록: PK, FK, UNIQUE(부분 유니크 포함), CHECK
- [ ] 명명·타입 컨벤션을 인증 도메인 기존 관례에 맞춰 통일
- [ ] 동시성 검증 시나리오 정의

### 2.2 Out of Scope

- 실제 마이그레이션 파일/JPA 엔티티 코드 작성 (Do 단계)
- API 엔드포인트 명세, 요청/응답 스키마 (Design 단계)
- **인증 로직 재설계** — 토큰 발급/검증/회전 흐름, Security 필터 체인은 참고 저장소의 것을 그대로 따르며 본 계획은 데이터 모델만 다룬다
- 회원가입 절차 (참고 저장소는 `DefaultUserInitializer`로 시딩. 본 프로젝트에서 가입 API가 필요한지는 Design에서 판단)
- 외부 결제 시스템 연동 (요건상 상태 변경으로 대체)
- 환불 금액 계산, 정산 로직
- 알림(대기열 승격 통보 등) 발송 채널
- 블랙리스트를 Redis로 이전하는 인프라 최적화 (참고 저장소가 이미 트레이드오프로 명시)

---

## 3. Requirements

### 3.1 Functional Requirements

**인증 도메인**

| ID | 요구사항 | 우선순위 | 상태 |
|----|---------|:--------:|:----:|
| FR-A1 | `users` 테이블 편입: `username`(UQ, 50), `password`(BCrypt 해시, 100), `is_enabled`, `created_at` | High | Pending |
| FR-A2 | `user_roles` 테이블 편입: `(user_id, role)` 복합 PK, 다중 권한 보유 지원 | High | Pending |
| FR-A3 | `Role` enum 확장: `ROLE_USER`, `ROLE_ADMIN`, **`ROLE_CREATOR`** 추가 | High | Pending |
| FR-A4 | `refresh_token` 테이블 편입: `token_hash`(SHA-256 hex, UQ, 64), `issued_at`, `expires_at`, `is_revoked`, `revoked_at` | High | Pending |
| FR-A5 | `revoked_access_token` 테이블 편입: `token_id`(jti, UQ, 36), `expires_at`(정리 기준), `revoked_at` | High | Pending |
| FR-A6 | 인증 테이블의 `user_id`는 값 참조(FK 제약 없음) 원칙 유지 및 근거 명문화 | High | Pending |
| FR-A7 | 크리에이터 권한 검증 경로 정의: JWT `roles` 클레임 ∋ `ROLE_CREATOR` **AND** `klass.creator_id` == `sub` | High | Pending |

**수강 도메인**

| ID | 요구사항 | 우선순위 | 상태 |
|----|---------|:--------:|:----:|
| FR-01 | `Klass` 엔티티: 제목, 설명, 가격, 정원, 수강 시작일/종료일 필드 정의 | High | Pending |
| FR-02 | `KlassStatus` ENUM(DRAFT/OPEN/CLOSED) 및 허용 전이 규칙 정의 | High | Pending |
| FR-03 | 강의 목록 조회를 위한 상태 필터 인덱스 설계 | High | Pending |
| FR-04 | 강의 상세 조회 시 현재 신청 인원을 O(1)로 제공하는 카운터 컬럼 설계 | High | Pending |
| FR-05 | `Enrollment` 엔티티: 사용자↔강의 관계, 상태, 신청/확정/취소 시각 | High | Pending |
| FR-06 | `EnrollmentStatus` ENUM(PENDING/CONFIRMED/CANCELLED) 및 전이 규칙 정의 | High | Pending |
| FR-07 | 동일 사용자의 동일 강의 중복 활성 신청 차단 제약 (부분 유니크) | High | Pending |
| FR-08 | 정원 초과 방지: `Klass` 행 비관적 락 + `enrollment_count` 갱신 규약 | High | Pending |
| FR-09 | 카운터 정합성 보증: `CHECK (enrollment_count BETWEEN 0 AND capacity)` 방어 제약 | High | Pending |
| FR-10 | 내 수강 신청 목록 조회용 인덱스 `(user_id, id DESC)` | High | Pending |
| FR-11 | 취소 가능 기간 제한: `confirmed_at` 기록 + `cancellation_period_days` 필드 위치 결정 | Medium | Pending |
| FR-12 | `Waitlist` 엔티티: 강의별 대기 순번, `WaitlistStatus`(WAITING/PROMOTED/CANCELLED), 승격 처리 모델 | Medium | Pending |
| FR-16 | **PENDING 좌석 점유 만료 정책**: 신청 후 기한 내 미결제 시 자동 `CANCELLED` + 카운터 감소 | **High** | Pending |
| FR-17 | `EnrollmentSource` ENUM(`DIRECT`/`WAITLIST`) 및 `enrollment.source` 컬럼 — 출처별 PENDING 만료 기한 차등 적용 | Medium | Pending |
| FR-18 | 대기열 승격 규약: 카운터 감소 시 최상위 `WAITING` 1건을 `PROMOTED`로 전환하고 `enrollment(PENDING, source=WAITLIST)` 생성 | Medium | Pending |
| FR-13 | 크리에이터 전용 조회: `klass.creator_id` + 소유권 검사 규약 | Medium | Pending |
| FR-14 | 커서 페이지네이션을 지원하는 복합 인덱스 및 정렬 키 확정 | Medium | Pending |
| FR-15 | (선택) `EnrollmentStatusHistory` 감사 로그 테이블 필요성 판단 | Low | Pending |

### 3.2 Non-Functional Requirements

| 항목 | 기준 | 측정 방법 |
|------|------|-----------|
| 정확성 | 동시 신청 부하에서 오버부킹 0건 | 잔여 1석에 동시 100 요청 → 성공 1건 검증 |
| 정합성 | `enrollment_count` == 좌석 점유 `Enrollment` 행 수 | 정합성 검증 쿼리 (Design에서 명세) |
| 성능 | 목록/상세 조회에서 전체 스캔 및 `COUNT(*)` 미사용 | 실행 계획(EXPLAIN)에서 인덱스 사용 확인 |
| 성능 | 페이지네이션이 offset 증가에 따라 저하되지 않음 | 커서 방식 채택 여부로 검증 |
| 성능 | 블랙리스트 조회가 매 요청 1회를 넘지 않고 unique 인덱스를 탄다 | `revoked_access_token.token_id` UQ 인덱스 확인 |
| 보안 | 토큰 원문이 DB·로그·응답에 남지 않는다 | `refresh_token`은 해시, 블랙리스트는 `jti`만 저장 |
| 보안 | 폐기된 Refresh 토큰 재사용 시 해당 사용자 전체 토큰 무효화 | `is_revoked` 플래그 + 재사용 감지 경로 검증 |
| 확장성 | 락 경합 범위가 강의 단위로 국한 | 락 대상이 `klass` 단일 행임을 설계로 명시 |
| 이식성 | 논리 모델이 특정 DB 벤더 문법에 비의존 | 논리 ERD 정본 + JPA/DDL 대응 표현 병기 |

---

## 4. Success Criteria

### 4.1 Definition of Done

- [ ] Mermaid ERD가 문서에 포함되고, 수강 도메인 + 인증 도메인이 하나의 다이어그램에 표현됨
- [ ] 7개 테이블(`users`, `user_roles`, `refresh_token`, `revoked_access_token`, `klass`, `enrollment`, `waitlist`)의 모든 필드에 타입·NULL 허용·기본값·의미가 기술됨
- [ ] 필수 구현 3항목이 각각 어떤 엔티티/제약으로 충족되는지 매핑표 존재
- [ ] 선택 구현 4항목이 스키마에 반영되고, 미반영 시 그 이유가 명시됨
- [ ] 상태 전이표(허용/금지)가 `KlassStatus`, `EnrollmentStatus`, `WaitlistStatus` 모두에 대해 작성됨
- [ ] 좌석 반납 → 대기열 승격이 단일 트랜잭션으로 기술되고 카운터 순변화가 0임이 명시됨
- [ ] 정원 관리 동시성 규약이 트랜잭션 순서 의사코드로 기술됨
- [ ] 조회 요건 4종별 인덱스가 각각 지정됨
- [ ] 인증 도메인에서 인용한 부분과 신규 확장한 부분(`ROLE_CREATOR`)이 명확히 구분 표기됨

### 4.2 Quality Criteria

- [ ] 모든 FK에 ON DELETE / ON UPDATE 정책이 명시되고, FK를 걸지 않은 관계는 그 근거가 기술됨
- [ ] 금액 필드가 부동소수점이 아닌 정수/DECIMAL로 정의됨
- [ ] 시각 필드의 타입이 인증 도메인 기존 관례(`LocalDateTime` + 주입된 `Clock`)와 일치함
- [ ] 비정규화 컬럼(`enrollment_count`)에 대해 정합성 복구(reconcile) 전략이 문서화됨
- [ ] 상태 값이 문자열 하드코딩이 아닌 ENUM/도메인으로 정의됨
- [ ] 자바 예약 식별자와 SQL 예약어 충돌이 전 테이블·엔티티에서 점검됨

---

## 5. Risks and Mitigation

| 리스크 | 영향 | 발생 가능성 | 완화 방안 |
|--------|:----:|:-----------:|-----------|
| `enrollment_count`가 실제 좌석 점유 행 수와 어긋남 (drift) | High | Medium | 카운터 갱신을 신청/확정/취소와 **항상 동일 트랜잭션**에 묶는다. `CHECK (enrollment_count BETWEEN 0 AND capacity)` 로 하한·상한 방어. 정합성 검증 쿼리와 재계산 절차를 Design에 포함 |
| **`LocalDateTime`은 시간대 정보가 없다** — 인증 도메인 관례를 따르면 수강 기간·취소 기간(7일) 계산이 서버 시간대에 묶인다 | High | Medium | 일관성을 위해 `LocalDateTime` + `Clock` 을 채택하되, **Design에서 재검토 대상으로 명시**. 수강 기간처럼 날짜 경계가 사업적 의미를 갖는 필드는 `LocalDate` 또는 타임존 인식 타입으로 분리하는 방안을 비교한다 |
| 비관적 락으로 인기 강의에서 락 경합·대기 증가 | Medium | Medium | 락 범위를 `klass` 단일 행으로 한정하고, 락 획득 후 작업을 최소화(검사+카운터+INSERT). 락 타임아웃 정책을 Design에 명시 |
| 트랜잭션 내 락 순서 불일치로 데드락 | Medium | Low | 락 획득 순서를 **항상 Klass → Enrollment → Waitlist** 로 고정하는 규약을 문서화 |
| 취소 후 재신청 시 유니크 제약 충돌 | Medium | High | 단순 `UNIQUE(user_id, klass_id)` 대신 **CANCELLED를 제외한 부분 유니크**로 설계. DB별 대응 표현을 각주로 제공 |
| ~~**`user_id`에 FK가 없어 고아 행 발생**~~ → **Design v1.3에서 해소.** 수강 도메인 3개 테이블에 FK를 부착해 구조적으로 불가능해졌다. 인증 2개 테이블에만 남으며, 그쪽은 만료로 자연 소멸하므로 실질 위험이 낮다 | ~~Medium~~ Low | Low | 사용자 삭제를 물리 삭제로 처리하지 않고 `is_enabled = false` 로만 다룬다. 고아 행 탐지는 인증 2개 테이블만 대상 (Design §5.1) |
| **PENDING 만료 배치와 사용자 결제의 경합** — 결제된 신청이 만료 처리로 취소될 수 있다 | High | Medium | 만료 배치가 `enrollment` 행 락을 획득한 뒤 **상태를 재확인**한다(§7.4 좌석 반납 흐름 3번). 결제 트랜잭션도 동일하게 행 락 후 상태를 재확인한다 |
| **중복 승격** — 여러 좌석이 동시에 반납되면 같은 대기자가 두 번 승격될 수 있다 | Medium | Medium | 승격을 `klass` 락 아래에서만 수행하고 대기 행도 `FOR UPDATE`로 잡는다. `waitlist`에 `(klass_id, user_id) WHERE status = 'WAITING'` 부분 유니크를 걸어 중복 대기 자체를 차단한다 |
| **승격 대상이 이미 신청·비활성 상태** | Low | Medium | 승격 직전 자격 재검증(§7.4 7-a). 부적격이면 해당 대기 행을 건너뛰고 다음 순번으로 이동한다 |
| 대기열 순번을 `MAX(position)+1` 로 계산해 중복 발생 | Medium | Medium | 순번도 `Klass` 락 하위에서 발급하거나, `UNIQUE(klass_id, position)` 로 물리 차단 |
| CLOSED 전환 시점과 진행 중인 PENDING 신청의 처리 모호 | Medium | Medium | "CLOSED는 신규 신청만 차단하며 기존 PENDING의 결제 확정은 허용/차단" 중 어느 쪽인지 Design에서 명문화 (기본안: 기존 PENDING의 확정은 허용) |
| **블랙리스트 조회가 신청 API의 병목이 됨** — 정원 락 대기와 겹치면 지연이 누적된다 | Medium | Medium | `token_id` unique 인덱스를 필수 요구사항으로 고정하고, `RevokedAccessTokenCleaner` 주기 정리를 유지. 부하 시 Redis 이전이 정석임을 Design에 트레이드오프로 기록 |
| 취소 가능 기간(7일)을 하드코딩해 강의별 정책 변경 불가 | Low | Medium | 기간 값을 `klass.cancellation_period_days`(NULL이면 전역 기본값 적용)로 설계 |
| 과설계로 ERD가 비대해져 리뷰 난이도 상승 | Medium | Medium | 감사 로그(FR-15)는 필요성 판단 후 선택 채택. YAGNI 기준을 Design에서 적용 |

---

## 6. Impact Analysis

> 본 프로젝트는 그린필드다. 다만 인증 도메인은 **외부 저장소의 기존 설계를 인용**하므로, 그 설계에 대한 변경은 원본과의 divergence로 관리해야 한다.

### 6.1 Changed Resources

| 리소스 | 유형 | 변경 내용 | 원본 대비 |
|--------|------|-----------|-----------|
| `users` | DB 모델 | 인용 + **컬럼 rename** `enabled` → `is_enabled` | **확장** |
| `user_roles` | DB 모델 | 인용 (변경 없음) | 동일 |
| `Role` | ENUM | **`ROLE_CREATOR` 추가** | **확장** |
| `refresh_token` | DB 모델 | 인용 + **컬럼 rename** `revoked` → `is_revoked` | **확장** |
| `revoked_access_token` | DB 모델 | 인용 (변경 없음) | 동일 |
| `TokenType` | ENUM | 인용 (ACCESS/REFRESH) | 동일 |
| `Klass` / `klass` | DB 모델 | 신규 생성 (+ `enrollment_count`, `creator_id`) | 신규 |
| `Enrollment` | DB 모델 | 신규 생성 (상태 머신 + 부분 유니크) | 신규 |
| `Waitlist` | DB 모델 | 신규 생성 (선택 기능) | 신규 |
| `KlassStatus` / `EnrollmentStatus` / `WaitlistStatus` / `EnrollmentSource` | ENUM | 신규 정의 | 신규 |

### 6.2 Current Consumers

`ROLE_CREATOR` 추가는 기존 `Role` enum의 소비자에 영향을 준다. 참고 저장소를 코드 베이스로 삼는 경우 아래를 확인해야 한다.

| 리소스 | 연산 | 코드 경로 | 영향 |
|--------|------|-----------|------|
| `Role` | READ | `User.roleNames()` → JWT `roles` 클레임 | None (값 추가만) |
| `Role` | READ | `SecurityUserDetails.from(user)` → `GrantedAuthority` 매핑 | None (`ROLE_` 접두어 관례 준수) |
| `Role` | WRITE | `DefaultUserInitializer` / `app.default-user.roles` 프로퍼티 | 검증 필요 — 크리에이터 시딩 계정 추가 시 |
| `users` | READ | `DomainUserDetailsService` (username 기반 조회) | None |
| `refresh_token` | ALL | `RefreshTokenRepositoryAdapter`, `RefreshTokenBreachHandler` | 검증 필요 — `@Column(name="is_revoked")` 매핑 추가 |
| `users` | ALL | `UserRepositoryAdapter`, `SecurityUserDetails` | 검증 필요 — `@Column(name="is_enabled")` 매핑 추가 |
| `revoked_access_token` | ALL | `RevokedAccessTokenRepositoryAdapter`, `RevokedAccessTokenCleaner` | None |
| 수강 도메인 전체 | ALL | 없음 (신규) | None |

### 6.3 Verification

- [x] 수강 도메인에 기존 소비자 없음을 확인
- [ ] `ROLE_CREATOR` 추가가 `hasRole()` 기반 권한 검사와 시딩 설정에 영향 없는지 확인
- [ ] 인증 테이블을 인용할 때 원본과의 divergence 목록을 Design에 기록

---

## 7. Architecture Considerations

### 7.1 Project Level Selection

| Level | 특징 | 적합 대상 | 선택 |
|-------|------|-----------|:----:|
| **Starter** | 단순 구조 | 정적 사이트 | ☐ |
| **Dynamic** | 기능 기반 모듈, 백엔드 포함 | 백엔드 있는 웹앱 | ☑ |
| **Enterprise** | 엄격한 레이어 분리, DI | 대규모 트래픽 | ☐ |

**선택 근거**: 트랜잭션·동시성 제어와 인증이 필요한 백엔드 중심 과제다. 참고 저장소가 헥사고날(Port↔Adapter) 구조를 이미 채택하고 있어 레이어 분리 수준은 Enterprise에 가깝지만, 서비스 규모는 Dynamic이다.

### 7.2 Key Architectural Decisions

| 결정 항목 | 선택지 | 선택 | 근거 |
|-----------|--------|------|------|
| ERD 표현 방식 | 논리 ERD / JPA 전제 / 혼합 | **논리 ERD 정본 + JPA·DDL 대응 표현 병기** | 사용자 결정. DB 중립적으로 설계 의도를 검토하면서, 참고 저장소가 JPA 기반이므로 구현 지재단도 함께 제공 |
| 인증 도메인 | 신규 설계 / 기존 설계 인용 | **`sample-jwt-authentication` 인용** | 사용자 선행 설계 자산 재사용. 토큰 회전·재사용 감지·블랙리스트가 이미 검증되어 있어 재설계 이익이 없다 |
| 강의 엔티티·테이블 명 | Class / Course / Lecture / **Klass** | **엔티티 `Klass`, 테이블 `klass`, FK 컬럼 `klass_id`** | 사용자 결정. 자바에서 `Class`는 `java.lang.Class`와 충돌해 선언 자체가 불가능하다. `Klass`는 도메인 어휘를 유지하면서 식별자 충돌만 우회한다. **엔티티·테이블·FK 컬럼에 같은 이름을 쓴다** — 이름이 갈리면 매핑을 매번 되짚어야 하고, 단수형이라 §8.2의 명명 원칙에도 그대로 부합한다. 대가로 DB 스키마에 자바 우회 흔적이 드러나지만, 한 개념에 한 이름이라는 이익이 더 크다 |
| 동시성 제어 | 비관적 락 / 낙관적 락 / 좌석 유니크 제약 | **비관적 락 + 카운터 컬럼** | 사용자 결정. 구현이 단순하고 정확하며, 락 경합이 강의 단위로 직렬화됨 |
| 현재 신청 인원 | 매번 COUNT / 비정규화 카운터 | **비정규화 `enrollment_count`** | 상세·목록 조회에서 강의당 COUNT 쿼리를 제거. 락 대상 행과 동일해 갱신 비용이 사실상 0 |
| 카운터 집계 기준 | CONFIRMED만 / PENDING+CONFIRMED / +waitlist(OFFERED) | **`enrollment`의 PENDING + CONFIRMED (확정)** | PENDING도 좌석을 점유해야 결제 대기 중 오버부킹이 방지된다. **좌석 점유가 `enrollment` 한 테이블에만 존재하도록 대기열 설계를 맞췄다**(아래 대기열 승격 방식 참조) — 점유가 두 테이블에 분산되면 정합성 검증과 락 대상이 함께 늘어난다 |
| 권한 모델 | 단일 role 컬럼 / 다중 권한 테이블 | **`user_roles` 다중 권한 + `ROLE_CREATOR` 추가** | 사용자 결정. 한 사용자가 수강생과 크리에이터를 동시에 가질 수 있어야 하며, 기존 `@ElementCollection` 구조가 스키마 변경 없이 이를 지원한다 |
| FK 정책 | 전부 FK / 전부 값 참조 / 하이브리드 | **하이브리드**. ⚠️ **Design v1.3에서 정정됨** → `klass ↔ enrollment ↔ waitlist`는 FK, **수강 도메인의 User 참조(`creator_id`, `user_id`)도 FK 부착**, 인증 2개 테이블(`refresh_token`, `revoked_access_token`)만 값 참조 유지 | Plan 시점 결정은 User 참조 전체를 값 참조로 두는 것이었으나, 원본의 no-FK 근거가 ORM 매핑(`@ManyToOne` 회피)에 관한 것이고 DDL FK는 별개 결정임을 확인해 정정했다. **최신 결정은 Design §3.1.1** |
| 중복 신청 차단 | 애플리케이션 검사 / 부분 유니크 제약 | **부분 유니크 제약** | 취소 후 재신청을 허용하면서 활성 중복만 DB가 차단 |
| 대기열 모델 | Enrollment 상태 추가 / 별도 테이블 | **별도 `waitlist` 테이블** | 신청 상태 머신을 오염시키지 않고 순번(position)을 독립 관리 |
| **대기열 승격 방식** | ① enrollment가 좌석 보전 / ② waitlist가 좌석 보전(`OFFERED`) | **① 승격 시 `enrollment(PENDING)` 자동 생성** | 사용자 결정. 좌석 점유가 `enrollment` 한 곳에만 존재해 정원 불변식이 단순하게 유지된다. ②는 `enrollment_count`가 `enrollment` + `waitlist(OFFERED)` 두 테이블에 걸쳐 정합성 검증 쿼리와 락 대상이 늘어난다. 사용자 체감 흐름(알림 → 결제)은 두 방식이 동일하다 |
| **PENDING 만료 기한** | 단일 기한 / 출처별 차등 | **출처별 차등 (`source` ENUM으로 분기)** | 승격 PENDING은 뒷 순번 대기자를 붙잡아두므로 일반 신청보다 짧아야 한다. `is_from_waitlist` boolean보다 `source` ENUM이 향후 분기 확장에 유리하다 |
| 삭제 정책 | 물리 삭제 / 상태 기반 | **상태 기반 (물리 삭제 없음)** | 취소는 `CANCELLED`로, 사용자 비활성은 `is_enabled = false`로 남긴다. 참고 저장소도 물리 삭제가 없다 |
| PK 전략 | UUID / **BIGINT IDENTITY** | **`BIGINT IDENTITY`** | 인증 도메인 관례 준수. 단조 증가라 커서 페이지네이션 정렬 키로도 안정적이다 |
| **boolean 컬럼 명명** | 접두어 없음(`revoked`) / **`is_`·`has_`·`can_` 접두어** | **의미에 맞는 접두어 부착** → `is_enabled`, `is_revoked` | 사용자 결정. DB 스키마만 보고도 boolean임이 드러나고 grep이 쉽다. `is_`만 규칙으로 두면 `has_certificate`·`can_cancel` 류에서 어색해지므로 세 접두어를 함께 허용한다. 자바 필드는 `revoked`로 두고 `@Column(name="is_revoked")`로 매핑해 `isIsRevoked()` 문제를 피한다 |
| 금액 타입 | FLOAT / DECIMAL / 정수 | **DECIMAL 또는 정수(최소 화폐 단위)** | 부동소수점 오차 배제 |
| 시각 타입 | `LocalDateTime` + Clock / 타임존 인식 | **`LocalDateTime` + 주입된 `Clock`** ※§5 리스크 참조 | 인증 도메인과의 일관성 및 테스트 결정성. 단 수강 기간·취소 기간은 타임존 민감도가 높아 Design에서 재검토 |

### 7.3 통합 엔티티 초안 (Design 단계에서 확정)

```
════════════════ 인증 도메인 (기존 설계 인용) ════════════════

┌────────────────────┐         ┌──────────────────────────┐
│ users              │         │ user_roles               │
│────────────────────│         │──────────────────────────│
│ id (PK, BIGINT)    │────────►│ user_id (PK, FK→users)   │
│ username (UQ, 50)  │         │ role (PK, VARCHAR 20)    │
│ password (BCrypt)  │         │  ROLE_USER               │
│ is_enabled         │         │  ROLE_ADMIN              │
│ created_at         │         │  ROLE_CREATOR  ◄── 신규   │
└────────────────────┘         └──────────────────────────┘
      ╎ (값 참조, FK 제약 없음)
      ├───────────────────────────┬────────────────────────┐
      ▼                           ▼                        ╎
┌──────────────────────┐  ┌──────────────────────────┐     ╎
│ refresh_token        │  │ revoked_access_token     │     ╎
│──────────────────────│  │──────────────────────────│     ╎
│ id (PK)              │  │ id (PK)                  │     ╎
│ user_id  (값 참조)   │  │ token_id (UQ, jti, 36)   │     ╎
│ token_hash (UQ, 64)  │  │ user_id  (값 참조)       │     ╎
│  └ SHA-256, 원문 미저장│  │ expires_at (정리 기준)  │     ╎
│ issued_at            │  │ revoked_at               │     ╎
│ expires_at           │  └──────────────────────────┘     ╎
│ is_revoked/revoked_at│   └ 로그아웃 즉시 차단            ╎
└──────────────────────┘     스케줄러가 만료 행 purge      ╎
  └ 회전 + 재사용(탈취) 감지                               ╎
                                                           ╎
════════════════ 수강 도메인 (신규 설계) ═══════════════════╎
                                                           ╎
┌───────────────────────────────┐                          │
│ klass  (entity: Klass)      │◄── creator_id (FK) ───────┘
│───────────────────────────────│
│ id (PK, BIGINT)               │
│ creator_id      │ FK          │
│ title, description            │
│ price (DECIMAL)               │
│ capacity                      │
│ enrollment_count  ◄── 카운터   │  ← 락 대상 + O(1) 인원 조회
│ status (KlassStatus)          │     DRAFT / OPEN / CLOSED
│ starts_at, ends_at            │
│ cancellation_period_days      │  NULL → 전역 기본값
│ created_at                    │
└───────────────────────────────┘
        │ FK (실제 제약)                  │ FK (실제 제약)
        ▼                                 ▼
┌──────────────────────────────┐  ┌──────────────────────────┐
│ enrollment                   │  │ waitlist  (선택)         │
│──────────────────────────────│  │──────────────────────────│
│ id (PK)                      │  │ id (PK)                  │
│ klass_id (FK→klass)        │  │ klass_id (FK→klass)    │
│ user_id  │ FK               │  │ user_id  │ FK           │
│ status (EnrollmentStatus)    │  │ position                 │
│  PENDING/CONFIRMED/CANCELLED │  │  UQ (klass_id, position) │
│ source (EnrollmentSource)    │  │ status (WaitlistStatus)  │
│  DIRECT / WAITLIST           │  │  WAITING/PROMOTED/       │
│ created_at                   │  │  CANCELLED               │
│ confirmed_at ◄ 취소기간 기준 │  │ created_at               │
│ cancelled_at                 │  │ promoted_at              │
│ 부분 UQ: (user_id, klass_id) │  │ 부분 UQ: (klass_id,      │
│   WHERE status ≠ CANCELLED   │  │  user_id) WHERE          │
└──────────────────────────────┘  │  status = 'WAITING'      │
        ▲                         └──────────────────────────┘
        └──── 승격 시 INSERT ──────────────┘
              (source = WAITLIST)
```

> ✅ **이 다이어그램은 Design v1.3의 FK 정정을 반영했다.** 수강 도메인의 User 참조(`creator_id`, `user_id`)는 실제 FK이며, 값 참조는 인증 2개 테이블(`refresh_token`, `revoked_access_token`)만이다. 정본은 Design §3.1의 ERD.
> **`╎` 표기**: FK 제약 없는 값 참조 (현재는 인증 2개 테이블만).
> **`│` 표기**: 실제 FK 제약.

### 7.4 정원 관리 트랜잭션 규약 (핵심)

```
[사전] JwtAuthenticationFilter 가 Access 토큰을 검증한다
       ├ typ == ACCESS 확인          (토큰 타입 혼동 공격 차단)
       ├ jti ∉ revoked_access_token  (로그아웃된 토큰 차단)
       └ sub → 인증된 userId 확정

BEGIN TRANSACTION
  1. SELECT ... FROM klass WHERE id = :classId FOR UPDATE   -- 배타 락 획득
  2. IF status != 'OPEN'                      → REJECT (강의 모집 중 아님)
  3. IF now NOT within 신청 허용 기간          → REJECT
  4. IF EXISTS 활성 enrollment(userId, classId) → REJECT (중복 신청)
  5. IF enrollment_count >= capacity            → REJECT 또는 waitlist 등록
  6. INSERT enrollment (status = PENDING)
  7. UPDATE klass SET enrollment_count = enrollment_count + 1
COMMIT   -- 락 해제
```

**좌석 반납 → 대기열 승격 (취소 / PENDING 만료 공통 경로)**

```
BEGIN TRANSACTION
  1. SELECT ... FROM klass WHERE id = :classId FOR UPDATE   -- 배타 락 획득
  2. SELECT ... FROM enrollment WHERE id = :enrollmentId FOR UPDATE
  3. IF enrollment.status NOT IN ('PENDING','CONFIRMED') → ABORT (이미 처리됨)
  4. UPDATE enrollment SET status = 'CANCELLED', cancelled_at = now
  5. UPDATE klass SET enrollment_count = enrollment_count - 1
  6. -- 대기열 승격 (같은 락 아래에서 수행)
     SELECT ... FROM waitlist
      WHERE klass_id = :classId AND status = 'WAITING'
      ORDER BY position ASC LIMIT 1 FOR UPDATE
  7. IF 대기자 존재 AND klass.status == 'OPEN'
       a. 대기자 자격 재검증 (users.is_enabled, 활성 enrollment 부재)
       b. UPDATE waitlist SET status = 'PROMOTED', promoted_at = now
       c. INSERT enrollment (status = PENDING, source = 'WAITLIST')
       d. UPDATE klass SET enrollment_count = enrollment_count + 1
COMMIT   -- 락 해제
```

> 5번의 감소와 7-d의 증가가 상쇄되어 `enrollment_count`의 순변화는 0이다. 반납된 좌석이 일반 신청자에게 노출되지 않고 곧바로 대기자에게 이전되는 것이 이 설계의 핵심이다.

> **락 획득 순서 규약**: 항상 **Klass → Enrollment → Waitlist**. 모든 트랜잭션이 이 순서를 지켜 데드락을 회피한다. 위 두 흐름 모두 `klass` 락을 가장 먼저 잡고 **승격까지 같은 트랜잭션 안에서 끝낸다** — 락을 놓고 승격하면 그 틈에 일반 신청자가 좌석을 채간다.
> **주의 1**: 정원 락을 잡은 트랜잭션 안에서 블랙리스트 조회를 하지 않는다. 토큰 검증은 필터 단계에서 이미 끝나 있어야 하며, 락 보유 시간에 외부 조회를 더하면 경합이 증폭된다.
> **주의 2**: 위 3번의 상태 재확인이 없으면 **사용자 결제와 만료 배치가 동시에 실행될 때 결제된 신청이 취소된다.** 행 락 획득 후 상태를 반드시 다시 읽어야 한다.

### 7.5 권한 검증 지점 매핑

| 요건 | 검증 방식 |
|------|-----------|
| 수강 신청/취소 | Access 토큰 유효 + `sub`를 `enrollment.user_id`로 사용 |
| 내 수강 신청 목록 | `enrollment.user_id == sub` (다른 사용자 조회 불가) |
| 강의 등록 | `roles ∋ ROLE_CREATOR` |
| 강의 상태 변경 | `roles ∋ ROLE_CREATOR` **AND** `klass.creator_id == sub` |
| 강의별 수강생 목록 (크리에이터 전용) | `roles ∋ ROLE_CREATOR` **AND** `klass.creator_id == sub` |
| 강의 목록/상세 조회 | 인증 불요 (공개) ※Design에서 확인 |

> 권한(role)만으로는 부족하다. `ROLE_CREATOR`를 가진 사용자가 **남의 강의**의 수강생 목록을 볼 수 있으면 안 되므로, 소유권 검사(`creator_id == sub`)가 항상 동반되어야 한다.

---

## 8. Convention Prerequisites

### 8.1 기존 프로젝트 컨벤션

- [ ] `CLAUDE.md` 코딩 컨벤션 섹션 — 없음 (사용자 전역 규칙: 한국어 문서화·주석)
- [ ] `docs/01-plan/conventions.md` — 없음
- [ ] ESLint / Prettier / tsconfig — 해당 없음 (Java 스택)
- [x] **참고 저장소의 관례를 사실상의 컨벤션으로 채택** — `build.gradle`, 헥사고날 패키지 구조, JavaDoc에 `Design Ref: §n` 주석 규약

### 8.2 정의할 컨벤션

| 항목 | 현재 | 정의할 내용 | 우선순위 |
|------|------|-------------|:--------:|
| 테이블 명명 | 참고 저장소 혼용 (`users` 복수 / `refresh_token` 단수) | **원칙: 단수 snake_case. 예약어 충돌 시에만 복수형** → `klass`, `enrollment`, `waitlist`. 복수형은 `users`(예약어 회피)와 `user_roles`(복합 PK 관례)뿐 | High |
| 엔티티 명명 | — | 자바 예약 식별자 회피 필요 시 `Klass` 식 우회. **엔티티명 = 테이블명**을 유지해 `@Table` 매핑이 불필요하게 한다 | High |
| 컬럼 명명 | snake_case | FK/참조는 `{entity}_id`, 시각은 `{동사}_at`, 기간은 `{명사}_days` | High |
| PK 전략 | `BIGINT IDENTITY` | 전 테이블 통일. 커서 페이지네이션 정렬 키로 `id` 사용 | High |
| **boolean 컬럼** | 접두어 없음 | **`is_` / `has_` / `can_` 중 의미에 맞는 접두어 부착.** 자바 필드는 접두어 없이 두고 `@Column(name=...)`으로 매핑 | High |
| ENUM 값 | `ROLE_` 접두어 (Spring Security 관례) | 권한은 `ROLE_*` 유지, 상태는 접두어 없이 `DRAFT`/`OPEN`/`PENDING` 등 | High |
| ENUM 저장 | `@Enumerated(EnumType.STRING)` | ordinal 금지 — 값 순서 변경 시 데이터가 조용히 깨진다 | High |
| 시각 타입 | `LocalDateTime` + 주입된 `Clock` | 통일. `LocalDateTime.now()` 직접 호출 금지 (테스트 결정성) | High |
| 공통 감사 컬럼 | `created_at`만 (`updated_at` 없음) | 상태 전이 시각을 개별 컬럼(`confirmed_at`, `cancelled_at`)으로 남기므로 `updated_at` 불필요 여부 판단 | Medium |
| 문서화 언어 | 한국어 | ERD 주석·설명은 한국어, 식별자는 영어 | High |

### 8.3 필요한 환경 변수 / 프로퍼티

인증 관련은 참고 저장소의 `application.yml` 구조를 그대로 승계한다.

| 프로퍼티 | 목적 | 참고 저장소 기본값 | 비고 |
|----------|------|--------------------|------|
| `spring.datasource.url` | DB 연결 | `jdbc:h2:mem:jwtauth` | 실 DB 전환 시 변경 |
| `jwt.issuer` | 토큰 발급자 | `sample-jwt-authentication` | 프로젝트명으로 변경 필요 |
| `jwt.secret` | HS256 서명 키 | 평문 (학습용) | ⚠️ **실서비스에서는 환경변수로 분리 필수** |
| `jwt.access-token-validity` | Access 만료 | `PT30M` | 신청 트랜잭션 길이 대비 충분 |
| `jwt.refresh-token-validity` | Refresh 만료 | `P14D` | — |
| `jwt.revoked-token-cleanup-interval` | 블랙리스트 정리 주기 | `PT10M` | 미설정 시 테이블 무한 증가 |
| `app.default-user.*` | 시딩 계정 | `chals` / `ROLE_USER` | 크리에이터 시딩 계정 추가 검토 |
| `app.enrollment.default-cancellation-period-days` | 취소 가능 기간 전역 기본값 | — | **신규 추가 필요** |
| `app.enrollment.pending-expiry.direct` | 일반 신청 PENDING 만료 기한 | — | **신규**. 예시 `PT30M` |
| `app.enrollment.pending-expiry.waitlist` | 승격 PENDING 만료 기한 | — | **신규**. 예시 `PT10M` — 뒷 순번 대기자를 오래 붙잡지 않도록 짧게 |
| `app.enrollment.pending-expiry-scan-interval` | 만료 스캔 배치 주기 | — | **신규**. 예시 `PT1M` |

### 8.4 요건 ↔ 스키마 매핑 (Design에서 완성)

| 요건 | 충족 수단 |
|------|-----------|
| 강의 등록 (제목/설명/가격/정원/기간) | `klass` 컬럼 |
| 강의 상태 3단계 | `KlassStatus` ENUM + 전이 규칙 |
| 목록 조회(상태 필터) | `INDEX (status, id DESC)` |
| 상세 조회(신청 인원 포함) | `klass.enrollment_count` |
| 수강 신청 | `enrollment` INSERT + 트랜잭션 규약 §7.4 |
| 신청 상태 3단계 | `EnrollmentStatus` ENUM + 전이 규칙 |
| 결제 확정 처리 | `status → CONFIRMED`, `confirmed_at` 기록 |
| 수강 취소 | `status → CANCELLED`, `cancelled_at`, 카운터 감소 |
| 내 신청 목록 | `INDEX (user_id, id DESC)`, `user_id == JWT sub` |
| 정원 초과 거부 | `CHECK (enrollment_count <= capacity)` + §7.4 5단계 |
| 동시 마지막 자리 | `SELECT FOR UPDATE` on `klass` |
| **사용자 식별(신규)** | `users` + JWT `sub` 클레임 |
| **로그인/로그아웃(신규)** | `refresh_token` 회전 + `revoked_access_token` 블랙리스트 |
| **토큰 탈취 방어(신규)** | `refresh_token.revoked` 재사용 감지 → 사용자 전체 토큰 무효화 |
| (선택) 취소 기간 제한 | `enrollment.confirmed_at` + `klass.cancellation_period_days` |
| (선택) 대기열 | `waitlist` + `UNIQUE (klass_id, position)` + `WaitlistStatus` |
| (선택) 대기열 승격 | 좌석 반납 트랜잭션 §7.4 6~7단계 → `enrollment(PENDING, source=WAITLIST)` |
| **좌석 영구 점유 방지(신규)** | `enrollment.source` 별 PENDING 만료 기한 + 만료 배치 |
| (선택) 크리에이터 수강생 목록 | `klass.creator_id` + `ROLE_CREATOR` + `INDEX (klass_id, status, id DESC)` |
| (선택) 페이지네이션 | 커서 방식 (`id` 기준) + 복합 인덱스 |

---

## 9. Next Steps

1. [ ] 설계 문서 작성: `/pdca design class-enrollment-erd`
   - 통합 Mermaid ERD 확정 (7개 테이블), 전 필드 타입 명세, 상태 전이표, 인덱스/제약 최종안
   - 미결 사항 확정: ① ~~카운터 집계 기준~~ → **확정: `enrollment`의 PENDING + CONFIRMED** ② CLOSED 시 PENDING 처리 정책 **및 CLOSED 상태에서의 승격 허용 여부** ③ 시각 타입(`LocalDateTime` vs 타임존 인식) ④ 회원가입 API 필요 여부 ⑤ `is_revoked` boolean과 `revoked_at` 중복 해소 여부 ⑥ PENDING 만료 기한 실제 값
2. [ ] 인증 도메인 divergence 목록 작성 (원본 대비 변경: `ROLE_CREATOR` 추가 등)
3. [ ] 동시성 검증 시나리오 문서화 (잔여 1석 동시 100 요청, 취소↔대기열 승격 경합)
4. [ ] 리뷰 및 승인 후 구현(Do) 진입

---

## Version History

| 버전 | 날짜 | 변경 내용 | 작성자 |
|------|------|-----------|--------|
| 0.1 | 2026-08-31 | 초안 작성 (사용자 확정 4개 결정 반영) | developer2@lulumedic.com |
| 0.8 | 2026-09-01 | 컬럼명 `confirmed_count` → **`enrollment_count`** (23건). Design v1.9와 동기화 | developer2@lulumedic.com |
| 0.7 | 2026-08-31 | 재검증 지적(N-9) 반영: §7.3 다이어그램 본문의 `╎ 값 참조` 표기 3곳을 FK로 통일. 같은 그림에서 `creator_id`가 FK와 값 참조로 동시 표기되던 모순 해소 | developer2@lulumedic.com |
| 0.6 | 2026-08-31 | design-validator 지적(I-9) 반영: §7.2 FK 정책과 §5 리스크·§7.3 다이어그램에 Design v1.3의 정정을 반영. Plan과 Design이 상반된 상태를 해소 | developer2@lulumedic.com |
| 0.5 | 2026-08-31 | 강의 테이블명을 `classes` → **`klass`**로, FK 컬럼을 `class_id` → **`klass_id`**로 변경. 엔티티·테이블·컬럼에 한 이름을 쓰고, 단수형 명명 원칙의 예외를 제거 | developer2@lulumedic.com |
| 0.4 | 2026-08-31 | 대기열 승격 방식 확정(설계 1: 승격 시 `enrollment(PENDING)` 자동 생성). `WaitlistStatus`·`EnrollmentSource` ENUM 정의, PENDING 만료 정책 FR 신규(FR-16~18), 좌석 반납→승격 트랜잭션 규약 추가, 카운터 집계 기준 확정 | developer2@lulumedic.com |
| 0.3 | 2026-08-31 | boolean 컬럼 명명 규칙 확정 (`is_`/`has_`/`can_` 접두어). `enabled`→`is_enabled`, `revoked`→`is_revoked` 및 인용 divergence 기록 | developer2@lulumedic.com |
| 0.2 | 2026-08-31 | JWT 인증 도메인 통합 (`sample-jwt-authentication` 인용). 엔티티명 `Class`→`Klass`, `ROLE_CREATOR` 추가, FK 하이브리드 정책, PK/시각 타입을 인증 도메인 관례로 통일 | developer2@lulumedic.com |
