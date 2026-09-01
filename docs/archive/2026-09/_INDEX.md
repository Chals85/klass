# Archive — 2026-09

완료된 PDCA 사이클의 문서를 보관한다. **여기 있는 문서는 그 시점의 기록이며 갱신하지 않는다.**
현재 유효한 스키마·컨벤션은 `docs/02-design/features/class-enrollment-erd.design.md`(ERD 정본)를 본다.

| 기능 | 완료일 | Match Rate | 이터레이션 | 문서 |
|------|:------:|:----------:|:---------:|------|
| [project-setup](./project-setup/) | 2026-09-01 | **100%** (최초 97%) | 1 | [plan](./project-setup/project-setup.plan.md) · [design](./project-setup/project-setup.design.md) · [analysis](./project-setup/project-setup.analysis.md) · [report](./project-setup/project-setup.report.md) |

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
