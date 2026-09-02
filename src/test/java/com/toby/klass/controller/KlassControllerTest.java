package com.toby.klass.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.request.RequestDocumentation.parameterWithName;
import static org.springframework.restdocs.request.RequestDocumentation.pathParameters;
import static org.springframework.restdocs.request.RequestDocumentation.queryParameters;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.patch;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.post;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.epages.restdocs.apispec.ResourceSnippetParameters;
import com.toby.klass.infrastructure.security.config.SecurityConfig;
import com.toby.klass.infrastructure.security.filter.JwtAuthenticationFilter;
import com.toby.klass.klass.adapter.in.web.controller.KlassController;
import com.toby.klass.common.application.dto.CursorPageResult;
import com.toby.klass.klass.application.dto.KlassCreatorResult;
import com.toby.klass.klass.application.dto.KlassQuery;
import com.toby.klass.klass.application.dto.KlassResult;
import com.toby.klass.klass.application.dto.KlassSummaryResult;
import com.toby.klass.klass.application.port.in.ChangeKlassStatusUseCase;
import com.toby.klass.klass.application.port.in.FindKlassUseCase;
import com.toby.klass.klass.application.port.in.ListKlassUseCase;
import com.toby.klass.klass.application.port.in.RegisterKlassUseCase;
import com.toby.klass.klass.application.port.in.UpdateKlassUseCase;
import com.toby.klass.klass.domain.KlassStatus;
import com.toby.klass.klass.domain.error.KlassError;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restdocs.test.autoconfigure.AutoConfigureRestDocs;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.restdocs.payload.JsonFieldType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 강의 관리 API 문서화 테스트 (L3).
 *
 * <h2>이 레벨에서 검증할 수 없는 것 — 권한과 인증</h2>
 * 하위 슬라이스는 {@code SecurityConfig} 를 컨텍스트에서 <b>배제</b>하고
 * {@code addFilters = false} 로 필터를 <b>끈다</b>. 따라서 {@code hasRole("CREATOR")} 규칙도
 * EntryPoint 도 동작하지 않는다 — {@code ROLE_USER} 로 강의를 등록해도 <b>201 이 나온다.</b>
 * 403·401 케이스를 여기 쓰면 검증하는 척만 하는 테스트가 된다.
 *
 * <p>그 공백은 {@code KlassFlowIntegrationTest}(L4)가 실제 필터 체인으로 메운다.
 * 소유권 검사도 마찬가지다 — 유즈케이스가 {@code @MockitoBean} 이라 여기서 403 을 단언하면
 * <b>내가 스텁한 예외를 내가 확인하는 동어반복</b>이 된다. 실제 검사는
 * {@code KlassServiceTest}(L2)가 본다.
 *
 * <h2>경로 변수를 쓰는 첫 테스트다</h2>
 * {@code RestDocumentationRequestBuilders} 를 써야 한다. {@code MockMvcRequestBuilders} 로
 * 요청하면 OpenAPI 의 path 키가 {@code /v1/klasses/1} 처럼 <b>실제 값으로 굳어</b>
 * 요청마다 path 가 늘고 문서가 오염된다. {@code pathParameters(...)} 도 빠지면 스니펫
 * 생성이 실패한다.
 *
 * <p>Design Ref: §4.1 엔드포인트, §8.5 L3
 */
@WebMvcTest(controllers = KlassController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {SecurityConfig.class, JwtAuthenticationFilter.class}))
@AutoConfigureRestDocs
@AutoConfigureMockMvc(addFilters = false)
class KlassControllerTest extends BaseControllerTest {

    private static final String ACCESS_TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.signature";
    private static final LocalDateTime CREATED_AT = LocalDateTime.parse("2026-09-01T10:00:00");
    private static final LocalDateTime UPDATED_AT = LocalDateTime.parse("2026-09-02T15:30:00");
    private static final LocalDate STARTS_ON = LocalDate.parse("2026-10-01");
    private static final LocalDate ENDS_ON = LocalDate.parse("2026-12-31");

    private static final Long CREATOR_ID = 2L;
    private static final Long KLASS_ID = 1L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RegisterKlassUseCase registerKlassUseCase;

    @MockitoBean
    private UpdateKlassUseCase updateKlassUseCase;

    @MockitoBean
    private ChangeKlassStatusUseCase changeKlassStatusUseCase;

    @MockitoBean
    private FindKlassUseCase findKlassUseCase;

    @MockitoBean
    private ListKlassUseCase listKlassUseCase;

    /** 크리에이터로 인증한다. 권한이 고정된 {@code authenticateAs(id, name)} 로는 그릴 수 없다. */
    private void authenticateAsCreator() {
        authenticateAs(CREATOR_ID, "creator", List.of("ROLE_USER", "ROLE_CREATOR"));
    }

    private static KlassResult klassResult(KlassStatus status) {
        return new KlassResult(KLASS_ID, "스프링 부트 입문", "처음 시작하는 스프링 부트",
                new BigDecimal("50000"), 30, 0, status, STARTS_ON, ENDS_ON, 7,
                new KlassCreatorResult(CREATOR_ID, "creator"), CREATED_AT, UPDATED_AT);
    }

    private static KlassSummaryResult summary(Long id, String title, KlassStatus status) {
        return new KlassSummaryResult(id, title, new BigDecimal("50000"), 30, 12, status,
                STARTS_ON, ENDS_ON, new KlassCreatorResult(CREATOR_ID, "creator"));
    }

    /** 상세 응답 필드. 등록·수정·상태변경·상세가 모두 같은 형태라 한 곳에 모은다. */
    private static org.springframework.restdocs.payload.FieldDescriptor[] klassFields() {
        return new org.springframework.restdocs.payload.FieldDescriptor[] {
            fieldWithPath("success").type(JsonFieldType.BOOLEAN).description("성공 여부"),
            fieldWithPath("error").type(JsonFieldType.NULL).description("성공 시 null"),
            fieldWithPath("data.id").description("강의 PK"),
            fieldWithPath("data.title").description("강의 제목"),
            fieldWithPath("data.description").description("강의 내용. **필수값**이다"),
            fieldWithPath("data.price").description("수강료"),
            fieldWithPath("data.capacity").description("최대 정원"),
            fieldWithPath("data.enrollmentCount").description("좌석 점유 인원 (PENDING + CONFIRMED)"),
            fieldWithPath("data.status").description("강의 상태. `DRAFT` / `OPEN` / `CLOSED`"),
            fieldWithPath("data.startsOn").description("수강 시작일 (ISO-8601 날짜)"),
            fieldWithPath("data.endsOn").description("수강 종료일"),
            fieldWithPath("data.cancellationPeriodDays").description("취소 가능 기간(일). null 이면 전역 기본값"),
            fieldWithPath("data.creator.id").description("개설자 PK"),
            fieldWithPath("data.creator.username").description("개설자 로그인 아이디"),
            fieldWithPath("data.createdAt").description("등록 시각"),
            fieldWithPath("data.updatedAt").description("최종 수정 시각. 수정된 적 없으면 `createdAt` 과 같다"),
        };
    }

    /** 목록 응답 필드. 공개 목록과 내 강의 목록이 같은 형태다. */
    private static org.springframework.restdocs.payload.FieldDescriptor[] pageFields() {
        return new org.springframework.restdocs.payload.FieldDescriptor[] {
            fieldWithPath("success").type(JsonFieldType.BOOLEAN).description("성공 여부"),
            fieldWithPath("error").type(JsonFieldType.NULL).description("성공 시 null"),
            fieldWithPath("data.items[].id").description("강의 PK"),
            fieldWithPath("data.items[].title").description("강의 제목"),
            fieldWithPath("data.items[].price").description("수강료"),
            fieldWithPath("data.items[].capacity").description("최대 정원"),
            fieldWithPath("data.items[].enrollmentCount").description("좌석 점유 인원"),
            fieldWithPath("data.items[].status").description("강의 상태"),
            fieldWithPath("data.items[].startsOn").description("수강 시작일"),
            fieldWithPath("data.items[].endsOn").description("수강 종료일"),
            fieldWithPath("data.items[].creator.id").description("개설자 PK"),
            fieldWithPath("data.items[].creator.username").description("개설자 로그인 아이디"),
            fieldWithPath("data.hasNext").type(JsonFieldType.BOOLEAN).description("다음 페이지 존재 여부"),
            fieldWithPath("data.nextCursor").description("다음 요청에 넣을 `cursor`. `hasNext` 가 false 면 null"),
        };
    }

    @Nested
    @DisplayName("명령")
    class Commands {

        @Test
        @DisplayName("강의 등록")
        void register() throws Exception {
            authenticateAsCreator();
            given(registerKlassUseCase.register(any()))
                    .willReturn(klassResult(KlassStatus.DRAFT));

            mockMvc.perform(post("/v1/klasses")
                            .header("Authorization", "Bearer " + ACCESS_TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "title": "스프링 부트 입문",
                                      "description": "처음 시작하는 스프링 부트",
                                      "price": 50000,
                                      "capacity": 30,
                                      "startsOn": "2026-10-01",
                                      "endsOn": "2026-12-31",
                                      "cancellationPeriodDays": 7
                                    }"""))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.status").value("DRAFT"))
                    .andDo(document("강의-등록", ResourceSnippetParameters.builder()
                            .tag("Klass")
                            .summary("강의 등록")
                            .description("""
                                    새 강의를 개설한다. `ROLE_CREATOR` 권한이 필요하다.

                                    상태는 항상 `DRAFT` 로 시작한다 — 요청으로 지정할 수 없다.
                                    공개하려면 상태 변경 API 로 `OPEN` 으로 전이한다.

                                    `endsOn >= startsOn` 은 도메인이 검사한다. 위반 시 400 `INVALID_KLASS_PERIOD`.""")
                            .requestHeaders(authorizationHeader())
                            .requestFields(
                                    fieldWithPath("title").description("강의 제목. 최대 200자"),
                                    fieldWithPath("description").description("강의 내용. **필수값**"),
                                    fieldWithPath("price").description("수강료. 0 이상"),
                                    fieldWithPath("capacity").description("최대 정원. 1 이상"),
                                    fieldWithPath("startsOn").description("수강 시작일"),
                                    fieldWithPath("endsOn").description("수강 종료일. 시작일 이후"),
                                    fieldWithPath("cancellationPeriodDays").optional()
                                            .description("취소 가능 기간(일). 생략하면 전역 기본값"))
                            .responseFields(klassFields())
                            .requestSchema(schema("RegisterKlassRequest"))
                            .responseSchema(schema("KlassResponse"))
                            .build()));
        }

        @Test
        @DisplayName("강의 수정")
        void update() throws Exception {
            authenticateAsCreator();
            given(updateKlassUseCase.update(any())).willReturn(klassResult(KlassStatus.DRAFT));

            mockMvc.perform(put("/v1/klasses/{id}", KLASS_ID)
                            .header("Authorization", "Bearer " + ACCESS_TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "title": "스프링 부트 입문",
                                      "description": "처음 시작하는 스프링 부트",
                                      "price": 50000,
                                      "capacity": 30,
                                      "startsOn": "2026-10-01",
                                      "endsOn": "2026-12-31",
                                      "cancellationPeriodDays": 7
                                    }"""))
                    .andExpect(status().isOk())
                    .andDo(document("강의-수정", ResourceSnippetParameters.builder()
                            .tag("Klass")
                            .summary("강의 수정")
                            .description("""
                                    강의를 **전체 교체**한다. `ROLE_CREATOR` 권한과 **본인 소유**여야 한다.

                                    메서드는 `PATCH` 지만 **부분 수정이 아니다.** 수정 화면은 상세 조회로
                                    강의의 전체 값을 이미 들고 있으므로, 변경하지 않은 필드도 **현재 값을
                                    그대로 실어 보낸다.**

                                    따라서 필드 누락·`null`·공백은 "안 바꿈"이 아니라 **입력 오류**이며
                                    400 `VALIDATION_ERROR` 다. 검증 기준은 등록 API 와 같다.

                                    값이 실제로 바뀌지 않았어도 매 요청이 수정이므로 `updatedAt` 이 갱신된다.
                                    `cancellationPeriodDays` 만 선택이며, 생략하거나 `null` 로 보내면
                                    **전역 기본값으로 되돌아간다.**

                                    `cancellationPeriodDays` 는 **`DRAFT` 상태에서만 변경할 수 있다.**
                                    취소 가능 기간은 수강생과의 약속이라, 신청을 받기 시작한 뒤에 바꾸면
                                    이미 신청한 사람의 취소 조건이 사후에 불리하게 바뀔 수 있다. 다른
                                    상태에서 이 값을 바꾸려 하면 409 `CANCELLATION_PERIOD_NOT_EDITABLE` 다.
                                    **현재 값을 그대로 재전송하는 것은 상태와 무관하게 허용된다** — 전체
                                    교체이므로 바꾸지 않은 필드에도 현재 값을 실어 보내는 것이 정상이고,
                                    그것까지 막으면 `OPEN` 강의의 다른 필드를 고칠 수 없게 된다.

                                    `endsOn >= startsOn` 은 도메인이 검사한다 (400 `INVALID_KLASS_PERIOD`).
                                    정원은 이미 신청한 인원보다 적게 줄일 수 없다 (409 `CAPACITY_BELOW_ENROLLMENT`).

                                    남의 강의를 수정하면 403 `NOT_KLASS_OWNER`, 남의 초안이면 404 다 —
                                    초안은 존재 자체를 드러내지 않는다.""")
                            .requestHeaders(authorizationHeader())
                            .pathParameters(parameterWithName("id").description("강의 PK"))
                            // 전 필드를 문서화한다. RestDocs 는 요청 본문에 실제로 담긴 것만
                            // 문서화하므로, 본문에서 필드를 빼면 스펙에서도 조용히 사라진다 —
                            // L5 는 오퍼레이션 수만 세므로 어떤 테스트도 그것을 잡지 못한다.
                            //
                            // .optional() 은 cancellationPeriodDays 에만 남는다. 나머지 6종은
                            // 필수이므로 optional 을 붙이면 생성된 스펙의 required 배열에서
                            // 빠져 문서가 "생략해도 된다"고 거짓말을 한다 (D-25)
                            .requestFields(
                                    fieldWithPath("title").description("강의 제목. 최대 200자"),
                                    fieldWithPath("description").description("강의 내용. **필수값**"),
                                    fieldWithPath("price").description("수강료. 0 이상"),
                                    fieldWithPath("capacity").description(
                                            "최대 정원. 1 이상. 이미 신청한 인원보다 적게 줄일 수 없다"),
                                    fieldWithPath("startsOn").description("수강 시작일"),
                                    fieldWithPath("endsOn").description("수강 종료일. 시작일 이후"),
                                    fieldWithPath("cancellationPeriodDays").optional()
                                            .type(JsonFieldType.NUMBER)
                                            .description("취소 가능 기간(일). 생략하거나 `null` 이면 전역 기본값. "
                                                    + "**`DRAFT` 에서만 변경 가능** — 다른 상태에서 값을 바꾸면 "
                                                    + "409 `CANCELLATION_PERIOD_NOT_EDITABLE`. 같은 값 재전송은 허용"))
                            .responseFields(klassFields())
                            .requestSchema(schema("UpdateKlassRequest"))
                            .responseSchema(schema("KlassResponse"))
                            .build()));
        }

        @Test
        @DisplayName("강의 상태 변경")
        void changeStatus() throws Exception {
            authenticateAsCreator();
            given(changeKlassStatusUseCase.changeStatus(any()))
                    .willReturn(klassResult(KlassStatus.OPEN));

            mockMvc.perform(patch("/v1/klasses/{id}/status", KLASS_ID)
                            .header("Authorization", "Bearer " + ACCESS_TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"status": "OPEN"}"""))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("OPEN"))
                    .andDo(document("강의-상태변경", ResourceSnippetParameters.builder()
                            .tag("Klass")
                            .summary("강의 상태 변경")
                            .description("""
                                    강의 상태를 바꾼다. `ROLE_CREATOR` 권한과 **본인 소유**여야 한다.

                                    **허용되는 전이는 셋뿐이다.**

                                    | 전이 | 뜻 |
                                    |------|-----|
                                    | `DRAFT` → `OPEN` | 모집 시작 |
                                    | `DRAFT` → `CLOSED` | 개설 철회 |
                                    | `OPEN` → `CLOSED` | 모집 마감 |

                                    역전이(`OPEN` → `DRAFT`, `CLOSED` → `OPEN`)는 **차단된다**.
                                    이미 신청한 사용자의 신청이 무효화되거나, 대기자가 있는 강의에서
                                    신규 신청자가 대기자를 앞지르는 구멍이 생기기 때문이다.

                                    허용되지 않는 전이는 409 `INVALID_KLASS_STATUS_TRANSITION` 이다 —
                                    요청 형식은 옳고 현재 상태와 충돌하는 것이라 400 이 아니다.""")
                            .requestHeaders(authorizationHeader())
                            .pathParameters(parameterWithName("id").description("강의 PK"))
                            .requestFields(fieldWithPath("status")
                                    .description("목표 상태. `OPEN` 또는 `CLOSED`"))
                            .responseFields(klassFields())
                            .requestSchema(schema("ChangeKlassStatusRequest"))
                            .responseSchema(schema("KlassResponse"))
                            .build()));
        }
    }

    @Nested
    @DisplayName("조회")
    class Queries {

        @Test
        @DisplayName("강의 상세 조회 — 인증 없이")
        void detail() throws Exception {
            // authenticateAs 를 호출하지 않는다. 선택적 인증이라 비로그인도 조회할 수 있다
            given(findKlassUseCase.findById(eq(KLASS_ID), eq(null)))
                    .willReturn(klassResult(KlassStatus.OPEN));

            mockMvc.perform(get("/v1/klasses/{id}", KLASS_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.title").value("스프링 부트 입문"))
                    .andDo(document("강의-상세조회", ResourceSnippetParameters.builder()
                            .tag("Klass")
                            .summary("강의 상세 조회")
                            .description("""
                                    강의 하나를 조회한다. **인증이 선택적이다** — 토큰이 없어도 열린다.

                                    토큰을 보내면 보이는 범위가 넓어진다. 개설자는 자기 `DRAFT` 도 볼 수 있고,
                                    그 외에는 `OPEN` · `CLOSED` 만 보인다.

                                    보이지 않는 강의는 403 이 아니라 **404** 다. 403 은 "그 강의는 존재한다"를
                                    알려주는데, 초안은 존재 자체가 비밀이기 때문이다.""")
                            .pathParameters(parameterWithName("id").description("강의 PK"))
                            .responseFields(klassFields())
                            .responseSchema(schema("KlassResponse"))
                            .build()));
        }

        @Test
        @DisplayName("공개 강의 목록 — 인증 없이")
        void list() throws Exception {
            given(listKlassUseCase.listPublic(any(KlassQuery.class)))
                    .willReturn(new CursorPageResult<>(
                            List.of(summary(42L, "스프링 부트 입문", KlassStatus.OPEN),
                                    summary(41L, "JPA 심화", KlassStatus.CLOSED)),
                            true, 41L));

            mockMvc.perform(get("/v1/klasses")
                            .param("cursor", "43")
                            .param("size", "2")
                            .param("status", "OPEN"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items.length()").value(2))
                    .andExpect(jsonPath("$.data.hasNext").value(true))
                    .andDo(document("강의-목록조회", ResourceSnippetParameters.builder()
                            .tag("Klass")
                            .summary("공개 강의 목록")
                            .description("""
                                    공개된 강의를 커서 방식으로 조회한다. **인증이 선택적이다.**

                                    `DRAFT` 는 **누구에게도** 보이지 않는다 — 개설자 본인에게도.
                                    그래야 크리에이터가 자기 강의가 남에게 어떻게 보이는지 이 화면에서
                                    확인할 수 있다. 초안은 `GET /v1/klasses/me` 로 본다.

                                    **커서 사용법**: 첫 요청은 `cursor` 없이 보내고, 응답의 `nextCursor` 를
                                    다음 요청의 `cursor` 로 넘긴다. `hasNext` 가 false 면 마지막 페이지다.
                                    정렬은 `id` 내림차순(최신순) 고정이며, **총 개수는 제공하지 않는다** —
                                    그것을 세려면 전체를 훑어야 해서 커서 방식의 이점이 사라진다.""")
                            .queryParameters(
                                    parameterWithName("cursor").optional()
                                            .description("직전 페이지 마지막 항목의 `id`. 첫 페이지는 생략"),
                                    parameterWithName("size").optional()
                                            .description("가져올 개수. 1~100, 기본 20"),
                                    parameterWithName("status").optional()
                                            .description("상태 필터. `OPEN` 또는 `CLOSED`. 생략하면 둘 다. "
                                                    + "`DRAFT` 를 지정하면 빈 목록이 나온다 — 초안은 공개 목록에 없다"))
                            .responseFields(pageFields())
                            .responseSchema(schema("KlassPageResponse"))
                            .build()));
        }

        @Test
        @DisplayName("내 강의 목록")
        void listMine() throws Exception {
            authenticateAsCreator();
            given(listKlassUseCase.listByCreator(eq(CREATOR_ID), any(KlassQuery.class)))
                    .willReturn(new CursorPageResult<>(
                            List.of(summary(42L, "스프링 부트 입문", KlassStatus.OPEN),
                                    summary(40L, "작성 중인 강의", KlassStatus.DRAFT)),
                            false, null));

            mockMvc.perform(get("/v1/klasses/me")
                            .header("Authorization", "Bearer " + ACCESS_TOKEN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.hasNext").value(false))
                    .andExpect(jsonPath("$.data.nextCursor").doesNotExist())
                    .andDo(document("강의-내목록조회", ResourceSnippetParameters.builder()
                            .tag("Klass")
                            .summary("내 강의 목록")
                            .description("""
                                    내가 개설한 강의를 커서 방식으로 조회한다. `ROLE_CREATOR` 권한이 필요하다.

                                    공개 목록과 달리 **`DRAFT` 를 포함한 전부**를 돌려준다.
                                    `status=DRAFT` 로 작성 중인 강의만 골라 볼 수도 있다.

                                    커서 사용법은 공개 목록과 같다.""")
                            .requestHeaders(authorizationHeader())
                            .queryParameters(
                                    parameterWithName("cursor").optional()
                                            .description("직전 페이지 마지막 항목의 `id`. 첫 페이지는 생략"),
                                    parameterWithName("size").optional()
                                            .description("가져올 개수. 1~100, 기본 20"),
                                    parameterWithName("status").optional()
                                            .description("상태 필터. `DRAFT` 도 지정할 수 있다"))
                            .responseFields(pageFields())
                            .responseSchema(schema("KlassPageResponse"))
                            .build()));
        }
    }

    /**
     * 실패 경로.
     *
     * <p>권한(403)·인증(401)은 여기 없다 — 이 슬라이스에서는 검증되지 않기 때문이다
     * (클래스 주석 참조). 여기서 볼 수 있는 것은 <b>컨트롤러 계층이 만드는 실패</b>와
     * <b>유즈케이스가 던진 예외가 올바른 상태코드로 번역되는지</b>다.
     */
    @Nested
    @DisplayName("실패 경로")
    class Failures {

        @Test
        @DisplayName("제목 없이 등록하면 400 이고 details 에 필드명이 담긴다")
        void rejectsMissingTitle() throws Exception {
            authenticateAsCreator();

            mockMvc.perform(post("/v1/klasses")
                            .header("Authorization", "Bearer " + ACCESS_TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "description": "내용", "price": 1000, "capacity": 10,
                                      "startsOn": "2026-10-01", "endsOn": "2026-12-31"
                                    }"""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.error.details.title").exists());
        }

        @Test
        @DisplayName("내용 없이 등록하면 400 이다 — description 은 필수값이다")
        void rejectsMissingDescription() throws Exception {
            authenticateAsCreator();

            mockMvc.perform(post("/v1/klasses")
                            .header("Authorization", "Bearer " + ACCESS_TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "title": "제목", "price": 1000, "capacity": 10,
                                      "startsOn": "2026-10-01", "endsOn": "2026-12-31"
                                    }"""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.error.details.description").exists());
        }

        @Test
        @DisplayName("size 가 범위를 벗어나면 400 이다 — Advice 핸들러가 없으면 500 이 된다")
        void rejectsOutOfRangeSize() throws Exception {
            // @Max 를 내장 메서드 검증이 잡아 HandlerMethodValidationException 을 던진다.
            // KlassQuery 생성자의 INVALID_KLASS_PAGE_SIZE 는 둘째 방어선이라 도달하지 않는다
            mockMvc.perform(get("/v1/klasses").param("size", "101"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        }

        @Test
        @DisplayName("status 에 정의되지 않은 값을 주면 400 이다 — Advice 확장이 없으면 500 이 된다")
        void rejectsUnknownStatus() throws Exception {
            mockMvc.perform(get("/v1/klasses").param("status", "OPENED"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.error.details.status").exists());
        }

        // 숫자가 아닌 경로 변수(/v1/klasses/abc)는 여기서 검증하지 않는다.
        // 이 슬라이스에는 SecurityConfig 가 없어 컨트롤러까지 도달해 400 이 나오지만,
        // 실제 앱에서는 permitAll 매처가 {id:[0-9]+} 라 401 이다 —
        // 여기서 400 을 단언하면 일어나지 않는 응답을 문서화하게 된다.
        // 그 경로의 계약은 KlassFlowIntegrationTest #14 가 단언한다.

        /**
         * <b>수정은 전체 교체이므로 등록과 같은 기준으로 막는다</b> (D-25).
         *
         * <p>부분 수정 시절에는 {@code @NotBlank} 를 쓸 수 없어 {@code @Pattern} 으로
         * "{@code null} 은 통과, 공백은 거부"를 표현했다. 이제 {@code null} 도 거부해야
         * 하므로 {@code @NotBlank} 가 맞다 — 빈 값은 "안 바꿈"이 아니라 입력 오류다.
         */
        @Test
        @DisplayName("수정 요청에 빈 제목을 보내면 400 이다")
        void rejectsBlankTitleOnUpdate() throws Exception {
            authenticateAsCreator();

            mockMvc.perform(put("/v1/klasses/{id}", KLASS_ID)
                            .header("Authorization", "Bearer " + ACCESS_TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "title": "",
                                      "description": "처음 시작하는 스프링 부트",
                                      "price": 50000, "capacity": 30,
                                      "startsOn": "2026-10-01", "endsOn": "2026-12-31"
                                    }"""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.error.details.title").exists());
        }

        @Test
        @DisplayName("수정 요청에 공백뿐인 내용을 보내면 400 이다 — D-18 의 필수값 취지")
        void rejectsBlankDescriptionOnUpdate() throws Exception {
            authenticateAsCreator();

            mockMvc.perform(put("/v1/klasses/{id}", KLASS_ID)
                            .header("Authorization", "Bearer " + ACCESS_TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "title": "스프링 부트 입문",
                                      "description": "   ",
                                      "price": 50000, "capacity": 30,
                                      "startsOn": "2026-10-01", "endsOn": "2026-12-31"
                                    }"""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.error.details.description").exists());
        }

        /**
         * <b>부분 수정에서는 "안 보냄"이었던 것이 이제 입력 오류다</b> (D-25).
         *
         * <p>이 두 케이스가 전환의 핵심 계약이다 — 예전 규격에서는 200 이 나가고 아무 일도
         * 일어나지 않았다. 클라이언트가 필드를 흘렸다는 사실이 조용히 묻히던 자리다.
         */
        @Test
        @DisplayName("수정 요청에 내용이 빠지면 400 이다 — 예전 규격에서는 '안 바꿈'이었다")
        void rejectsMissingDescriptionOnUpdate() throws Exception {
            authenticateAsCreator();

            mockMvc.perform(put("/v1/klasses/{id}", KLASS_ID)
                            .header("Authorization", "Bearer " + ACCESS_TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "title": "스프링 부트 입문",
                                      "price": 50000, "capacity": 30,
                                      "startsOn": "2026-10-01", "endsOn": "2026-12-31"
                                    }"""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.error.details.description").exists());
        }

        @Test
        @DisplayName("수정 요청에 정원이 빠지면 400 이다")
        void rejectsMissingCapacityOnUpdate() throws Exception {
            authenticateAsCreator();

            mockMvc.perform(put("/v1/klasses/{id}", KLASS_ID)
                            .header("Authorization", "Bearer " + ACCESS_TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "title": "스프링 부트 입문",
                                      "description": "처음 시작하는 스프링 부트",
                                      "price": 50000,
                                      "startsOn": "2026-10-01", "endsOn": "2026-12-31"
                                    }"""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.error.details.capacity").exists());
        }

        @Test
        @DisplayName("여러 줄 내용은 통과한다 — description 은 TEXT 라 개행이 정상이다")
        void allowsMultilineDescription() throws Exception {
            authenticateAsCreator();
            given(updateKlassUseCase.update(any())).willReturn(klassResult(KlassStatus.DRAFT));

            mockMvc.perform(put("/v1/klasses/{id}", KLASS_ID)
                            .header("Authorization", "Bearer " + ACCESS_TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            // JSON 이 이스케이프를 받아야 한다. 텍스트 블록에서 \n 은
                            // 실제 개행이 되어 JSON 문법 위반(제어문자)이 된다
                            .content("""
                                    {
                                      "title": "스프링 부트 입문",
                                      "description": "\\n둘째 줄에 내용이 있다",
                                      "price": 50000, "capacity": 30,
                                      "startsOn": "2026-10-01", "endsOn": "2026-12-31"
                                    }"""))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("허용되지 않는 상태 전이는 409 다 — 400 이 아니다")
        void rejectsInvalidTransition() throws Exception {
            authenticateAsCreator();
            willThrow(KlassError.INVALID_KLASS_STATUS_TRANSITION.toException())
                    .given(changeKlassStatusUseCase).changeStatus(any());

            mockMvc.perform(patch("/v1/klasses/{id}/status", KLASS_ID)
                            .header("Authorization", "Bearer " + ACCESS_TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"status": "OPEN"}"""))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("INVALID_KLASS_STATUS_TRANSITION"));
        }

        /**
         * <b>Advice 매핑만 검증한다.</b> 유즈케이스가 목이라 규칙 자체는 실행되지 않는다 —
         * "{@code DRAFT} 에서만 바꿀 수 있다"와 "같은 값 재전송은 통과한다"를 고정하는 것은
         * {@code KlassTest}(L1)와 {@code KlassServiceTest}(L2)다. 여기서 확인하는 것은 새
         * 에러 코드가 <b>400 이나 500 이 아니라 409</b> 로 나가고 {@code error.code} 가
         * 그대로 실린다는 것뿐이다.
         *
         * <p>새 스니펫을 만들지 않는다 — 같은 엔드포인트이므로 오퍼레이션 수가 늘면 안 된다.
         */
        @Test
        @DisplayName("DRAFT 아닌 강의의 취소 기간 변경은 409 다 — 상태 전이 코드와 구분된다")
        void rejectsCancellationPeriodChangeOutsideDraft() throws Exception {
            authenticateAsCreator();
            willThrow(KlassError.CANCELLATION_PERIOD_NOT_EDITABLE.toException())
                    .given(updateKlassUseCase).update(any());

            mockMvc.perform(put("/v1/klasses/{id}", KLASS_ID)
                            .header("Authorization", "Bearer " + ACCESS_TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "title": "스프링 부트 입문",
                                      "description": "처음 시작하는 스프링 부트",
                                      "price": 50000, "capacity": 30,
                                      "startsOn": "2026-10-01", "endsOn": "2026-12-31",
                                      "cancellationPeriodDays": 14
                                    }"""))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("CANCELLATION_PERIOD_NOT_EDITABLE"));
        }

        @Test
        @DisplayName("보이지 않는 강의는 404 다")
        void hidesInvisibleKlass() throws Exception {
            willThrow(KlassError.KLASS_NOT_FOUND.toException())
                    .given(findKlassUseCase).findById(eq(KLASS_ID), eq(null));

            mockMvc.perform(get("/v1/klasses/{id}", KLASS_ID))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("KLASS_NOT_FOUND"));
        }
    }
}
