# klass QA 테스트 플랜 — 강의 수강신청 + JWT 인증

> bkit QA Phase 산출물. TC 근거는 ERD 설계 정본 `docs/02-design/features/class-enrollment-erd.design.md`
> (§7 권한 검증 지점 · §8 검증 시나리오 36건)과 CLAUDE.md 의 상태코드 규약이다.
> 작성일: 2026-09-05

## 1. 테스트 레벨 구성

bkit L1~L5 를 이 프로젝트(API 서버, UI 없음)에 맞게 조정한다.

| bkit 레벨 | 이 프로젝트에서의 의미 | 실행 방법 |
|:-:|---|---|
| L1 | 자동 테스트 스위트 (도메인·어댑터·컨트롤러·통합·스키마) | `./gradlew test` |
| L2 | **실서버 블랙박스 API QA** — 본 문서의 TC | `bootRun` + curl |
| L3~L4 | E2E / UX Flow (Chrome MCP) | **N/A** — UI 없는 API 서버 |
| L5 | 문서 산출물 서빙 검증 | `documentationTest` + curl(DOC TC) |

동시성(§8 #1·6·8)·만료 배치(#7·29)·DB 제약(#31)·토큰 만료처럼 **시간·경합 제어가 필요한
시나리오는 L2 로 재현하지 않고 L1 스위트에 매핑**한다(§3). 실서버에서 30분 만료를 기다리거나
락 경합을 curl 로 만드는 것은 재현성이 없다.

## 2. L2 블랙박스 TC

- 서버: `./gradlew bootRun` (H2 인메모리 — 기동마다 초기화되므로 TC 는 순서 의존적으로 설계)
- 계정: `chals`/`test`(USER) · `chals2`/`test`(USER) · `creator`/`test`(USER+CREATOR)
- 검증 항목: HTTP 상태코드 + `error.code`(실패 시) + `data` 필드(성공 시)
- 응답 봉투: `{ "success": bool, "data": ..., "error": { "code", "message", ... } }`

### 2.1 인증 (AUTH)

| TC | 시나리오 | 기대 결과 | 근거 |
|---|---|---|---|
| AUTH-01 | 정상 로그인 (`chals`/`test`) | 200 · accessToken·refreshToken 발급 | §4.7 |
| AUTH-02 | 비밀번호 오류 로그인 | 401 `INVALID_CREDENTIALS` | — |
| AUTH-03 | 존재하지 않는 사용자 로그인 | 401 `INVALID_CREDENTIALS` — **AUTH-02 와 동일 응답**(계정 열거 방지) | DomainAuthenticationProvider |
| AUTH-04 | Refresh 로 토큰 재발급 | 200 · 새 토큰 쌍 | §4.7 |
| AUTH-05 | **폐기된 Refresh 재사용** (재발급에 쓴 옛 토큰 재제출) | 401 `REFRESH_TOKEN_REUSED` + **사용자 전체 토큰 무효화** — 직후 새 Refresh 재발급도 실패 | §8 #16 |
| AUTH-06 | 로그아웃 후 남은 Access 로 보호 API 호출 | 401 `TOKEN_REVOKED` | §8 #15 |
| AUTH-07 | Refresh 토큰으로 보호 API 호출 (typ 혼동) | 401 `TOKEN_TYPE_MISMATCH` | §4.2 사전 |
| AUTH-08 | 토큰 없이 보호 API 호출 | 401 `UNAUTHENTICATED` | §7 |
| USER-01 | `GET /v1/users/me` | 200 · username·roles 일치 | §7 |

### 2.2 강의 (KLASS)

| TC | 시나리오 | 기대 결과 | 근거 |
|---|---|---|---|
| KLASS-01 | `creator` 강의 등록 | 201 · status=`DRAFT` | §3.4 |
| KLASS-02 | `chals`(ROLE_USER) 강의 등록 | 403 `ACCESS_DENIED` | §7 |
| KLASS-03 | 비인증 공개 목록 조회 | 200 · **DRAFT 미노출** | §7 선택적 인증 |
| KLASS-04 | 타인이 DRAFT 상세 조회 | **404** `KLASS_NOT_FOUND` (403 아님 — 초안은 존재가 비밀) | CLAUDE.md 상태코드 규약 |
| KLASS-05 | 개설자 본인의 DRAFT 상세 조회 | 200 | §7 |
| KLASS-06 | 유효성 위반 등록 (capacity=0) | 400 `VALIDATION_ERROR` | §3.5 |
| KLASS-07 | `DRAFT → OPEN` 전이 | 200 · status=`OPEN` | §8 #26 |
| KLASS-08 | `chals` 가 강의 상태 변경 시도 | 403 (권한) | §8 #30 유사 |
| KLASS-09 | `OPEN → DRAFT` — 신청자 존재 | 409 `INVALID_KLASS_STATUS_TRANSITION` | §8 #19 |
| KLASS-10 | `CLOSED → OPEN` 전이 | 409 `INVALID_KLASS_STATUS_TRANSITION` | §8 #25 |
| KLASS-11 | `CLOSED → DRAFT` 전이 | 409 `INVALID_KLASS_STATUS_TRANSITION` | §8 #24 |
| KLASS-12 | 타인 강의 수강생 목록 조회 (`chals`) | 403 | §8 #17 |
| KLASS-13 | 개설자 수강생 목록 조회 | 200 · 신청자 포함 | §7 |

### 2.3 수강신청 (ENR)

| TC | 시나리오 | 기대 결과 | 근거 |
|---|---|---|---|
| ENR-01 | OPEN 강의 신청 | 201 · status=`PENDING` · source=`DIRECT` · expiresAt 존재 | §4.2 |
| ENR-02 | DRAFT 강의 신청 | 409 `KLASS_NOT_OPEN` — 404 은닉 규약은 **조회 전용**이다 (아카이브 enrollment-management 설계 §4 "status != OPEN → KLASS_NOT_OPEN (409)") | §8 #22 |
| ENR-03 | 중복 신청 | 409 `DUPLICATE_ENROLLMENT` | §8 #3 |
| ENR-04 | 개설자 본인 강의 신청 | 403 `SELF_ENROLLMENT_FORBIDDEN` | EnrollmentError |
| ENR-05 | 정원 초과 신청 | 409 `KLASS_CAPACITY_FULL` — **자동 대기열 등록 없음** | §8 #2 |
| ENR-06 | 결제 확정 (PENDING, 만료 전) | 200 · status=`CONFIRMED` | §4.3 |
| ENR-07 | 타인 PENDING 결제 확정 | 403 `NOT_ENROLLMENT_OWNER` | §8 #33 |
| ENR-08 | CONFIRMED 재확정 | 409 `INVALID_ENROLLMENT_STATUS_TRANSITION` | §3.4 |
| ENR-09 | 사용자 취소 (PENDING) | 200 · status=`CANCELLED` · 좌석 반납 | §8 #34 |
| ENR-10 | 타인 신청 취소 | 403 `NOT_ENROLLMENT_OWNER` | §8 #32 |
| ENR-11 | CANCELLED 재확정/재취소 | 각각 409 | §8 #20 |
| ENR-12 | 취소 후 같은 강의 재신청 | 201 (부분 유니크 — CANCELLED 는 제외) | §8 #4 |
| ENR-13 | CLOSED 강의 신규 신청 | 409 `KLASS_NOT_OPEN` | §8 #11 |
| ENR-14 | CLOSED 전환 후 기존 PENDING 결제 (만료 전) | 200 `CONFIRMED` | §8 #10 |
| ENR-15 | 내 신청 목록 | 200 · 본인 것만 | §7 |
| ENR-16 | 타인 신청 상세 조회 | 403 또는 404 | §7 |

### 2.4 대기열 (WL)

| TC | 시나리오 | 기대 결과 | 근거 |
|---|---|---|---|
| WL-01 | 만석 강의 대기 등록 | 201 · status=`WAITING` · position=1 | §4.5 |
| WL-02 | 자리 있는 강의 대기 등록 | 409 `WAITLIST_SEAT_AVAILABLE` | §4.5 5번 |
| WL-03 | 중복 대기 등록 | 409 `DUPLICATE_WAITLIST` | §4.5 4번 |
| WL-04 | 활성 신청 보유자의 대기 등록 | 409 `DUPLICATE_ENROLLMENT` | §8 #23 |
| WL-05 | **취소 → 1순위 승격** (만석·대기 1명에서 기존 신청 취소) | 대기 status=`PROMOTED` · 새 enrollment `PENDING`/source=`WAITLIST` · 좌석 순변화 0 | §8 #5·#34 |
| WL-06 | PROMOTED 대기 포기 시도 | 409 `WAITLIST_NOT_WAITING` | §8 #28 |
| WL-07 | WAITING 대기 정상 포기 | 200 · status=`CANCELLED` | §8 #35 |
| WL-08 | 타인 대기 포기 | 403 `NOT_WAITLIST_OWNER` | §7 |
| WL-09 | 포기 후 재대기 | 201 (waiting_user_key NULL) — 순번은 gap 이후 값 | §8 #36 |
| WL-10 | `OPEN → CLOSED` 시 잔여 대기 일괄 정리 | 대기 status=`CANCELLED` | §4.8 5번 |

### 2.5 문서 산출물 (DOC — L5)

| TC | 시나리오 | 기대 결과 |
|---|---|---|
| DOC-01 | `GET /docs/openapi3.json` | 200 · 유효 JSON · **paths 16 / operations 19** (한 path 에 복수 메서드) |
| DOC-02 | `GET /docs/api-guide.html` · `/docs/api-test.html` | 200 |

## 3. L1 매핑 — L2 로 재현하지 않는 시나리오

| 설계 §8 | 시나리오 | 담당 테스트 |
|:-:|---|---|
| #1 | 잔여 1석 동시 100건 신청 | `EnrollmentFlowIntegrationTest` (동시성 절) |
| #6 | 취소 2건 동시 · 대기 1명 — 이중 승격 없음 | 〃 |
| #7·#29 | PENDING 만료 배치 회수 · 배치 전 만료 결제 거부 | 〃 + `ExpiredEnrollmentScheduler` 관련 L2/L4 |
| #8 | 결제 vs 만료 배치 경합 | 〃 |
| #14 | capacity < enrollment_count 수정 거부 | L1 `Klass` 도메인 + `EnrollmentSchemaTest`(`ck_klass_count`) |
| #21 | 부적격 1순위 건너뛰고 2순위 승격 | 승격 로직 서비스 테스트 |
| #31 | `revoked_at` 미설정 시 CHECK 위반 | `EnrollmentSchemaTest` · auth 스키마 검증 |
| — | 토큰 만료(30분)·취소 기간 초과(7일) | 시간 주입(`Clock`) 기반 단위 테스트 |
| #17 | **타 크리에이터** 소유권 검사 | L4 통합 — 기본 계정에 CREATOR 가 1명뿐이라 L2 재현 불가 |

## 4. 합격 기준

- L1: 스위트 전건 통과 (`test` + `documentationTest`)
- L2: 전 TC 의 상태코드·`error.code`·핵심 `data` 필드가 기대와 일치
- 하나라도 어긋나면 FAIL 로 기록하고 QA 리포트에 재현 절차를 남긴다
