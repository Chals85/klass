# project-setup 완료 보고서

> **Project**: klass
> **Version**: 1.0
> **Author**: developer2@lulumedic.com
> **Date**: 2026-09-01
> **Match Rate**: **100%** (최초 97% → Critical 1건 해소)
> **Status**: 완료

---

## Executive Summary

### 1.1 프로젝트 개요

확정된 ERD(v1.10)를 실행 가능한 Spring Boot 4 프로젝트로 세웠다. 인증은 검증된 자산
(`sample-jwt-authentication`)을 인용해 이식하고, 그 위에 수강 도메인 엔티티 3종을 얹었다.
수강신청 비즈니스 로직은 2차로 분리했다.

### 1.2 결과 요약

| 지표 | 결과 |
|------|------|
| Match Rate | **100%** |
| 테스트 | **101건 / 실패 0** |
| 소스 | main 85개 · test 16개 |
| 테이블 | 7/7 · ENUM 6/6 · CHECK 10/10 · FK 6/6 · 인덱스 6/6 · UNIQUE 3/3 |
| API | 엔드포인트 4/4, 런타임 계약 검증 13/13 |
| 이터레이션 | 1회 (Critical 1건) |

### 1.3 Value Delivered

| 관점 | 계획 | 실제 |
|------|------|------|
| **Problem** | ERD는 확정됐으나 코드가 한 줄도 없어 설계를 실행해 볼 수 없다 | **해소.** `./gradlew bootRun` 으로 기동해 로그인→토큰→보호자원 호출이 동작한다 |
| **Solution** | 검증 자산 인용 + ERD 7테이블 | **달성.** 원본 테스트 14개가 동등하게 통과하고, 7테이블이 제약·인덱스·FK 와 함께 생성된다 |
| **Function/UX Effect** | 문서 2종으로 API 확인 가능 | **달성.** `/docs/api-guide.html`(Redoc)·`/docs/api-test.html`(Swagger UI), 스펙에 엔드포인트 4종 |
| **Core Value** | 설계와 코드가 처음 만나는 지점 | **달성 + 확장.** 정원 불변식이 DB 제약으로 서 있어, 2차의 동시성 구현이 안전망 위에서 진행된다 |

---

## 1.4 Success Criteria 최종 상태

### Plan §4.1 Definition of Done — 6/6 ✅

| 기준 | 상태 | 근거 |
|------|:----:|------|
| FR-01 ~ FR-15 전부 구현 | ✅ | §3.1 참조 |
| `./gradlew build` 통과 | ✅ | BUILD SUCCESSFUL, 101건 |
| `bootRun` 후 로그인 → `/v1/users/me` 200 | ✅ | 런타임 검증 13/13 |
| 7개 테이블·제약 확인 | ✅ | 테이블 7 · CHECK 10 · FK 6 · 인덱스 6 · UNIQUE 3 |
| Redoc / Swagger UI 렌더링 | ✅ | 3종 200, 스펙 엔드포인트 4/4 |
| 인증 테스트 원본과 동등 통과 | ✅ | 원본 14개 전부 이식·통과 |

### Plan §4.2 Quality Criteria — 4/4 ✅

| 기준 | 상태 | 근거 |
|------|:----:|------|
| 우리 코드의 컴파일 경고 0건 | ✅ | 0건. Lombok JVM 경고는 조치 불가로 확인돼 Plan v0.3 에서 알려진 예외로 명시 |
| ERD 기계적 대조 누락 0 | ✅ | ENUM 6/6 · CHECK 10/10 · 테이블 7/7 · FK 6/6 |
| 컨벤션 7종 반영 | ✅ | boolean·시각·PK·FK·ENUM·명명·사용자 참조 컬럼 |
| `Design Ref` 주석 부착 | ✅ | 85개 파일 전부 |

**최종 달성률: 10/10 (100%)**

---

## 1.5 Decision Record Summary

| # | 결정 | 출처 | 준수 | 결과 |
|:-:|------|------|:----:|------|
| 1 | 검증 자산 인용 (인증 재설계 안 함) | Plan §7.2 | ✅ | 로직 무변경 이식, 테스트 14개 동등 통과. 재설계 비용 0 |
| 2 | Option C — 인용 + 컨벤션 정합 | Design §2.0 | ✅ | divergence 13건으로 통제. 이름만 바꾸고 로직 보존 |
| 3 | H2 (`MODE=MySQL`) | Plan §7.2 | ✅ | 외부 인프라 없이 기동. 단 `STORED`·예약어는 실 DB 전환 시 재확인 필요 |
| 4 | **D-1** boolean `is` 전 계층 통일 | 사용자 | ✅ | DB `is_enabled` ↔ API `data.isEnabled` 런타임 확인. **JPQL 문자열 함정**을 설계서가 예고해 막았다 |
| 5 | **D-9** 생성 컬럼 `STORED` 제거 | module-1 스파이크 | ✅ | H2 가 `STORED` 를 거부함을 사전 판정. 후퇴 경로 불필요 |
| 6 | **D-11** `tokenId` → `jti` | 사용자 지적 | ✅ | 이름과 설명이 어긋나 있던 것을 RFC 7519 용어로 통일. 파생 쿼리 함정도 함께 처리 |
| 7 | **D-12** Lombok `@Getter` | 사용자 | ✅ | 접근자 47개 제거(~190줄). `User.roles` 만 방어적 복사 보존 |
| 8 | **D-13** 수강 도메인 `@ManyToOne` | 사용자 (Check 후) | ✅ | **FK 미생성(G-1)을 해소.** ERD §3.1.1 전제를 뒤집은 결정 |
| 9 | `@Check` 를 1차부터 도입 | 사용자 | ✅ | 오버부킹을 DB 가 실제로 거부함을 테스트로 확인 |

---

## 2. Related Documents

| 문서 | 버전 | 경로 |
|------|:----:|------|
| Plan | v0.3 | `docs/01-plan/features/project-setup.plan.md` |
| Design | v0.5 | `docs/02-design/features/project-setup.design.md` |
| Analysis | v1.2 | `docs/03-analysis/project-setup.analysis.md` |
| ERD 정본 | v1.10 | `docs/02-design/features/class-enrollment-erd.design.md` |
| 인증 원본 | — | `Chals85/sample-jwt-authentication` |

---

## 3. Completed Items

### 3.1 Functional Requirements — 15/15

| FR | 내용 | 검증 |
|----|------|------|
| FR-01 | 빌드 통과 | `./gradlew build` BUILD SUCCESSFUL |
| FR-02 | QueryDSL Q클래스 생성 | module-1 스파이크 + 컴파일 |
| FR-03 | 7테이블 생성 (+FK 6종) | `EnrollmentSchemaTest` 테이블 7 · FK 검증 2건 |
| FR-04 | CHECK 제약 | 제약 10종 존재 + 오버부킹 거부 |
| FR-05 | 활성 중복 차단 | 생성 컬럼 UNIQUE, 취소 후 재신청 허용 확인 |
| FR-06 | 로그인 | 200 / 401 `INVALID_CREDENTIALS` |
| FR-07 | Refresh 회전 | 200, 이전 토큰 폐기 |
| FR-08 | 재사용 감지 | `REFRESH_TOKEN_REUSED` + 전체 무효화 |
| FR-09 | 로그아웃 블랙리스트 | 로그아웃 후 같은 access 401 |
| FR-10 | 인증 가드 | 토큰 없음 401 / 유효 200 / 위조 `TOKEN_INVALID` |
| FR-11 | 블랙리스트 정리 | `RevokedAccessTokenCleaner` + `@EnableScheduling` |
| FR-12 | Redoc | `/docs/api-guide.html` 200, `<redoc` 포함 확인 |
| FR-13 | Swagger UI | `/docs/api-test.html` 200, `swagger-ui` 포함 확인 |
| FR-14 | 멱등 시딩 | `chals`[USER] + `creator`[USER, CREATOR] |
| FR-15 | 인덱스 | 조회 인덱스 6 + UNIQUE 3 |

### 3.2 Non-Functional Requirements — 5/5

| 항목 | 결과 |
|------|------|
| 재현성 | 외부 인프라·환경변수 없이 `bootRun` 만으로 기동 |
| 테스트 결정성 | 무인자 `now()` 호출 0건 (`ClockConfig` 외) |
| 문서 정합성 | `bootJar` 가 `generatedDocument` 에 의존 — 문서 없이는 실행 불가 |
| 보안 | BCrypt·SHA-256 해시 저장, 계정 열거 방지, CSRF 비활성화 |
| 이식 정확성 | 원본 패키지 잔존 0건 |

### 3.3 Deliverables

- **빌드 환경**: Gradle 9.7.1 · Java 25 · Spring Boot 4.1.1 · QueryDSL 5.1.0(jakarta)
- **엔티티 6개 / 테이블 7개**, ENUM 6종
- **인증 도메인 전체**: 로그인·로그아웃·재발급·회전·재사용 감지·블랙리스트·정리 배치
- **문서 파이프라인**: RestDocs → `openapi3.json` → Redoc + Swagger UI
- **테스트 16개 파일 / 101건**

---

## 4. Incomplete Items

### 4.1 다음 사이클로 이월

| 항목 | 사유 |
|------|------|
| 수강신청 비즈니스 로직 (동시성 규약 7종) | 계획된 분리. 비관적 락·카운터 갱신·대기열 승격·PENDING 만료 배치 |
| 수강 도메인 UseCase / Service / Controller | 위와 동일 |
| `app.enrollment.*` 프로퍼티 4종 | 소비처가 전부 2차 |
| **G-2** `DefaultUserInitializer` 포트 우회 | `UserCommandPort` 신설 필요. 기능 영향 없음 |

### 4.2 보류

| 항목 | 상태 |
|------|------|
| **G-3** Lombok `sun.misc.Unsafe` 경고 | **조치 불가 확인.** 최신 버전이고 `--add-opens` 로도 해소 안 됨. Lombok 대응 대기 |
| ERD 미결 ⑦ `cancel_reason` | 현 요건에 해당 기능 없음 |
| ERD 미결 ⑧ `refresh_token` 정리 주기 | **2차에서 반드시 확정** — 회전마다 행이 쌓인다 |
| 실 DB 전환 | H2 → MySQL/PostgreSQL. `STORED` 되붙이기·예약어 재확인 필요 |

---

## 5. Quality Metrics

### 5.1 최종 분석 결과

| 축 | 점수 |
|----|:----:|
| 구조 일치 | 100% |
| 기능 완성도 | 100% |
| 계약 일치 | 100% |
| 런타임 | 100% |
| **Match Rate** | **100%** |
| 아키텍처 | 95% (G-2 잔여) |
| 컨벤션 | 100% |

### 5.2 해결된 이슈

| ID | 내용 | 해결 |
|----|------|------|
| **G-1 (Critical)** | 수강 도메인 FK 5개 미생성 | `@ManyToOne(LAZY)` 전환(D-13). FK 6종 생성 확인 + **검증 테스트 2건 신설** |
| G-3 (Minor) | Lombok 경고 | 조치 불가 확인 후 Plan §4.2 에 알려진 예외로 명시 |
| R-1 (Plan 리스크) | QueryDSL × Boot 4 미검증 | module-1 스파이크에서 판정 — Lombok 공존 정상 |
| R-2 / R-3 | H2 부분 유니크·CHECK | 스파이크로 조기 판정. `STORED` 제거 필요를 발견(D-9) |

---

## 6. Lessons Learned

### 6.1 잘된 것 (Keep)

**① 스파이크를 첫 모듈에 배치한 것.** module-1 에서 3종을 판정했고, 그중 H2 의 `STORED` 미지원이
실제로 걸렸다. 엔티티를 다 쓴 뒤 알았다면 module-4 를 다시 써야 했다.

**② 설계서가 "컴파일러가 잡지 못하는 지점"을 미리 목록화한 것.** D-1(boolean 이름 변경) 적용 시
`RefreshTokenJpaRepository` 의 JPQL 문자열이 정확히 그 목록에 있었다. 놓쳤다면 컴파일은 통과하고
**Hibernate 부트스트랩에서 기동 실패**했을 것이다. 같은 패턴이 D-11(`jti`)의 Spring Data 파생 쿼리
(`existsByTokenId`)에서 반복됐고, 이미 습관이 되어 있어 사전에 잡았다.

**③ divergence 를 13건까지 전부 추적한 것.** 원본과 달라진 지점이 모두 근거와 함께 남아 있어,
Check 단계에서 "이건 갭인가 의도된 차이인가"를 판정하는 데 시간이 들지 않았다.

**④ 사용자 지적이 실제 결함을 잡아낸 것.** `tokenId` → `jti`(D-11)는 이름과 설명이 어긋나 있던
문제였고, `@ManyToOne` 질문은 **Critical 갭의 근본 원인**으로 이어졌다.

### 6.2 개선이 필요한 것 (Problem)

**① 검증 테스트의 범위가 좁아 Critical 이 늦게 발견됐다.** `EnrollmentSchemaTest` 가 테이블·CHECK·
인덱스·UNIQUE 는 `information_schema` 로 확인하면서 **`referential_constraints` 는 보지 않았다.**
그래서 FK 5개가 없는 채로 빌드가 통과했다. *"제약을 선언했다"와 "제약이 생성됐다"는 다르다* 는
교훈을 CHECK 에는 적용했으면서 FK 에는 적용하지 못했다.

**② 선행 문서의 결정을 확인 없이 승계했다.** `@ManyToOne` 미사용은 ERD §3.1.1 과 인증 원본이
확정한 것이었으나, 그 결정이 **1차 범위에 미치는 영향**(FK 를 별도 수단으로 만들어야 함)을 짚지
않고 넘어갔다. boolean 명명·Lombok 은 확인받았으면서 이것만 빠졌다.

**③ 설계 초안에 존재하지 않는 API 를 적었다.** `@Table(check = ...)` 는 JPA 에 없는 속성인데
설계서 v0.1 에 그대로 들어갔다. design-validator 가 잡아냈으나, 검증을 돌리지 않았다면 구현
단계에서야 발견됐을 것이다.

**④ 제 테스트에 위양성이 있었다.** "문서 페이지가 HTML 200 으로 서빙된다"를 로그인 페이지도
통과시켰다(TestRestTemplate 이 리다이렉트를 따라감). 상태코드만 보고 **내용을 보지 않은** 검사였다.

### 6.3 다음에 시도할 것 (Try)

**① 스키마 검증 체크리스트를 고정한다.** 테이블 / 컬럼 / **FK** / CHECK / UNIQUE / 인덱스 6종을
항상 `information_schema` 로 확인한다. 이번처럼 한 종류만 빠지는 사고를 막는다.

**② 선행 문서의 결정도 "이번 범위에 미치는 영향"을 한 번 짚는다.** 확정된 문서라도, 그 결정이
현재 작업에서 추가 수단을 요구하는지 확인한다.

**③ 런타임 검증에 "내용 확인"을 기본으로 넣는다.** 상태코드·Content-Type 만으로는 위양성이 난다.
응답 본문에 기대하는 마커가 있는지까지 본다.

**④ 2차 시작 시 fetch join 정책을 먼저 정한다.** D-13 으로 `@ManyToOne(LAZY)` 가 되었으므로,
목록 조회에서 N+1 이 날 자리가 생겼다. 강의 목록·수강생 목록이 첫 후보다.

---

## 7. Process Improvement

### 7.1 PDCA 프로세스

- **design-validator 를 Design 직후에 돌린 것이 효과적이었다.** Critical 3건(JPQL 누락, 존재하지
  않는 API, 응답 계약 모순)을 구현 전에 잡았다. 특히 JPQL 건은 발견이 늦었으면 기동 실패로 이어졌다.
- **Check 단계의 런타임 검증을 프로젝트 성격에 맞게 재정의할 필요가 있다.** 스킬의 기본 L1~L3 은
  curl/Playwright 전제인데, 이 프로젝트는 Gradle 빌드 + `@SpringBootTest` 가 그 역할을 한다.

### 7.2 도구 / 환경

- **Java 25 + Boot 4 조합의 함정 3종을 확인했다**: 스타터명 변경(`starter-web` → `starter-webmvc`),
  테스트 모듈 분리(`resttestclient` 등), `sun.misc.Unsafe` deprecation.
- **`@ConfigurationPropertiesScan` 누락**으로 통합 테스트 16개가 한 번에 무너졌다.
  `@SpringBootApplication` 은 이 스캔을 포함하지 않는다 — 주석으로 이유를 남겼다.

---

## 8. Next Steps

### 8.1 즉시

1. [ ] `/pdca archive project-setup`
2. [ ] 커밋 (아직 하지 않음 — 저장소에 커밋 이력 없음)

### 8.2 다음 PDCA 사이클 (2차)

1. [ ] **수강신청 도메인** — ERD §4 동시성 규약 7종
   - 비관적 락(`SELECT ... FOR UPDATE`) → `enrollment_count` 증감
   - 대기열 승격 체인, PENDING 만료 배치
2. [ ] `app.enrollment.*` 프로퍼티 4종 (값은 ERD §2 ⑥ 에 확정돼 있음)
3. [ ] **ERD 미결 ⑧ 확정** — `refresh_token` 정리 주기
4. [ ] **fetch join 정책** — D-13 의 N+1 대비
5. [ ] G-2 — `UserCommandPort` 신설

---

## 9. Changelog

### v1.0.0 (2026-09-01)

**Added**
- 빌드 환경: Gradle 9.7.1 · Java 25 · Spring Boot 4.1.1 · QueryDSL 5.1.0
- 엔티티 6개 / 테이블 7개, ENUM 6종, CHECK 10종, FK 6종, 인덱스 6종
- 인증 도메인 전체 (로그인·로그아웃·재발급·회전·재사용 감지·블랙리스트)
- 문서 파이프라인 (RestDocs → OpenAPI3 → Redoc + Swagger UI)
- 테스트 101건

**Changed (원본 대비 divergence 13건)**
- D-1 boolean `is` 전 계층 통일 · D-2 패키지 · D-3 QueryDSL · D-4 수강 도메인
- D-5 식별자 문자열 · D-6 시딩 리스트 구조 · D-7 `SecurityUserDetails` 오버라이드 제거
- D-8 인덱스명 · D-9 `STORED` 제거 · D-10 Boot 4 테스트 패키지
- D-11 `tokenId` → `jti` · D-12 Lombok `@Getter` · D-13 수강 도메인 `@ManyToOne`

**Known Issues**
- G-2 `DefaultUserInitializer` 포트 우회 (기능 영향 없음)
- G-3 Lombok `sun.misc.Unsafe` 경고 (조치 불가, Lombok 대응 대기)

---

## Version History

| 버전 | 날짜 | 변경 내용 | 작성자 |
|------|------|-----------|--------|
| 1.0 | 2026-09-01 | 최초 작성. Match Rate 100%, Success Criteria 10/10, FR 15/15 | developer2@lulumedic.com |
