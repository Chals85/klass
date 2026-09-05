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
import com.toby.klass.enrollment.application.dto.WaitlistResult;
import com.toby.klass.enrollment.application.port.in.GiveUpWaitlistUseCase;
import com.toby.klass.enrollment.application.port.in.ListWaitlistUseCase;
import com.toby.klass.enrollment.application.port.in.RegisterWaitlistUseCase;
import com.toby.klass.infrastructure.security.config.SecurityConfig;
import com.toby.klass.infrastructure.security.filter.JwtAuthenticationFilter;
import com.toby.klass.waitlist.adapter.in.web.controller.WaitlistController;
import com.toby.klass.waitlist.domain.WaitlistStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
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
 * 대기열 API 계약 + RestDocs 스니펫 (L3).
 *
 * <p>토큰 값은 {@code EnrollmentControllerTest} 와 같아야 한다 —
 * {@code restdocs-api-spec} 이 payload 를 base64 JSON 으로 파싱하므로 임의 문자열을 넣으면
 * 스니펫 생성이 예외로 죽는다.
 *
 * <p>Design Ref: enrollment-management §9.4
 */
@WebMvcTest(controllers = WaitlistController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {SecurityConfig.class, JwtAuthenticationFilter.class}))
@AutoConfigureRestDocs
@AutoConfigureMockMvc(addFilters = false)
class WaitlistControllerTest extends BaseControllerTest {

    private static final String ACCESS_TOKEN =
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.signature";
    private static final Long KLASS_ID = 7L;
    private static final Long WAITLIST_ID = 91L;
    private static final Long STUDENT_ID = 2L;

    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 9, 3, 10, 0);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RegisterWaitlistUseCase registerWaitlistUseCase;

    @MockitoBean
    private GiveUpWaitlistUseCase giveUpWaitlistUseCase;

    @MockitoBean
    private ListWaitlistUseCase listWaitlistUseCase;

    private static WaitlistResult waitingResult() {
        return new WaitlistResult(WAITLIST_ID, KLASS_ID, "스프링 부트 입문", 3,
                WaitlistStatus.WAITING, CREATED_AT, null);
    }

    private static WaitlistResult cancelledResult() {
        return new WaitlistResult(WAITLIST_ID, KLASS_ID, "스프링 부트 입문", 3,
                WaitlistStatus.CANCELLED, CREATED_AT, null);
    }

    /** 대기 응답의 필드. 등록과 포기가 같은 형태다. */
    private FieldDescriptor[] waitlistFields(String prefix) {
        return new FieldDescriptor[] {
            fieldWithPath("success").type(JsonFieldType.BOOLEAN).description("성공 여부"),
            fieldWithPath("error").type(JsonFieldType.NULL).description("성공 시 null"),
            fieldWithPath(prefix + "id")
                    .description("대기 PK. **대기 포기 API 의 경로 변수**다"),
            fieldWithPath(prefix + "klassId").description("강의 PK"),
            fieldWithPath(prefix + "klassTitle").description("강의 제목"),
            fieldWithPath(prefix + "position")
                    .description("대기 순번. **취소된 앞 순번은 gap 으로 남으므로 실제 대기 "
                            + "인원수와 다를 수 있다** — \"내 앞에 N명\"으로 읽으면 안 된다"),
            fieldWithPath(prefix + "status")
                    .description("`WAITING` / `PROMOTED`(승격됨) / `CANCELLED`(포기·정리됨)"),
            fieldWithPath(prefix + "createdAt").description("대기 등록 시각"),
            fieldWithPath(prefix + "promotedAt").optional()
                    .description("승격 시각. `PROMOTED` 가 아니면 null")
        };
    }

    @Test
    @DisplayName("대기열 등록")
    void register() throws Exception {
        authenticateAs(STUDENT_ID, "student");
        given(registerWaitlistUseCase.register(any())).willReturn(waitingResult());

        mockMvc.perform(post("/v1/klasses/{klassId}/waitlists", KLASS_ID)
                        .header("Authorization", "Bearer " + ACCESS_TOKEN))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("WAITING"))
                .andExpect(jsonPath("$.data.position").value(3))
                .andDo(document("대기열-등록", ResourceSnippetParameters.builder()
                        .tag("대기열")
                        .summary("대기열 등록")
                        .description("""
                                정원이 찬 강의의 대기열에 등록한다. **요청 본문이 없다.**

                                **신청이 정원 초과로 거부됐을 때 자동으로 실행되지 않는다** —
                                요청하지 않은 사용자를 대기열에 넣는 것은 월권이므로 사용자가
                                이 API 를 직접 호출해야 한다.

                                누군가 취소하면 순번이 앞선 대기자가 **같은 트랜잭션에서 승격**되어
                                `PENDING` 신청이 생긴다. 승격은 한 번에 1건이고, 반납된 좌석이 일반
                                신청자에게 노출되는 틈이 없다.

                                거부 조건:
                                - 404 `KLASS_NOT_FOUND`
                                - 403 `SELF_ENROLLMENT_FORBIDDEN` — **본인이 개설한 강의**
                                - 409 `KLASS_NOT_OPEN` — 모집 중이 아님
                                - 409 `DUPLICATE_ENROLLMENT` — 이미 활성 신청이 있음
                                - 409 `DUPLICATE_WAITLIST` — 이미 대기 중
                                - 409 `WAITLIST_SEAT_AVAILABLE` — **자리가 남아 있음.** 승격은 좌석
                                  반납에서만 트리거되므로 빈자리가 있으면 영구히 기다리게 된다.
                                  신청 API 를 쓸 것

                                응답의 `id` 를 보관해야 한다 — 대기 포기 API 의 경로 변수이며,
                                놓치면 `GET /v1/waitlists/me` 로 다시 찾아야 한다.""")
                        .requestHeaders(authorizationHeader())
                        .pathParameters(parameterWithName("klassId").description("강의 PK"))
                        .responseFields(waitlistFields("data."))
                        .responseSchema(schema("WaitlistResponse"))
                        .build()));
    }

    @Test
    @DisplayName("내 대기 목록")
    void listMine() throws Exception {
        authenticateAs(STUDENT_ID, "student");
        given(listWaitlistUseCase.listMineWaitlist(anyLong(), any()))
                .willReturn(new CursorPageResult<>(List.of(waitingResult()), false, null));

        mockMvc.perform(get("/v1/waitlists/me")
                        .param("size", "20")
                        .header("Authorization", "Bearer " + ACCESS_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].position").value(3))
                .andDo(document("대기열-내목록", ResourceSnippetParameters.builder()
                        .tag("대기열")
                        .summary("내 대기 목록")
                        .description("""
                                내가 등록한 대기 목록. **승격·포기한 기록도 포함한다** — 내 이력이다.

                                상태 필터를 받지 않는다. 대기는 상태가 셋뿐이고 목록이 짧아 걸러낼
                                이유가 적다.

                                **대기 포기 API 의 `waitlistId` 를 얻는 경로다.** 등록 응답을 놓쳤다면
                                여기 말고는 알아낼 방법이 없다.

                                커서 페이지네이션이며 총 개수는 제공하지 않는다.""")
                        .requestHeaders(authorizationHeader())
                        .queryParameters(
                                parameterWithName("cursor").optional()
                                        .description("직전 페이지의 `nextCursor`. 없으면 첫 페이지"),
                                parameterWithName("size").optional()
                                        .description("조회 개수. 1~100, 기본 20"))
                        .responseFields(concat(waitlistFields("data.items[]."),
                                fieldWithPath("data.hasNext").type(JsonFieldType.BOOLEAN)
                                        .description("다음 페이지 존재 여부"),
                                fieldWithPath("data.nextCursor").optional()
                                        .description("다음 요청의 `cursor`. `hasNext` 가 false 면 null")))
                        .responseSchema(schema("WaitlistPage"))
                        .build()));
    }

    @Test
    @DisplayName("대기 포기")
    void giveUp() throws Exception {
        authenticateAs(STUDENT_ID, "student");
        given(giveUpWaitlistUseCase.giveUp(any())).willReturn(cancelledResult());

        mockMvc.perform(post("/v1/waitlists/{id}/cancel", WAITLIST_ID)
                        .header("Authorization", "Bearer " + ACCESS_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andDo(document("대기열-포기", ResourceSnippetParameters.builder()
                        .tag("대기열")
                        .summary("대기 포기")
                        .description("""
                                대기를 포기한다. **본인의 대기만 가능하다.**

                                **이미 승격됐으면 409 `WAITLIST_NOT_WAITING` 이다.** 승격이 먼저
                                커밋된 상황에서 포기시켜 버리면 배정된 좌석이 주인 없이 남는다 —
                                사용자는 "이미 자리가 배정되었다"를 안내받고, 그 좌석을 원하지 않으면
                                생성된 신청을 취소해야 한다.

                                포기한 순번은 **재사용하지 않고 gap 으로 남는다.** 순번 재배열은 여러
                                행을 갱신해 락 범위를 넓히고, 순번의 절대값이 사용자에게 의미를 갖지
                                않기 때문이다. 같은 강의에 **다시 등록하는 것은 허용된다.**

                                거부 조건:
                                - 404 `WAITLIST_NOT_FOUND`
                                - 403 `NOT_WAITLIST_OWNER`
                                - 409 `WAITLIST_NOT_WAITING` — 이미 승격·포기됨""")
                        .requestHeaders(authorizationHeader())
                        .pathParameters(parameterWithName("id").description("대기 PK"))
                        .responseFields(waitlistFields("data."))
                        .responseSchema(schema("WaitlistResponse"))
                        .build()));
    }

    /** 목록 응답은 항목 필드에 커서 필드를 덧붙인다. */
    private static FieldDescriptor[] concat(FieldDescriptor[] head, FieldDescriptor... tail) {
        FieldDescriptor[] merged = new FieldDescriptor[head.length + tail.length];
        System.arraycopy(head, 0, merged, 0, head.length);
        System.arraycopy(tail, 0, merged, head.length, tail.length);
        return merged;
    }
}
