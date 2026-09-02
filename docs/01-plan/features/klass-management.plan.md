# 강의 관리 기능 계획서

> **Summary**: 강의(Klass)의 등록·수정·상태 전이·조회(상세/공개 목록/내 강의 목록) 5종 유즈케이스를 헥사고날 전 계층에 걸쳐 구현한다. 스키마는 이미 확정돼 있으므로 이번 사이클의 본체는 **행위**다 — 엔티티 상태 전이 메서드, 소유권 검사, 커서 페이지네이션.
>
> **Project**: klass
> **Version**: 0.0.1-SNAPSHOT
> **Author**: developer2@lulumedic.com
> **Date**: 2026-09-02
> **Status**: Draft

---

## Executive Summary

| 관점 | 내용 |
|------|------|
| **Problem** | 1차에서 `klass` 테이블과 엔티티는 만들었지만 **강의를 만들 방법도 볼 방법도 없다.** `KlassJpaRepository` 는 본문이 비어 있고 `Klass` 에는 팩토리 `open()` 하나뿐이며, application·adapter.in 계층이 통째로 없다. 수강신청(2차 본체)은 신청할 강의가 존재해야 성립하므로, 강의 관리는 그 선행 조건이다. |
| **Solution** | ERD 정본이 이미 확정한 규약(상태 전이표 §3.4, 소유권 검사 §7, 정원 축소 방어 §4.8, 인덱스 §5)을 **코드로 이행**한다. 상태 전이와 정원 수정은 엔티티 안의 의도 드러나는 메서드로 표현하고, 권한은 `ROLE_CREATOR` 보유(설정)와 `creator_id == sub` 소유권(서비스) **두 겹**으로 나눠 검사한다. 목록은 `idx_klass_status` · `idx_klass_creator` 가 전제하는 커서 방식으로 읽는다. |
| **Function/UX효과** | 크리에이터는 강의를 초안으로 만들어 다듬은 뒤 공개하고, 모집을 마감할 수 있다. 일반 사용자에게는 공개된 강의만 보이고 남의 초안은 존재조차 드러나지 않는다. 목록은 강의 수가 늘어도 응답 시간이 일정하다. |
| **Core Value** | "남의 강의는 건드릴 수 없다"와 "정원은 이미 앉은 사람보다 작아질 수 없다"를 **권한 설정 한 곳이 아니라 도메인 규칙으로** 보증한다. `ROLE_CREATOR` 만 검사하면 크리에이터끼리 서로의 강의를 수정할 수 있다는 구멍이 남는데, 이 계획은 그 구멍을 처음부터 닫힌 채로 시작한다. |

---

## Context Anchor

| Key | Value |
|-----|-------|
| **WHY** | 강의를 만들·고칠·볼 경로가 없어 수강신청 도메인이 착지할 대상이 존재하지 않는다 |
| **WHO** | 크리에이터(ROLE_CREATOR) — 등록·수정·상태 전이·내 강의 목록 / 일반 사용자·비로그인 방문자 — 공개 목록·상세 조회 |
| **RISK** | `ROLE_CREATOR` 권한 검사만 통과하면 **남의 강의를 수정할 수 있는** 수평 권한 상승 (소유권 검사 누락) |
| **SUCCESS** | 타 크리에이터의 강의 수정·상태 전이 시도가 403 / 금지된 상태 전이가 전부 거부 / DRAFT 가 공개 목록·타인 상세 조회에서 완전히 비노출 / 커서 목록이 중복·누락 없이 이어짐 |
| **SCOPE** | Phase 1 도메인 행위(전이·수정 메서드 + L1) → Phase 2 포트·서비스·어댑터(소유권·커서 + L2) → Phase 3 컨트롤러 + RestDocs 6종(L3) → Phase 4 스키마 개정(`updated_at`)·통합 검증(L4/L5) |

---

## 1. Overview

### 1.1 Purpose

강의 애그리거트에 대한 **명령 3종(등록·수정·상태 전이)과 조회 3종(상세·공개 목록·내 강의 목록)** 을 헥사고날 전 계층에 구현한다. 산출물은 도메인 메서드 + 포트/서비스 + 어댑터 + REST 엔드포인트 6개 + RestDocs 스니펫이다.

### 1.2 Background

CLAUDE.md 의 [범위 경계](../../../CLAUDE.md) 가 명시하듯, 1차는 **수강 도메인을 엔티티 + Repository 까지만** 두고 끝냈다. 그 선택은 의도된 것이었고 근거는 "동시성 규약을 코드로 옮기기 전에 스키마를 먼저 굳힌다" 였다. 스키마가 굳었으므로 이제 행위를 얹는다.

다만 **2차 전체를 한 번에 열지는 않는다.** 2차에 예정된 항목 중 강의 관리는 동시성 규약과의 결합이 가장 얕다.

- 수강신청·취소·대기열 승격은 `enrollment_count` 증감을 동반하므로 비관적 락 규약(ERD §4.1~4.4)이 통째로 필요하다.
- 반면 강의 관리에서 락이 필요한 지점은 **상태 전이와 `capacity` 수정 두 곳뿐**이며(ERD §4.8), 둘 다 `enrollment_count` 를 **읽기만** 한다.

즉 강의 관리는 락 규약의 가장 얇은 단면만 밟고 지나간다. 여기서 `klass` 행 락을 먼저 세워 두면 수강신청이 그 위에 올라탈 수 있다 — ERD §4.1 이 "정원과 관련된 모든 트랜잭션은 `klass` 단일 행을 첫 락으로 잡는다" 로 락 순서를 하나로 고정해 둔 덕분이다.

**이번 사이클에서 처음으로 확인되는 사실**: 이 저장소의 인증은 지금까지 "토큰이 있거나 없거나" 두 상태만 다뤘다(`anyRequest().authenticated()`). 강의 조회는 ERD §7 이 **선택적 인증**으로 확정한 첫 엔드포인트다 — 토큰 없이도 열리되, 토큰이 있으면 보이는 것이 늘어난다. SecurityConfig 의 `PUBLIC_ENDPOINTS` 확장과 `JwtAuthenticationFilter` 의 무토큰 통과 동작 확인이 함께 필요하다.

### 1.3 Related Documents

- **데이터 모델 정본**: [`docs/02-design/features/class-enrollment-erd.design.md`](../../02-design/features/class-enrollment-erd.design.md)
  — §3.2.5 `klass` 컬럼 / §3.3 `KlassStatus` / §3.4 상태 전이표 / §4.1 락 순서 / §4.8 상태 전이·정원 수정 / §5 인덱스·쿼리 패턴 / §7 권한 검증 지점
- 1차 사이클 기록: [`docs/archive/2026-09/project-setup/project-setup.design.md`](../../archive/2026-09/project-setup/) — §10 코딩 규약, §12 divergence 13건
- 다음 산출물: `docs/02-design/features/klass-management.design.md`

---

## 2. Scope

### 2.1 In Scope

**도메인 (`klass/domain/Klass.java`, 신규 `KlassError`)**
- [ ] 상태 전이 메서드 — `publish()`(DRAFT→OPEN), `close()`(OPEN→CLOSED). 허용되지 않는 전이는 도메인 예외
- [ ] 내용 수정 메서드 — 제목·설명·가격·수강기간
- [ ] 정원 수정 메서드 — `changeCapacity()`. `new < enrollmentCount` 면 거부 (ERD §4.8)
- [ ] 소유권 판별 — `isOwnedBy(userId)`
- [ ] `updated_at` 컬럼 + 수정 시 갱신 (생성 시각과 같은 규약 — 팩토리/메서드가 `Clock` 산출값을 **파라미터로** 받는다)
- [ ] `KlassError` — 도메인 에러 코드 (`ErrorCode` 구현)

**애플리케이션 (`klass/application/`)**
- [ ] 인바운드 포트 5종: `RegisterKlassUseCase`, `UpdateKlassUseCase`, `ChangeKlassStatusUseCase`, `FindKlassUseCase`, `ListKlassUseCase`
- [ ] 아웃바운드 포트: `KlassCommandPort`, `KlassQueryPort`
- [ ] `KlassService` — 소유권 검사, 락 획득 지점, `Clock` 주입
- [ ] Command/Result DTO — 수정은 전 필드 필수이므로 `Optional` 같은 "미지정" 표현이 필요 없다 (Design D-25)

**어댑터 아웃 (`klass/adapter/out/persistence/`)**
- [x] `KlassJpaRepository` 확장 — 파생 쿼리 1종(`findWithCreatorById`). 커서는 QueryDSL, ~~비관적 락~~은 Deferred (Design D-21)
- [ ] `KlassRepositoryAdapter` — 포트 구현
- [ ] 개설자 fetch join — 상세·목록 응답에 개설자명이 들어가므로 N+1 차단 (CLAUDE.md 범위 경계에 예고된 지점)

**어댑터 인 (`klass/adapter/in/web/`)**
- [ ] `KlassController` — 엔드포인트 6개
- [ ] Request/Response DTO — 커서 페이지 응답 봉투 포함

**인프라**
- [ ] `SecurityConfig` — 조회 경로 선택적 인증 허용, 명령 경로 `ROLE_CREATOR` 경로 단위 보호
- [x] ~~`JwtAuthenticationFilter` 무토큰 요청 통과 동작 확인~~ — 이미 그렇게 동작함. 테스트로 고정만 하면 된다

**문서·테스트**
- [ ] ERD 정본 §3.2.5 에 `updated_at` 등재 (스키마 개정)
- [ ] L1~L5 테스트 (§4 참조)

### 2.2 Out of Scope

- **수강신청·취소·결제 확정** (`enrollment` 상태 전이, `enrollment_count` 증감) — 락 규약 본체
- **대기열 승격 체인** — `capacity` 증가 시 승격 트리거(ERD §4.8 capacity 5번)는 대기열이 있어야 성립한다. 이번엔 **정원 증가만 하고 승격은 하지 않는다** (§5 리스크 참조)
- **역전이 2종** (`OPEN→DRAFT`, `CLOSED→OPEN`) — ERD §3.4·§4.8 이 대기자 유령 행 문제로 초기 구현 금지를 권고. 사용자 확정 사항
- **강의 삭제** — ERD §2 "물리 삭제가 없다" 원칙. 폐기가 필요하면 `CLOSED`
- **강의별 수강생 목록 조회** — `enrollment` 조회이므로 수강신청 사이클에 속한다
- **`app.enrollment.*` 프로퍼티 4종** — 취소 기간 기본값 등. 취소 로직과 함께 붙는다
- 강의 검색(제목 LIKE)·카테고리·정렬 옵션 — 인덱스 설계가 `id DESC` 단일 정렬 전제

---

## 3. Requirements

### 3.1 기능 요구사항

**명령 (크리에이터 전용)**

| ID | 요구사항 | 우선순위 | 상태 |
|----|---------|:--------:|:----:|
| FR-01 | 강의 등록 — 제목·설명·가격·정원·수강기간(시작일~종료일). 상태는 항상 `DRAFT` 로 시작 | High | Pending |
| FR-02 | 강의 수정 — 제목·설명·가격·정원·수강기간을 **전체 필수 수신**(전체 교체). 클라이언트가 변경되지 않은 필드도 현재 값을 그대로 실어 보내므로, 누락·`null`·공백은 400 이다 | High | **개정** (Design D-25) |
| FR-03 | 강의 상태 수정 — `DRAFT→OPEN`, `OPEN→CLOSED` 만 허용. 그 외 전이는 거부 | High | Pending |
| FR-04 | 정원 축소 방어 — `new_capacity < enrollment_count` 면 거부 (ERD §4.8) | High | Pending |
| FR-05 | 소유권 검사 — `ROLE_CREATOR` 보유 **AND** `klass.creator_id == sub`. 불일치 시 403 (ERD §7) | High | Pending |
| FR-06 | 상태 전이·정원 수정은 `klass` 행 비관적 락 아래 수행 (ERD §4.1 락 순서) | High | **Deferred** (Design D-21) |
| FR-07 | 수정 시 `updated_at` 갱신. 주입된 `Clock` 사용 | Medium | Pending |
| FR-17 | 취소 가능 기간(`cancellationPeriodDays`)은 **`DRAFT` 에서만 변경 가능** — 다른 상태에서 값을 바꾸면 409 `CANCELLATION_PERIOD_NOT_EDITABLE`. **같은 값 재전송은 허용**(no-op). 취소 가능 기간은 수강생과의 약속이라 신청자가 생긴 뒤 사후 변경은 이미 신청한 사람에게 불리하게 작용한다(`DRAFT` 는 신청 자체가 불가능하다 — ERD §2.2). 같은 값을 통과시키지 않으면 전체 필수 수신(FR-02)에서 모든 요청이 이 필드를 싣고 오므로 `OPEN` 강의를 아예 수정할 수 없게 된다 | High | **완료** (Design D-26) |

> **FR-06 을 삭제하지 않고 `Deferred` 로 둔다.** 락이 직렬화하려던 상대는 수강신청 트랜잭션이고 그것이 2차 범위라 지금은 직렬화할 대상이 없다 — 요구사항이 틀린 것이 아니라 **아직 발현하지 않은 것**이므로, 수강신청이 붙는 2차에서 되살아난다 (Design §12 D-21).

**조회 (전체 공개, 선택적 인증)**

| ID | 요구사항 | 우선순위 | 상태 |
|----|---------|:--------:|:----:|
| FR-08 | 강의 상세 조회 — `GET /v1/klasses/{id}`. `DRAFT` 는 개설자 본인에게만. 타인에게는 **404** (403 아님, §3.3 참조) | High | Pending |
| FR-09 | 공개 목록 조회 — `GET /v1/klasses`. `OPEN`·`CLOSED` 만. 커서 `?cursor={id}&size=20`, `id DESC` | High | Pending |
| FR-10 | 내 강의 목록 — `GET /v1/klasses/me`. 본인 개설분 전체(`DRAFT` 포함). 커서 동일 규격 | High | Pending |
| FR-11 | 목록 응답에 `hasNext`·`nextCursor` 포함. 총 개수는 제공하지 않음 | Medium | Pending |
| FR-12 | 상세·목록 응답에 개설자 정보 포함. fetch join 으로 N+1 차단 | Medium | Pending |
| FR-13 | 공개 목록에 상태 필터 `?status=OPEN` 지원 (미지정 시 OPEN+CLOSED) | Low | Pending |

**API 계약**

| ID | 요구사항 | 우선순위 | 상태 |
|----|---------|:--------:|:----:|
| FR-14 | 모든 응답은 `ApiResponse<T>` 봉투. boolean 필드는 전 계층 `is` 접두어 | High | Pending |
| FR-15 | 6개 엔드포인트 전부 RestDocs 스니펫 생성 → `openapi3.json` 반영 | High | Pending |
| FR-16 | 입력 검증 실패는 400 + `details` 에 필드별 사유 (기존 `GlobalExceptionControllerAdvice` 규약) | High | Pending |

### 3.2 비기능 요구사항

| 항목 | 기준 | 측정 방법 |
|------|------|-----------|
| 성능 | 목록 조회가 강의 수와 무관하게 인덱스 스캔. `idx_klass_status` / `idx_klass_creator` 사용 | 실행 계획 확인 또는 쿼리 카운트 테스트 |
| 성능 | 목록 N건 조회 시 SQL **2회 이하** (본문 + 개설자 fetch join 포함) | 어댑터 테스트에서 쿼리 카운트 검증 |
| 보안 | 수평 권한 상승 차단 — 타 크리에이터 강의 수정·전이 시도 403 | L3 컨트롤러 테스트 |
| 보안 | 정보 노출 차단 — 타인 `DRAFT` 는 존재 자체가 드러나지 않음 | L3 테스트에서 404 + 본문 마커 확인 |
| 정합성 | 정원 축소가 앱 검사와 CHECK 제약 **양쪽**에서 거부 | L1(앱) + 스키마 테스트(CHECK) |
| 시각 | 무인자 `LocalDateTime.now()` / `LocalDate.now()` 부재 | grep 검증 |
| 문서 | `documentationTest` 의 엔드포인트 개수 검증 통과 | `./gradlew build` |

### 3.3 확정된 설계 판단

계획 단계에서 이미 결론이 난 것들. Design 에서 재론하지 않는다.

| 판단 | 결정 | 근거 |
|------|------|------|
| 내 강의 목록 경로 | `GET /v1/klasses/me` (별도 경로) | `/v1/users/me` 관례가 이미 있고, `SecurityConfig` 에서 경로 단위로 `ROLE_CREATOR` 를 걸 수 있다. `?scope=mine` 은 권한 검사를 설정에서 서비스 코드로 끌어내린다 |
| 페이지네이션 | 커서 (`?cursor&size`) | `idx_klass_status(status, id DESC)` 가 정확히 이걸 위해 설계됐다 (ERD §5). offset 은 뒷 페이지에서 느려지고 인덱스 의도와 어긋난다 |
| 상태 전이 범위 | 정방향 2종만 | ERD §3.4·§4.8 — 역전이는 대기자가 `DRAFT` 강의에 유령으로 남거나 신규 신청자가 대기자를 앞지르는 구멍을 연다. 대기열이 없는 지금 열면 2차에서 다시 닫아야 한다 |
| `updated_at` | 추가한다 | 수정 기능이 생기는데 "언제 바뀌었나"를 알 길이 없다. ERD 정본 §3.2.5 개정 + 스키마 테스트 갱신이 따라온다 |
| 타인 DRAFT 응답 | **404** (403 아님) | 403 은 "그 강의는 있는데 네가 못 본다"를 알려준다. ERD §7 의 "DRAFT 는 제외" 는 필터링이지 거부가 아니다 — 목록에서 안 보이는 것과 상세에서 404 가 같은 이야기를 해야 한다 |
| 수정 방식 | ~~PATCH (부분 수정)~~ → **PATCH 경로 유지 + 전체 필수 수신** | **개정** (Design §12 D-25). 최초 근거는 "PUT 전체 교체는 클라이언트가 안 바꾸는 필드까지 실어야 한다" 였는데, **실어 보내는 것이 정상이라는 것이 사용자 확정 사항**이다 — 수정 화면은 상세 조회로 강의 전체 값을 들고 있다. 그러면 필드 누락·`null`·공백은 "안 바꿈"이 아니라 **입력 오류**이고, 부분 수정 규격은 그것을 200 으로 받아 조용히 무시한다. HTTP 메서드는 `PATCH` 를 유지한다 — `SecurityConfig` 매처·openapi 오퍼레이션 키·스니펫 이름까지 번져 위험 대비 이득이 없다 |

---

## 4. 성공 기준

### 4.1 Definition of Done

- [ ] FR-01 ~ FR-16 전부 구현
- [ ] `./gradlew build` 통과 — `test` + `documentationTest` 포함
- [ ] `openapi3.json` 에 강의 엔드포인트 6개 반영, `/docs/api-guide.html` 에서 확인
- [ ] ERD 정본 §3.2.5 에 `updated_at` 등재, 변경 이력 갱신
- [ ] 커밋 규약 준수 — 계층을 가로지르는 커밋은 범위를 본문에 명시

### 4.2 테스트 (레벨별)

| 레벨 | 대상 | 필수 케이스 |
|------|------|-------------|
| **L1** 도메인 | `KlassTest` | 전이 허용 2종 / 금지 4종 거부 / 정원 축소 거부 / `isOwnedBy` / `updatedAt` 갱신 |
| **L2** 어댑터 | `KlassRepositoryAdapterTest` | 커서 조회 경계(첫 페이지·중간·마지막) / `DRAFT` 필터링 / 프록시 초기화 / **fetch join 쿼리 증가분** |
| **L2** 서비스 | `KlassServiceTest` | 소유권 불일치 예외 / 전 필드 교체와 `updatedAt` 갱신 (동일 값이어도) / `Clock` 주입 확인 |
| **L3** 컨트롤러 | `KlassControllerTest` | **엔드포인트 6개 전부 RestDocs 스니펫** + 403(타인 강의) + 404(타인 DRAFT) + 400(검증 실패) |
| **L4** 통합 | `KlassFlowIntegrationTest` | 등록 → 수정 → 공개 → 목록 노출 확인 → 마감 전 흐름 |
| **L5** 문서 | `DocumentationIntegrationTest` | 엔드포인트 개수 검증 갱신 |
| **스키마** | `EnrollmentSchemaTest` | `updated_at` 컬럼 존재 검증 추가 |

### 4.3 검증 시나리오 (Design 에서 구체화)

| # | 시나리오 | 기대 |
|---|---------|------|
| S1 | 크리에이터 A 가 크리에이터 B 의 강의 수정 시도 | 403, 데이터 불변 |
| S2 | `ROLE_USER` 만 가진 사용자가 강의 등록 시도 | 403 |
| S3 | 비로그인 방문자가 공개 목록 조회 | 200, `DRAFT` 0건 |
| S4 | 일반 사용자가 타인의 `DRAFT` 상세 조회 | 404 |
| S5 | 개설자가 자기 `DRAFT` 상세 조회 | 200 |
| S6 | `CLOSED` 강의를 `OPEN` 으로 전이 시도 | 400/409, 상태 불변 |
| S7 | `enrollment_count = 5` 인 강의를 정원 3 으로 수정 | 거부. CHECK 제약까지 도달하지 않고 앱이 먼저 막는다 |
| S8 | 커서 목록 3페이지 순회 | 중복·누락 0건, 마지막 페이지 `hasNext=false` |
| S9 | `OPEN` 강의의 취소 가능 기간을 다른 값으로 변경 시도 / 같은 값 재전송 | **409** `CANCELLATION_PERIOD_NOT_EDITABLE`(값 불변) / **200**(제목 등 다른 필드는 정상 수정). `DRAFT` 에서는 변경이 200 이다 (FR-17) |

---

## 5. 리스크와 대응

| 리스크 | 영향 | 가능성 | 대응 |
|--------|:----:|:------:|------|
| **소유권 검사 누락** — `ROLE_CREATOR` 만 검사하면 크리에이터끼리 서로의 강의를 수정할 수 있다. 권한 테스트가 "권한 있는 사용자로 200" 만 확인하면 **통과한다** | 높음 | 중간 | 서비스 계층에 소유권 검사를 단일 지점으로 두고, L3 에 "**다른** 크리에이터로 403" 케이스를 명시적으로 넣는다 (S1) |
| **선택적 인증이 이 저장소에 처음 도입된다** — 지금까지 경로는 "토큰 불필요(`PUBLIC_ENDPOINTS`)" 아니면 "토큰 필수(`anyRequest().authenticated()`)" 둘뿐이었다. 강의 조회는 **토큰이 없어도 200이되, 있으면 보이는 범위가 늘어나는** 세 번째 종류다 | 중간 | 중간 | **필터 쪽은 이미 해결돼 있음이 확인됐다** — `JwtAuthenticationFilter` 는 무토큰 요청에서 예외를 던지지 않고 체인을 통과시킨다(파일 주석: "이 필터는 요청을 직접 거부하지 않는다"). 남은 일은 ① `SecurityConfig` 에 조회 경로 `permitAll` 추가 ② **컨트롤러·서비스가 `principal == null` 을 다루는 것** — 기존 `UserController` 는 보호 경로라 `principal.id()` 를 바로 역참조하는데, 조회 경로는 그러면 NPE 다. 비로그인 조회(S3)를 L3 에 고정 |
| **상태코드만 검사하면 위양성** — Security 기본 체인이 살아 있으면 리다이렉트를 따라가 200 + `text/html` 이 온다 (CLAUDE.md 기록된 실제 사고) | 중간 | 중간 | 모든 조회 테스트에서 응답 본문 마커까지 확인 |
| **정원 증가 시 대기열 승격 부재** — ERD §4.8 은 정원을 올리면 빈자리만큼 대기자를 승격하라고 규정한다. 대기열이 2차이므로 이번엔 승격이 없다. 대기자가 생긴 뒤 정원을 올리면 **신규 신청자가 대기자를 앞지른다** | 중간 | 낮음 (대기열 미구현이라 현재는 발현 불가) | `changeCapacity()` 에 "대기열 승격은 2차에서 이 지점에 붙는다" 를 근거와 함께 주석으로 남기고, 수강신청 사이클의 선행 과제로 등재. **지금 대기열이 없으므로 실제 피해는 없다** |
| **`updated_at` 추가가 정본 문서를 건드린다** — ERD 정본은 1차의 결론물이다 | 낮음 | 확실 | 정본 §3.2.5 + 변경 이력에 함께 반영. 아카이브 문서는 손대지 않는다 (그 시점의 기록) |
| **컴파일러가 못 잡는 3종** — JPQL 문자열, 파생 쿼리 메서드명, RestDocs 경로. 이 저장소에서 이미 세 번 다 걸렸다 | 중간 | 중간 | 새 필드(`updatedAt`) 도입 시 `grep -rnE '\bupdatedAt\b'` 로 전 계층 훑기. 기동 테스트로 부트스트랩 실패를 조기 검출 |
| **fetch join 과 커서 페이지네이션의 충돌** — `@ManyToOne` fetch join 은 안전하지만, 향후 컬렉션 조인이 끼면 페이징이 메모리로 내려간다 | 낮음 | 낮음 | 이번 범위는 `creator` 단일 연관만. L2 에 쿼리 카운트 검증을 걸어 회귀를 잡는다 |

---

## 6. 영향 분석

### 6.1 변경 대상

| 리소스 | 유형 | 변경 내용 |
|--------|------|-----------|
| `Klass` 엔티티 | 도메인 | 상태 전이·수정·정원 변경·소유권 판별 메서드 추가, `updatedAt` 필드 추가 |
| `klass` 테이블 | 스키마 | `updated_at` 컬럼 추가 (nullable — 기존 행 없음, 신규는 생성 시 `created_at` 과 동일값) |
| `KlassJpaRepository` | 어댑터 | 빈 인터페이스 → 파생 쿼리 1종. 커서는 `KlassQueryDslRepository`, 락은 Deferred (D-21) |
| `SecurityConfig` | 인프라 | `PUBLIC_ENDPOINTS` 에 조회 경로 추가, 명령 경로에 `ROLE_CREATOR` 부여 |
| ERD 정본 §3.2.5 | 문서 | `updated_at` 등재 + 변경 이력 |
| `openapi3.json` | 산출물 | 엔드포인트 6개 증가 (자동 생성) |

### 6.2 기존 소비자

| 리소스 | 소비자 | 영향 |
|--------|--------|------|
| `Klass` 엔티티 | `EnrollmentSchemaTest` (스키마 검증) | **검증 항목 추가 필요** — 컬럼 존재 확인 |
| `Klass` 엔티티 | `Enrollment`·`Waitlist` (`@ManyToOne`) | 없음 — 필드 추가는 기존 매핑에 영향 없음 |
| `Klass.open()` 팩토리 | 현재 호출자 없음 (테스트 제외) | 시그니처에 `updatedAt` 을 더할지 여부는 Design 판단 |
| `SecurityConfig` | 기존 인증 테스트, `AuthFlowIntegrationTest` | **검증 필요** — `anyRequest().authenticated()` 아래 규칙 순서가 바뀐다. `/v1/users/me` 등 기존 보호가 유지되는지 확인 |
| `DocumentationIntegrationTest` | 엔드포인트 개수 상수 | **갱신 필요** — 고칠 것은 개수가 아니라, 6개 테스트를 다 쓴 뒤 그 결과에 맞추는 것 |
| `KlassJpaRepository` | 없음 (아직 주입처 없음) | 없음 |

### 6.3 검증

- [ ] `SecurityConfig` 변경 후 기존 보호 경로가 여전히 401/403 을 반환하는지 확인
- [ ] `updated_at` 추가가 기존 7개 테이블 스키마 검증을 깨지 않는지 확인
- [ ] `openapi3.json` 이 파싱 가능한 상태로 생성되는지 확인 (description 다중행 함정 — `generatedDocument` Copy 태스크에 filter 금지)

---

## 7. 아키텍처 고려사항

### 7.1 프로젝트 레벨

| 레벨 | 선택 |
|------|:----:|
| Starter | ☐ |
| **Dynamic** | ☑ |
| Enterprise | ☐ |

기존 헥사고날 구조를 그대로 따른다. 새 아키텍처 판단이 아니라 **기존 규약의 이행**이다.

### 7.2 주요 아키텍처 결정 (Design 에서 확정)

| 결정 | 후보 | 잠정 | 근거 |
|------|------|------|------|
| 인바운드 포트 분할 | 유즈케이스별 5개 / 명령·조회 2개 / 단일 1개 | **유즈케이스별** | `auth` 가 이미 `LoginUseCase`·`LogoutUseCase` 로 분할돼 있다. 일관성 |
| 부분 수정 표현 | ~~`Optional<T>` 필드 / null 허용 / JSON Merge Patch~~ | **불필요해짐** (Design D-25) | 수정이 전체 필수 수신이 되면서 "미지정"을 표현할 필요 자체가 사라졌다. 누락·`null`·공백은 400 이다 |
| 락 획득 지점 | 서비스 / 어댑터 | **어댑터** (`@Lock` + 포트 메서드 분리) | `application.service` 는 JPA 타입을 몰라야 한다 (의존 규칙) |
| 커서 응답 형태 | `CursorPage<T>` 공통 / 도메인별 | **공통** (`common/adapter/in/web/dto`) | 수강신청 목록도 같은 규격을 쓴다 |
| 도메인 예외 | `KlassError` 신설 / `CommonError` 재사용 | **신설** | `auth`·`user` 가 각자 `*Error` 를 갖는 관례 |
| `updated_at` nullable | NOT NULL(생성 시 채움) / NULL 허용 | Design 판단 | NOT NULL 이면 "한 번도 수정 안 됨"을 표현할 수 없고, NULL 이면 응답 DTO 가 null 을 다뤄야 한다 |

### 7.3 패키지 구조 (예상)

```
klass/
├── adapter/
│   ├── in/web/
│   │   ├── controller/KlassController.java
│   │   └── dto/{RegisterKlassRequest, UpdateKlassRequest,
│   │            ChangeKlassStatusRequest, KlassResponse, KlassSummaryResponse}.java
│   └── out/persistence/
│       ├── KlassJpaRepository.java      (확장)
│       └── KlassRepositoryAdapter.java  (신규)
├── application/
│   ├── dto/{RegisterKlassCommand, UpdateKlassCommand,
│   │        ChangeKlassStatusCommand, KlassResult, KlassPageResult}.java
│   ├── port/in/{RegisterKlass, UpdateKlass, ChangeKlassStatus,
│   │            FindKlass, ListKlass}UseCase.java
│   ├── port/out/{KlassCommandPort, KlassQueryPort}.java
│   └── service/KlassService.java
└── domain/
    ├── Klass.java                       (확장)
    ├── KlassStatus.java                 (전이 규칙 추가 검토)
    └── error/KlassError.java            (신규)

common/adapter/in/web/dto/CursorPage.java  (신규 — 도메인 공용)
```

---

## 8. 컨벤션 전제

### 8.1 이미 확정된 것

- [x] `CLAUDE.md` 코딩 규약 (boolean `is` 접두어, `_at`/`_on`/`_days`, ENUM STRING, `Clock` 주입, 한국어 주석 + `Design Ref: §n`)
- [x] ERD 정본이 스키마 정본
- [x] 커밋 규약 (한국어 제목, 본문에 "왜")
- [x] 헥사고날 의존 규칙 표

### 8.2 이번에 새로 정할 것

| 항목 | 현재 | 정할 것 | 우선순위 |
|------|------|---------|:--------:|
| 커서 페이지네이션 응답 규격 | 없음 (첫 목록 API) | `data.items` / `data.hasNext` / `data.nextCursor` 필드명 확정 | High |
| 수정 요청 규격 | 없음 (첫 PATCH) | ~~미지정 필드 표현 방식~~ — 전 필드 필수로 확정 (D-25) | High |
| 선택적 인증 경로 규칙 | 없음 | `SecurityConfig` 에서 `PUBLIC_ENDPOINTS` 와 구분해 표기 | Medium |
| 도메인 상태 전이 예외 코드 체계 | `auth`·`user` 만 존재 | `KlassError` 코드 명명 | Medium |

### 8.3 환경 변수

이번 사이클에서 추가되는 것 없음. `app.enrollment.*` 4종은 수강신청 사이클 소관이다.

---

## 9. 다음 단계

1. [ ] 설계 문서 작성 — `/pdca design klass-management`
2. [ ] Design 에서 §7.2 잠정 결정 4건 확정 (부분 수정 표현, `updated_at` nullable, 커서 규격, 에러 코드)
3. [x] ~~`JwtAuthenticationFilter` 무토큰 경로 동작 확인~~ — **확인 완료.** 필터가 이미 무토큰 요청을 통과시킨다. Design 은 `principal == null` 처리 규약만 정하면 된다
4. [ ] 구현 — `/pdca do klass-management`

---

## 변경 이력

| 버전 | 날짜 | 변경 | 작성자 |
|------|------|------|--------|
| 0.1 | 2026-09-02 | 최초 작성. 요구사항 확인 2회(목록 분리 / 경로·페이지네이션·전이 범위·`updated_at`) 반영 | developer2@lulumedic.com |
| 0.5 | 2026-09-02 | **취소 가능 기간의 `DRAFT` 전용 변경 규칙 등재** (FR-17, Design §12 D-26). 취소 가능 기간은 수강생과의 약속이므로 신청자가 생긴 뒤의 사후 변경은 이미 신청한 사람에게 불리하다 — `DRAFT` 는 신청 자체가 불가능해(ERD §2.2) 그때까지만 열어 둔다. **같은 값 재전송은 no-op 으로 허용**한다: FR-02 의 전체 필수 수신에서는 모든 요청이 이 필드를 싣고 오므로 무조건 거부하면 `OPEN` 강의를 아예 수정할 수 없게 된다. §4.3 에 S9 추가. 구현·테스트(L1 6건 · L2 2건 · L3 1건 · L4 2건) 완료 상태를 반영 | developer2@lulumedic.com |
| 0.4 | 2026-09-02 | **수정 방식 개정 — 부분 수정 → 전체 필수 수신** (Design §12 D-25). §3.3 의 근거가 뒤집혔다: "PUT 은 안 바꾸는 필드까지 실어야 한다"를 단점으로 봤으나, **전체 값을 실어 보내는 것이 클라이언트의 정상 동작**임이 사용자 확정 사항이다. 그러면 누락·`null`·공백은 입력 오류이며 부분 수정 규격은 그것을 조용히 무시한다. FR-02 · §3.3 · §4.2 L2 · §7.2 · §8 미해결 항목 정정. `PATCH` 경로는 유지 | developer2@lulumedic.com |
| 0.3 | 2026-09-02 | Check 단계 반영 — FR-06(비관적 락) 상태를 `Deferred (Design D-21)` 로. 막을 상대인 수강신청이 2차 범위라 지금은 락이 아무것도 직렬화하지 않는다. §4.2 L2 의 "락 조회" 항목도 함께 정정. Context Anchor 의 "전이 4종"은 D-18(`DRAFT→CLOSED` 허용)로 3종이 돼 수치를 뺐다 | developer2@lulumedic.com |
| 0.2 | 2026-09-02 | 선택적 인증 리스크 정정 — `JwtAuthenticationFilter` 가 무토큰을 이미 통과시킴을 확인. 실제 과제는 `principal == null` 처리로 좁혀짐 | developer2@lulumedic.com |
