package com.toby.klass.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.post;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.request.RequestDocumentation.parameterWithName;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.epages.restdocs.apispec.ResourceSnippetParameters;
import com.toby.klass.common.application.dto.CursorPageResult;
import com.toby.klass.enrollment.adapter.in.web.controller.EnrollmentController;
import com.toby.klass.enrollment.application.dto.EnrollmentResult;
import com.toby.klass.enrollment.application.dto.EnrollmentSummaryResult;
import com.toby.klass.enrollment.application.dto.KlassEnrollmentResult;
import com.toby.klass.enrollment.application.port.in.ApplyEnrollmentUseCase;
import com.toby.klass.enrollment.application.port.in.CancelEnrollmentUseCase;
import com.toby.klass.enrollment.application.port.in.ConfirmEnrollmentUseCase;
import com.toby.klass.enrollment.application.port.in.FindEnrollmentUseCase;
import com.toby.klass.enrollment.application.port.in.ListEnrollmentUseCase;
import com.toby.klass.enrollment.domain.CancelReason;
import com.toby.klass.enrollment.domain.EnrollmentSource;
import com.toby.klass.enrollment.domain.EnrollmentStatus;
import com.toby.klass.infrastructure.security.config.SecurityConfig;
import com.toby.klass.infrastructure.security.filter.JwtAuthenticationFilter;
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
import org.springframework.restdocs.payload.FieldDescriptor;
import org.springframework.restdocs.payload.JsonFieldType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 수강 신청 API 계약 + RestDocs 스니펫 (L3).
 *
 * <h2>이 테스트가 문서를 만든다</h2>
 * 여기서 남긴 스니펫이 {@code openapi3.json} 이 되고 Redoc·Swagger UI 가 그것을 렌더링한다.
 * <b>테스트를 빠뜨리면 엔드포인트가 문서에서 조용히 사라지고</b>
 * {@code DocumentationIntegrationTest} 의 오퍼레이션 검증이 깨진다 — 그때 고칠 것은
 * 개수가 아니라 이 테스트다.
 *
 * <p>{@code fieldWithPath("data.isCancellable")} 같은 <b>경로 문자열은 컴파일러가 잡지
 * 못한다.</b> 필드명을 바꾸면 문서 생성 단계에서 깨진다 (CLAUDE.md 지점 4번).
 *
 * <h2>{@code SecurityConfig} 를 제외한다</h2>
 * 권한 게이트는 여기서 검증하지 않는다 — 필터를 끈 슬라이스라 규칙이 적용되지 않아
 * <b>통과해도 아무것도 증명하지 못한다.</b> 그쪽은 L4 통합 테스트의 몫이다.
 *
 * <p>Design Ref: enrollment-management §9.4
 */
@WebMvcTest(controllers = EnrollmentController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {SecurityConfig.class, JwtAuthenticationFilter.class}))
@AutoConfigureRestDocs
@AutoConfigureMockMvc(addFilters = false)
class EnrollmentControllerTest extends BaseControllerTest {

    /**
     * 문서용 가짜 토큰.
     *
     * <p><b>payload 가 유효한 base64 JSON 이어야 한다.</b> {@code restdocs-api-spec} 의
     * {@code JwtSecurityHandler} 가 Bearer 토큰의 가운데 조각을 디코딩해 {@code scope}
     * 클레임을 읽으려 하는데, 파싱에 실패하면 스니펫 생성 자체가 예외로 죽는다.
     * 다른 컨트롤러 테스트가 모두 같은 값을 쓰는 이유다 ({@code eyJzdWIiOiIxIn0} = {"sub":"1"}).
     */
    private static final String ACCESS_TOKEN =
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.signature";
    private static final Long KLASS_ID = 7L;
    private static final Long ENROLLMENT_ID = 42L;
    private static final Long STUDENT_ID = 2L;

    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 9, 3, 10, 0);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ApplyEnrollmentUseCase applyEnrollmentUseCase;

    @MockitoBean
    private ConfirmEnrollmentUseCase confirmEnrollmentUseCase;

    @MockitoBean
    private CancelEnrollmentUseCase cancelEnrollmentUseCase;

    @MockitoBean
    private FindEnrollmentUseCase findEnrollmentUseCase;

    @MockitoBean
    private ListEnrollmentUseCase listEnrollmentUseCase;

    private void authenticateAsStudent() {
        authenticateAs(STUDENT_ID, "student");
    }

    private void authenticateAsCreator() {
        authenticateAs(1L, "creator", List.of("ROLE_USER", "ROLE_CREATOR"));
    }

    // ── 응답 픽스처 ──────────────────────────────────────────────────────────

    private static EnrollmentResult pendingResult() {
        return new EnrollmentResult(ENROLLMENT_ID, KLASS_ID, "스프링 부트 입문",
                EnrollmentStatus.PENDING, EnrollmentSource.DIRECT,
                CREATED_AT, CREATED_AT.plusMinutes(30), null, null, null, true);
    }

    private static EnrollmentResult confirmedResult() {
        return new EnrollmentResult(ENROLLMENT_ID, KLASS_ID, "스프링 부트 입문",
                EnrollmentStatus.CONFIRMED, EnrollmentSource.DIRECT,
                CREATED_AT, null, CREATED_AT.plusMinutes(5), null, null, true);
    }

    private static EnrollmentResult cancelledResult() {
        return new EnrollmentResult(ENROLLMENT_ID, KLASS_ID, "스프링 부트 입문",
                EnrollmentStatus.CANCELLED, EnrollmentSource.DIRECT,
                CREATED_AT, null, CREATED_AT.plusMinutes(5), CREATED_AT.plusHours(2),
                CancelReason.USER, false);
    }

    /** 단건 응답의 필드. 신청·확정·취소·상세가 모두 이 형태다. */
    private FieldDescriptor[] enrollmentFields() {
        return new FieldDescriptor[] {
            fieldWithPath("success").type(JsonFieldType.BOOLEAN).description("성공 여부"),
            fieldWithPath("error").type(JsonFieldType.NULL).description("성공 시 null"),
            fieldWithPath("data.id").description("신청 PK. 확정·취소 API 의 경로 변수"),
            fieldWithPath("data.klassId").description("강의 PK"),
            fieldWithPath("data.klassTitle").description("강의 제목"),
            fieldWithPath("data.status").description("`PENDING` / `CONFIRMED` / `CANCELLED`"),
            fieldWithPath("data.source").description("`DIRECT`(직접 신청) / `WAITLIST`(승격)"),
            fieldWithPath("data.createdAt").description("신청 시각"),
            fieldWithPath("data.expiresAt").optional()
                    .description("결제 기한. `PENDING` 이 아니면 null"),
            fieldWithPath("data.confirmedAt").optional()
                    .description("결제 확정 시각. 취소 가능 기간의 기산점"),
            fieldWithPath("data.cancelledAt").optional().description("취소 시각"),
            fieldWithPath("data.cancelReason").optional()
                    .description("`USER`(사용자 취소) / `EXPIRED`(결제 기한 초과로 배치가 회수). "
                            + "`CANCELLED` 가 아니면 null. **만료 취소는 사용자가 요청한 적이 "
                            + "없으므로 이 값이 유일한 단서다**"),
            fieldWithPath("data.isCancellable").type(JsonFieldType.BOOLEAN)
                    .description("지금 취소할 수 있는지. **서버가 판정해 내려준다** — "
                            + "취소 가능 기간과 강의 종료일을 클라이언트가 계산하면 "
                            + "판정이 양쪽에 복제된다")
        };
    }

    @Nested
    @DisplayName("신청과 상태 전이")
    class Commands {

        @Test
        @DisplayName("수강 신청")
        void apply() throws Exception {
            authenticateAsStudent();
            given(applyEnrollmentUseCase.apply(any())).willReturn(pendingResult());

            mockMvc.perform(post("/v1/klasses/{klassId}/enrollments", KLASS_ID)
                            .header("Authorization", "Bearer " + ACCESS_TOKEN))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.status").value("PENDING"))
                    .andDo(document("수강신청-신청", ResourceSnippetParameters.builder()
                            .tag("Enrollment")
                            .summary("수강 신청")
                            .description("""
                                    강의에 신청한다. **요청 본문이 없다** — 필요한 것은 경로의 강의와
                                    토큰의 사용자뿐이다.

                                    상태는 항상 `PENDING`(결제 대기)에서 시작하고 **좌석을 즉시 점유한다.**
                                    `expiresAt` 까지 결제하지 않으면 그 자리는 회수 대상이 된다.

                                    거부 조건:
                                    - 404 `KLASS_NOT_FOUND` — 없는 강의
                                    - 403 `SELF_ENROLLMENT_FORBIDDEN` — **본인이 개설한 강의**
                                    - 409 `KLASS_NOT_OPEN` — 모집 중이 아님 (`DRAFT`·`CLOSED`)
                                    - 409 `DUPLICATE_ENROLLMENT` — 이미 활성 신청이 있음
                                    - 409 `KLASS_CAPACITY_FULL` — 정원 초과

                                    **정원이 차도 대기열로 자동 등록되지 않는다.** 대기를 원하면
                                    `POST /v1/klasses/{klassId}/waitlists` 를 별도로 호출한다.""")
                            .requestHeaders(authorizationHeader())
                            .pathParameters(parameterWithName("klassId").description("강의 PK"))
                            .responseFields(enrollmentFields())
                            .responseSchema(schema("EnrollmentResponse"))
                            .build()));
        }

        @Test
        @DisplayName("결제 완료 처리")
        void confirm() throws Exception {
            authenticateAsStudent();
            given(confirmEnrollmentUseCase.confirm(any())).willReturn(confirmedResult());

            mockMvc.perform(post("/v1/enrollments/{id}/confirm", ENROLLMENT_ID)
                            .header("Authorization", "Bearer " + ACCESS_TOKEN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                    .andExpect(jsonPath("$.data.expiresAt").doesNotExist())
                    .andDo(document("수강신청-결제완료", ResourceSnippetParameters.builder()
                            .tag("Enrollment")
                            .summary("결제 완료 처리")
                            .description("""
                                    `PENDING` 을 `CONFIRMED` 로 전이한다. **본인의 신청만 가능하다.**

                                    결제 게이트웨이 연동은 이 프로젝트의 범위가 아니다 — 외부에서 결제가
                                    끝났다는 신호로 상태만 바꾼다.

                                    **좌석 점유 수는 변하지 않는다.** `PENDING` 이 이미 점유하고 있었기
                                    때문이며, 그래서 이 요청만 강의 락을 잡지 않는다.

                                    확정되면 `expiresAt` 이 null 이 되고 `confirmedAt` 이 채워진다 —
                                    그 시각이 **취소 가능 기간의 기산점**이다.

                                    거부 조건:
                                    - 404 `ENROLLMENT_NOT_FOUND`
                                    - 403 `NOT_ENROLLMENT_OWNER` — 타인의 신청
                                    - 409 `INVALID_ENROLLMENT_STATUS_TRANSITION` — 이미 확정·취소됨
                                    - 409 `ENROLLMENT_EXPIRED` — 결제 기한이 지남""")
                            .requestHeaders(authorizationHeader())
                            .pathParameters(parameterWithName("id").description("신청 PK"))
                            .responseFields(enrollmentFields())
                            .responseSchema(schema("EnrollmentResponse"))
                            .build()));
        }

        @Test
        @DisplayName("수강 취소")
        void cancel() throws Exception {
            authenticateAsStudent();
            given(cancelEnrollmentUseCase.cancel(any())).willReturn(cancelledResult());

            mockMvc.perform(post("/v1/enrollments/{id}/cancel", ENROLLMENT_ID)
                            .header("Authorization", "Bearer " + ACCESS_TOKEN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                    .andExpect(jsonPath("$.data.isCancellable").value(false))
                    .andDo(document("수강신청-취소", ResourceSnippetParameters.builder()
                            .tag("Enrollment")
                            .summary("수강 취소")
                            .description("""
                                    신청을 취소하고 **좌석을 반납한다.** 본인의 신청만 가능하다.

                                    강의가 모집 중(`OPEN`)이고 대기자가 있으면 **같은 트랜잭션에서
                                    1건이 승격**된다 — 반납된 자리가 일반 신청자에게 노출되는 틈 없이
                                    대기자에게 이전된다.

                                    ### 상태별 취소 조건

                                    | 상태 | 조건 |
                                    |------|------|
                                    | `PENDING` | **무조건 가능.** 결제 전이라 환불할 것이 없다 |
                                    | `CONFIRMED` | 결제일 + 취소 가능 기간 **이내**이고, 강의 종료일이 지나지 않아야 한다 |

                                    두 관문이 모두 걸리면 **강의 종료가 먼저 보고된다** — 기간 초과는
                                    "다음엔 더 빨리"지만 강의 종료는 아무리 빨라도 성립하지 않으므로
                                    사용자에게 해야 할 이야기가 다르다.

                                    거부 조건:
                                    - 404 `ENROLLMENT_NOT_FOUND`
                                    - 403 `NOT_ENROLLMENT_OWNER`
                                    - 409 `INVALID_ENROLLMENT_STATUS_TRANSITION` — 이미 취소됨
                                    - 409 `KLASS_ALREADY_FINISHED` — 강의가 끝남
                                    - 409 `CANCELLATION_PERIOD_EXPIRED` — 취소 가능 기간 초과""")
                            .requestHeaders(authorizationHeader())
                            .pathParameters(parameterWithName("id").description("신청 PK"))
                            .responseFields(enrollmentFields())
                            .responseSchema(schema("EnrollmentResponse"))
                            .build()));
        }
    }

    @Nested
    @DisplayName("조회")
    class Queries {

        @Test
        @DisplayName("신청 상세")
        void detail() throws Exception {
            authenticateAsStudent();
            given(findEnrollmentUseCase.findById(anyLong(), anyLong()))
                    .willReturn(confirmedResult());

            mockMvc.perform(get("/v1/enrollments/{id}", ENROLLMENT_ID)
                            .header("Authorization", "Bearer " + ACCESS_TOKEN))
                    .andExpect(status().isOk())
                    .andDo(document("수강신청-상세", ResourceSnippetParameters.builder()
                            .tag("Enrollment")
                            .summary("신청 상세 조회")
                            .description("""
                                    신청 하나를 조회한다. **본인 것만 볼 수 있다.**

                                    타인의 것이면 403 `NOT_ENROLLMENT_OWNER` 다. 강의 상세가 타인의
                                    초안을 404 로 감추는 것과 다른데, 초안은 존재 자체가 비밀이지만
                                    신청 id 는 연속된 정수라 감춰봐야 존재가 추측되기 때문이다.""")
                            .requestHeaders(authorizationHeader())
                            .pathParameters(parameterWithName("id").description("신청 PK"))
                            .responseFields(enrollmentFields())
                            .responseSchema(schema("EnrollmentResponse"))
                            .build()));
        }

        @Test
        @DisplayName("내 신청 목록")
        void listMine() throws Exception {
            authenticateAsStudent();
            given(listEnrollmentUseCase.listMine(anyLong(), any()))
                    .willReturn(new CursorPageResult<>(List.of(new EnrollmentSummaryResult(
                            ENROLLMENT_ID, KLASS_ID, "스프링 부트 입문",
                            EnrollmentStatus.CONFIRMED, EnrollmentSource.DIRECT,
                            CREATED_AT, null, null, true)), true, ENROLLMENT_ID));

            mockMvc.perform(get("/v1/enrollments/me")
                            .param("size", "20")
                            .header("Authorization", "Bearer " + ACCESS_TOKEN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items[0].klassTitle").value("스프링 부트 입문"))
                    .andDo(document("수강신청-내목록", ResourceSnippetParameters.builder()
                            .tag("Enrollment")
                            .summary("내 신청 목록")
                            .description("""
                                    내가 신청한 강의 목록. **취소한 것까지 전부** 나온다 — 내 기록이므로
                                    가리지 않는다. 특정 상태만 보려면 `status` 를 지정한다.

                                    커서 페이지네이션이다. 다음 페이지는 응답의 `nextCursor` 를
                                    `cursor` 로 넘겨 요청한다. **총 개수는 제공하지 않는다** — 커서
                                    방식의 이점이 건너뛴 행을 읽지 않는 것인데, 총 개수를 세면 그 이점이
                                    사라진다.

                                    `id` 는 확정·취소 API 의 경로 변수다.""")
                            .requestHeaders(authorizationHeader())
                            .queryParameters(
                                    parameterWithName("cursor").optional()
                                            .description("직전 페이지의 `nextCursor`. 없으면 첫 페이지"),
                                    parameterWithName("size").optional()
                                            .description("조회 개수. 1~100, 기본 20"),
                                    parameterWithName("status").optional()
                                            .description("상태 필터. 미지정이면 전체"))
                            .responseFields(
                                    fieldWithPath("success").type(JsonFieldType.BOOLEAN)
                                            .description("성공 여부"),
                                    fieldWithPath("error").type(JsonFieldType.NULL)
                                            .description("성공 시 null"),
                                    fieldWithPath("data.items[].id").description("신청 PK"),
                                    fieldWithPath("data.items[].klassId").description("강의 PK"),
                                    fieldWithPath("data.items[].klassTitle").description("강의 제목"),
                                    fieldWithPath("data.items[].status").description("신청 상태"),
                                    fieldWithPath("data.items[].source").description("신청 출처"),
                                    fieldWithPath("data.items[].createdAt").description("신청 시각"),
                                    fieldWithPath("data.items[].expiresAt").optional()
                                            .description("결제 기한. `PENDING` 이 아니면 null"),
                                    fieldWithPath("data.items[].cancelReason").optional()
                                            .description("`USER` / `EXPIRED`. "
                                                    + "취소가 아니면 null"),
                                    fieldWithPath("data.items[].isCancellable")
                                            .type(JsonFieldType.BOOLEAN)
                                            .description("지금 취소할 수 있는지"),
                                    fieldWithPath("data.hasNext").type(JsonFieldType.BOOLEAN)
                                            .description("다음 페이지 존재 여부"),
                                    fieldWithPath("data.nextCursor").optional()
                                            .description("다음 요청의 `cursor`. `hasNext` 가 false 면 null"))
                            .responseSchema(schema("EnrollmentSummaryPage"))
                            .build()));
        }

        @Test
        @DisplayName("강의별 수강생 목록")
        void listByKlass() throws Exception {
            authenticateAsCreator();
            given(listEnrollmentUseCase.listByKlass(anyLong(), anyLong(), any()))
                    .willReturn(new CursorPageResult<>(List.of(new KlassEnrollmentResult(
                            ENROLLMENT_ID, STUDENT_ID, "student",
                            EnrollmentStatus.CONFIRMED, EnrollmentSource.DIRECT,
                            CREATED_AT, CREATED_AT.plusMinutes(5), null)), false, null));

            mockMvc.perform(get("/v1/klasses/{klassId}/enrollments", KLASS_ID)
                            .param("size", "20")
                            .header("Authorization", "Bearer " + ACCESS_TOKEN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items[0].username").value("student"))
                    .andDo(document("수강신청-강의별수강생", ResourceSnippetParameters.builder()
                            .tag("Enrollment")
                            .summary("강의별 수강생 목록 (크리에이터 전용)")
                            .description("""
                                    강의의 수강생 명단. **`ROLE_CREATOR` 권한과 소유권이 모두 필요하다.**

                                    권한만으로는 부족하다 — 그것만 검사하면 크리에이터끼리 서로의 명단을
                                    볼 수 있다. 남의 강의면 403 `NOT_KLASS_OWNER` 다.

                                    취소한 수강생도 기본으로 포함된다. 크리에이터가 이탈을 확인할 수
                                    있어야 하므로 감추지 않는다.

                                    내 신청 목록과 담기는 것이 반대다 — 그쪽은 *어느 강의인지*를,
                                    여기는 *누구인지*를 담는다. 강의는 경로에 이미 있고, 수강생 정보는
                                    남의 개인정보라 내 목록에 들어갈 이유가 없다.""")
                            .requestHeaders(authorizationHeader())
                            .pathParameters(parameterWithName("klassId").description("강의 PK"))
                            .queryParameters(
                                    parameterWithName("cursor").optional()
                                            .description("직전 페이지의 `nextCursor`"),
                                    parameterWithName("size").optional()
                                            .description("조회 개수. 1~100, 기본 20"),
                                    parameterWithName("status").optional()
                                            .description("상태 필터. 미지정이면 전체"))
                            .responseFields(
                                    fieldWithPath("success").type(JsonFieldType.BOOLEAN)
                                            .description("성공 여부"),
                                    fieldWithPath("error").type(JsonFieldType.NULL)
                                            .description("성공 시 null"),
                                    fieldWithPath("data.items[].id").description("신청 PK"),
                                    fieldWithPath("data.items[].userId").description("수강생 PK"),
                                    fieldWithPath("data.items[].username")
                                            .description("수강생 로그인 아이디"),
                                    fieldWithPath("data.items[].status").description("신청 상태"),
                                    fieldWithPath("data.items[].source").description("신청 출처"),
                                    fieldWithPath("data.items[].createdAt").description("신청 시각"),
                                    fieldWithPath("data.items[].confirmedAt").optional()
                                            .description("결제 확정 시각. 확정 전이면 null"),
                                    fieldWithPath("data.items[].cancelReason").optional()
                                            .description("`USER` / `EXPIRED`. "
                                                    + "이탈 사유를 개설자가 구분할 수 있다"),
                                    fieldWithPath("data.hasNext").type(JsonFieldType.BOOLEAN)
                                            .description("다음 페이지 존재 여부"),
                                    fieldWithPath("data.nextCursor").optional()
                                            .description("다음 요청의 `cursor`"))
                            .responseSchema(schema("KlassEnrollmentPage"))
                            .build()));
        }
    }

    @Nested
    @DisplayName("파라미터 검증")
    class ParameterValidation {

        @Test
        @DisplayName("size 가 범위를 벗어나면 400 — @Validated 없이 내장 검증이 처리한다")
        void rejectsOutOfRangeSize() throws Exception {
            authenticateAsStudent();

            mockMvc.perform(get("/v1/enrollments/me")
                            .param("size", "101")
                            .header("Authorization", "Bearer " + ACCESS_TOKEN))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        }

        @Test
        @DisplayName("알 수 없는 status 는 400 — enum 변환 실패가 500 이 되면 안 된다")
        void rejectsUnknownStatus() throws Exception {
            authenticateAsStudent();

            mockMvc.perform(get("/v1/enrollments/me")
                            .param("status", "UNKNOWN")
                            .header("Authorization", "Bearer " + ACCESS_TOKEN))
                    .andExpect(status().isBadRequest());
        }
    }
}
