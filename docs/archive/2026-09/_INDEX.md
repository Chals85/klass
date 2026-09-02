# Archive — 2026-09

완료된 PDCA 사이클의 문서를 보관한다. **여기 있는 문서는 그 시점의 기록이며 갱신하지 않는다.**
현재 유효한 스키마·컨벤션은 `docs/02-design/features/class-enrollment-erd.design.md`(ERD 정본)를 본다.

| 기능 | 완료일 | Match Rate | 이터레이션 | 문서 |
|------|:------:|:----------:|:---------:|------|
| [klass-management](./klass-management/) | 2026-09-02 | **97%** (최초 93%) | 2 | [plan](./klass-management/klass-management.plan.md) · [design](./klass-management/klass-management.design.md) · [analysis](./klass-management/klass-management.analysis.md) · [report](./klass-management/klass-management.report.md) |
| [project-setup](./project-setup/) | 2026-09-01 | **100%** (최초 97%) | 1 | [plan](./project-setup/project-setup.plan.md) · [design](./project-setup/project-setup.design.md) · [analysis](./project-setup/project-setup.analysis.md) · [report](./project-setup/project-setup.report.md) |

## klass-management

강의(Klass) 도메인에 API 6개를 열어 전 계층을 완성한 2차 작업. 등록 · 전체 교체 · 상태 전이 ·
조회 3종(상세 · 공개 목록 · 내 강의 목록). 수강신청은 3차로 남았다.

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

**3차(수강신청)로 이월 — 좌표가 코드에 있다**

| 항목 | 좌표 |
|------|------|
| `findByIdForUpdate` 복구 | `KlassService.loadForCommand` javadoc (D-21) |
| `enrollment_count` 증감 | `Klass` 신설 |
| 정원 증가 시 대기열 승격 | `Klass.changeCapacity` (D-16) |
| `CLOSED` 전이 시 잔여 `WAITING` 정리 | `Klass.close` (D-16) |

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
