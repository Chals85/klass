# klass QA 리포트 — 강의 수강신청 + JWT 인증

> bkit QA Phase 산출물. 테스트 플랜: `class-enrollment-erd.test-plan.md`
> 실행일: 2026-09-05 · 대상: master `745103a`

## 실행 증적

| 증적 | 파일 | 내용 |
|---|---|---|
| L1 | [`evidence/l1-test-results.md`](evidence/l1-test-results.md) | JUnit XML 집계 — 클래스별 건수·실행 시각·소요 시간 (477건) |
| L2 | [`evidence/l2-api-execution.log`](evidence/l2-api-execution.log) | **56 TC 전건의 HTTP 요청·응답 원문** + TC 별 판정. 실행 시각·대상 커밋 헤더 포함 |
| L2 스크립트 | [`evidence/l2-qa-script.sh`](evidence/l2-qa-script.sh) | 실행에 쓴 스크립트 원본 — 서버 신규 기동 직후 1회 실행으로 재현 |

최초 실행(TC 기대값 오류 2건 포함, 54/56)의 판정·정정 후, **정정된 TC 로 초기 DB 에서 전량
재실행해 56/56 을 확인한 기록**이 위 증적이다.

## 결과 요약

| 레벨 | 내용 | 결과 |
|:-:|---|---|
| PRE-SCAN | bkit pre-release 스캐너 | **N/A** — JS 코드베이스 전용 스캐너라 Java 프로젝트에 스캔 대상 없음 (검출된 CRITICAL 6건은 전부 bkit 플러그인 자체 파일) |
| L1 | 자동 테스트 스위트 (`test` + `documentationTest`) | ✅ **477건 전건 통과** (failures 0 · errors 0 · skipped 0) |
| L2 | 실서버 블랙박스 API QA — 56 TC | ✅ **56/56 PASS** (최초 실행 54/56 → FAIL 2건 모두 TC 기대값 오류로 판정·정정 후 초기 DB 에서 전량 재실행) |
| L3~L4 | E2E / UX Flow (Chrome MCP) | **N/A** — UI 없는 API 서버 |
| L5 | 문서 산출물 | ✅ openapi3.json 유효(paths 16 / operations 19) · Redoc·Swagger UI 서빙 확인 |

**종합 판정: PASS** — 설계 정본 §8 검증 시나리오 중 L2 재현 가능 전건과 §7 권한 매트릭스 전건이 기대대로 동작한다.

## L2 실행 상세

서버: `./gradlew bootRun` 신규 기동(H2 초기 상태). 계정: `chals` · `chals2` · `creator`.

### 인증·사용자 (10/10 PASS)

| TC | 결과 | 확인된 것 |
|---|:-:|---|
| AUTH-01 | ✅ | 로그인 → Access·Refresh 쌍 발급 |
| AUTH-02·03 | ✅ | 비밀번호 오류·미존재 사용자가 **완전히 동일한 401 응답** — 계정 열거 방지 유효 |
| AUTH-04 | ✅ | Refresh 회전 재발급 |
| AUTH-05a·b | ✅ | 폐기된 Refresh 재사용 감지 + **사용자 전체 토큰 일괄 무효화** (§8 #16) |
| AUTH-06 | ✅ | 로그아웃 후 Access 401 `TOKEN_REVOKED` — jti 블랙리스트 유효 (§8 #15) |
| AUTH-07 | ✅ | Refresh 로 API 접근 401 `TOKEN_TYPE_MISMATCH` — typ 혼동 차단 |
| AUTH-08 | ✅ | 무토큰 401 `UNAUTHENTICATED` |
| USER-01 | ✅ | `/v1/users/me` 본인 정보 |

### 강의 (13/13 PASS)

| TC | 결과 | 확인된 것 |
|---|:-:|---|
| KLASS-01·02 | ✅ | CREATOR 만 등록 가능(201 DRAFT), USER 는 403 |
| KLASS-03·04·05 | ✅ | 공개 목록에 DRAFT 미노출 · 타인 DRAFT 상세 **404**(403 아님) · 본인 DRAFT 200 |
| KLASS-06 | ✅ | capacity=0 → 400 `VALIDATION_ERROR` |
| KLASS-07~11 | ✅ | 전이 화이트리스트: DRAFT→OPEN 성공 · 신청자 있는 OPEN→DRAFT 409 · CLOSED→OPEN 409 · CLOSED→DRAFT 409 · 비소유자 403 |
| KLASS-12·13 | ✅ | 수강생 목록 — 비소유자 403, 소유자 200 (§8 #17) |

### 수강신청 (16/16 PASS)

| TC | 결과 | 확인된 것 |
|---|:-:|---|
| ENR-01 | ✅ | 신청 → PENDING/DIRECT/expiresAt (§4.2) |
| ENR-02 | ✅* | DRAFT 신청 409 `KLASS_NOT_OPEN` — *TC 기대값(404) 정정 후 판정. 아래 관측 #1 참조 |
| ENR-03·04·05 | ✅ | 중복 409 · 개설자 본인 신청 403 · **정원 초과 409 + 자동 대기열 등록 없음** (§8 #2·3) |
| ENR-06·07·08 | ✅ | 본인 확정 200 CONFIRMED · 타인 확정 403 · 재확정 409 (§8 #27·33) |
| ENR-09·10·11 | ✅ | 본인 취소 200 · 타인 취소 403 · CANCELLED 재확정/재취소 각 409 (§8 #20·32·34) |
| ENR-12 | ✅ | 취소 후 재신청 201 — 부분 유니크(active_user_key) 동작 (§8 #4) |
| ENR-13·14 | ✅ | CLOSED 신규 신청 409 · **CLOSED 후 기존 PENDING 결제는 성공** (§8 #10·11, §2.1) |
| ENR-15·16 | ✅ | 내 목록 본인 것만 · 타인 상세 403 `NOT_ENROLLMENT_OWNER` |

### 대기열 (12/12 PASS)

| TC | 결과 | 확인된 것 |
|---|:-:|---|
| WL-01~04 | ✅ | 만석 대기 201(position=1) · 자리 있으면 409 `WAITLIST_SEAT_AVAILABLE` · 중복 대기 409 · 활성 신청자 대기 409 (§4.5·§8 #23) |
| WL-05a·b·c | ✅ | **취소 → 1순위 승격 전 과정**: 대기 PROMOTED · 새 enrollment PENDING/`WAITLIST` 생성 · **좌석 순변화 0** (직후 신청이 CAPACITY_FULL) (§8 #5) |
| WL-06·07 | ✅ | PROMOTED 포기 409 · WAITING 포기 200 (§8 #28·35) |
| WL-08·09 | ✅ | 타인 포기 403 · 포기 후 재대기 201 (§8 #36) |
| WL-10 | ✅ | **OPEN→CLOSED 전환 시 잔여 WAITING 일괄 CANCELLED** (§4.8 5번) |

### 문서 (2/2 PASS)

DOC-01 ✅* openapi3.json 유효, paths 16 / operations 19 (*TC 단언이 operation 수를 path 수로 오기 — 정정) · DOC-02 ✅ api-guide.html·api-test.html 200

## 최초 FAIL 2건의 판정 근거

| TC | 관측 | 판정 |
|---|---|---|
| ENR-02 | 409 `KLASS_NOT_OPEN` (기대 404) | **TC 오류.** 아카이브 enrollment-management 설계 §4 가 `status != OPEN → KLASS_NOT_OPEN (409)` 를 명시. 404 은닉 규약은 강의 조회 전용. 구현은 정본 적합 |
| DOC-01 | paths 16 (기대 19) | **TC 오류.** 19 는 operation 수. `/v1/klasses` 등 3개 path 가 복수 메서드를 가져 path 는 16 이 맞다 |

## 관측 사항 (결함 아님 · 후속 검토 후보)

1. **DRAFT 존재 추정 가능성** — 신청 엔드포인트가 DRAFT 에 409(`KLASS_NOT_OPEN`), 미존재에 404 를 돌려주므로, 연속 정수 id 를 훑으면 **비공개 초안의 존재 여부를 구분할 수 있다.** 조회는 404 로 은닉하는데(§7) 신청 경로가 오라클이 된다. 아카이브 설계 §7.5 는 신청 id 에 대해서만 이 논점을 다뤘고 DRAFT 강의 id 에 대한 논의는 없다. 은닉을 완성하려면 신청·대기 등록에서도 타인 DRAFT 를 `KLASS_NOT_FOUND` 로 답해야 한다.
2. **L2 재현 불가 시나리오는 L1 매핑으로 커버** — 동시성(§8 #1·6·8), 만료 배치(#7·29), DB CHECK(#31), 타 크리에이터 소유권(#17, 기본 CREATOR 계정 1개) 은 통과한 477건 스위트가 담당. 테스트 플랜 §3 에 매핑표.
3. **승격 알림 부재 위험(기지)** — CLAUDE.md 범위 경계에 기재된 대로, 승격 사실을 모른 채 순차 만료되면 대기열 소진 + 빈 좌석. 이번 QA 범위 밖(ERD §4.8).

## 재현 방법

```bash
./gradlew test documentationTest                      # L1
./gradlew bootRun                                     # 별도 터미널 (신규 기동 = 초기 DB)
EVIDENCE=/tmp/l2-evidence.log \
  bash docs/05-qa/evidence/l2-qa-script.sh            # L2 (56 TC, 기동 직후 1회 실행 전제)
```

TC 정의의 정본은 테스트 플랜이고, 스크립트는 그 실행체이자 증적이다. 순서 의존적(앞 TC 가
만든 강의·신청을 뒤 TC 가 사용)이므로 사용한 DB 에 재실행하면 기대값이 어긋난다.
