# Archive — 2026-09

완료된 PDCA 사이클의 문서를 보관한다. **여기 있는 문서는 그 시점의 기록이며 갱신하지 않는다.**
현재 유효한 스키마·컨벤션은 `docs/02-design/features/class-enrollment-erd.design.md`(ERD 정본)를 본다.

| 기능 | 완료일 | Match Rate | 이터레이션 | 문서 |
|------|:------:|:----------:|:---------:|------|
| [enrollment-management](./enrollment-management/) | 2026-09-04 | **100%** (최초 98%) | 1 | [plan](./enrollment-management/enrollment-management.plan.md) · [design](./enrollment-management/enrollment-management.design.md) · [analysis](./enrollment-management/enrollment-management.analysis.md) · [report](./enrollment-management/enrollment-management.report.md) |
| [klass-management](./klass-management/) | 2026-09-02 | **97%** (최초 93%) | 2 | [plan](./klass-management/klass-management.plan.md) · [design](./klass-management/klass-management.design.md) · [analysis](./klass-management/klass-management.analysis.md) · [report](./klass-management/klass-management.report.md) |
| [project-setup](./project-setup/) | 2026-09-01 | **100%** (최초 97%) | 1 | [plan](./project-setup/project-setup.plan.md) · [design](./project-setup/project-setup.design.md) · [analysis](./project-setup/project-setup.analysis.md) · [report](./project-setup/project-setup.report.md) |

## enrollment-management

수강신청 도메인 전 계층. 신청 · 결제확정 · 취소 · 대기열(등록 · 승격 · 포기) · 조회 4종.
**`klass.enrollment_count` 를 쓰는 최초의 코드**가 여기서 생겼다 — 그 전까지 값이 항상 0이라
`ck_klass_count` 도 도달 불가였다.

**여전히 참조 가치가 있는 것**

- **`enrollment-management.report.md` §7.2 함정 6종** — 이 사이클에서 처음 밟은 것들.
  그중 둘은 **조용히 깨진다**: 중첩 `record` 프로퍼티가 블록 누락 시 예외가 아니라 `null`
  이라 **기동은 성공하고 첫 사용에서 NPE**, `FOR UPDATE` + 1건 제한이 상위 락 없이는
  **낡은 행을 돌려준다**. 나머지는 `restdocs-api-spec` 의 Bearer payload 파싱 ·
  `Map.of` 10쌍 상한 · Spring Data 4 의 `PropertyReferenceException` 이동 · 파생 쿼리
  수식어 위치
- **`enrollment-management.design.md` §4.1.1 스파이크 판정** — 락·파생쿼리·프로퍼티 전제
  5종을 구현 전에 실측한 기록. **실제로 생성된 SQL 이 박혀 있다**
  (`fetch first ? rows only for update`). 실 DB 전환 시 대조 기준
- **`enrollment-management.design.md` §12 divergence 18건 (D-29 ~ D-46)** — 특히 **D-32**
  (만료 회수 미구현)와 **D-33**(정원 증가 승격 미구현)은 *왜 안 만들었고 언제 되살려야 하는지*
  가 적혀 있다. **D-42~D-45 는 문서가 코드보다 틀렸던 4건**이다
- **`enrollment-management.report.md` §6.2** — Check 에서 "문서를 코드에 맞춘다"로 끝낸 것이
  얕았던 경위. 사용자 질문으로 되짚어 **D-46(패키지 결합 절반 제거)** 이 나왔다
- **`enrollment-management.analysis.md` §2.4** — 락 획득 순서 7종과 §4.1 예외 2건의 대조표

**결과 요약**

| 항목 | 값 |
|------|-----|
| Success Criteria | 11/11 |
| FR | 14/14 + FR-16 의도된 부분충족 (D-32) |
| Match Rate | 100% (Check 98% → Act 후 100%) |
| 테스트 | 431건 / 실패 0 (이번 사이클 +224) |
| API | 9개 — `openapi3.json` path 16 / 오퍼레이션 19 |
| 스키마 변경 | **없음** — project-setup 이 만든 것을 처음 실제로 썼다 |
| 커밋 | 11개 |
| 잔여 | **R-01 만료 회수 부재 (High)** — §4.2 참조 |

**핵심 결정**

| ID | 결정 | 이유 |
|----|------|------|
| D-29 | 좌석 유스케이스 **단일 서비스** | ERD §4.1 이 `klass` 행을 트랜잭션 경계 루트로 지정 → 세 테이블은 논리적 단일 애그리거트. 쪼개면 승격이 락 밖에서 실행될 여지가 생긴다 |
| D-30 | 개설자 차단 **3지점** | 신청·대기등록만 막으면 승격이 우회로가 된다 |
| D-31 | `ends_on` 경과 후 취소 차단 | 종료일 검사가 기간 검사보다 **먼저** — 사용자에게 다른 이야기를 해야 한다 |
| D-46 | `WaitlistQuery` 분리 | klass-management **D-24 와 같은 모순**의 재발 — 쓰지도 않는 필드 때문에 남의 패키지를 경유 |

**다음 사이클로 이월 — 좌표**

| 항목 | 우선도 | 좌표 |
|------|:------:|------|
| **PENDING 만료 회수** | 🔴 | 회수 로직은 `EnrollmentService.cancel` 경로에 이미 있다. **트리거만 붙이면 된다.** `expires_at` 은 정확히 채워져 있고 관측 쿼리는 `EnrollmentFlowIntegrationTest` 정합성 절에 있다 |
| `cancel_reason` 구분 | 🟡 | 만료 회수가 생기면 취소 원인이 둘이 된다 (ERD §2 ⑦) |
| 시나리오 #5 보강 | 🟢 | 대기자 3명 + 2순위 `WAITING` 잔존 단정 |

---

## klass-management

강의(Klass) 도메인에 API 6개를 열어 전 계층을 완성한 2차 작업. 등록 · 전체 교체 · 상태 전이 ·
조회 3종(상세 · 공개 목록 · 내 강의 목록). **수강신청은 enrollment-management 에서 완료됐다.**

**여전히 참조 가치가 있는 것**

- **`klass-management.design.md` §12 divergence 15건 (D-14 ~ D-28)** — ERD 정본과 CLAUDE.md 를
  좁히거나 예외를 둔 지점. 특히 **D-21(락 제거)** 은 수강신청을 붙일 때 되살릴 좌표를
  코드 세 곳에 남긴 근거이고, **D-25·D-27·D-28** 은 수정 API 의 성격을 정한 결정이다
- **`klass-management.report.md` §7.2 함정 6종** — `hasRole("ROLE_X")` · `@Validated` ×
  `@RequestParam` · `SecurityConfig` 규칙 순서 · `@WebMvcTest`+`addFilters=false` ·
  RestDocs `requestFields` · 텍스트 블록의 `\n`. **전부 컴파일·테스트를 통과하면서 틀린다**
- **`klass-management.report.md` §5.2** — "통과하지만 아무것도 검증하지 않는" 테스트 10종의
  유형 분류. 이 사이클에서 발견한 문제 중 **테스트 실패로 드러난 것은 하나도 없었다**
- **`klass-management.analysis.md` §3.2** — 정책이 만든 파생 결과. `CAPACITY_BELOW_ENROLLMENT`
  가 API 로는 도달 불가해진 경위(정원은 DRAFT 에서만 바뀌고 DRAFT 는 `enrollment_count == 0`)
- **`klass-management.report.md` §6.2** — 서브에이전트가 확정 결정을 뒤집은 경위와 커밋 미룸의 대가

**결과 요약**

| 항목 | 값 |
|------|-----|
| Success Criteria | 4/4 |
| FR | 16/17 (FR-06 비관적 락 Deferred — D-21) |
| Match Rate | 97% (구조 100 · 기능 94 · 계약 100 · 런타임 100) |
| 테스트 | 207건 / 실패 0 |
| API | 6개 — `openapi3.json` path 8 / 오퍼레이션 10 |
| 스키마 변경 | `klass.updated_at` 추가 · `klass.description` NOT NULL (ERD 정본 v1.11) |
| 커밋 | 4개 (`feat/klass-management`) |
| 잔여 | 같은 상태 재호출의 HTTP 레벨 검증 공백 1건(위험 낮음) |

**3차(수강신청)로 이월했던 것 — 전건 해소됨**

| 항목 | 좌표 |
|------|------|
| `findByIdForUpdate` 복구 | ✅ `findWithLockById` 로 복원 (enrollment D-21 해소) |
| `enrollment_count` 증감 | ✅ `Klass.occupySeat`/`releaseSeat` 신설 |
| 정원 증가 시 대기열 승격 | ✅ **필요 없음으로 종결** — `DRAFT` 전용이라 도달 불가 (enrollment D-33) |
| `CLOSED` 전이 시 잔여 `WAITING` 정리 | ✅ `KlassService.changeStatus` 가 port.in 으로 위임 (enrollment D-29) |

---

## project-setup

확정된 ERD(v1.10)를 실행 가능한 Spring Boot 4 프로젝트로 세운 1차 작업. 빌드 환경 · 7개 테이블 ·
인증 도메인 전체 · 문서 파이프라인까지. 수강신청 비즈니스 로직은 2차로 분리했다.

**여전히 참조 가치가 있는 것**

- **`project-setup.design.md` §12 divergence 13건** — 인증 원본(`Chals85/sample-jwt-authentication`)
  대비 달라진 지점과 근거. 원본과 다른 코드를 만났을 때 여기를 먼저 본다
- **§3.6.1 생성 컬럼** — H2 가 `STORED` 를 거부한 판정(D-9). 실 DB 전환 시 되붙여야 한다
- **`project-setup.analysis.md` §6.2 회고** — FK 검증이 빠져 Critical 이 늦게 발견된 경위
- **`project-setup.report.md` §4** — 2차로 이월한 항목과 사유

**결과 요약**

| 항목 | 값 |
|------|-----|
| Success Criteria | 10/10 |
| FR | 15/15 |
| 테스트 | 101건 / 실패 0 |
| 소스 | main 85 · test 16 |
| 스키마 | 테이블 7 · ENUM 6 · CHECK 10 · FK 6 · 인덱스 6 · UNIQUE 3 |
| 잔여 이슈 | G-2 포트 우회(기능 영향 없음) · G-3 Lombok 경고(조치 불가) |
