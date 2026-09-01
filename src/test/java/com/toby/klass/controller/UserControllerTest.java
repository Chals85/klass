package com.toby.klass.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.epages.restdocs.apispec.ResourceSnippetParameters;
import com.toby.klass.infrastructure.security.config.SecurityConfig;
import com.toby.klass.infrastructure.security.filter.JwtAuthenticationFilter;
import com.toby.klass.user.adapter.in.web.controller.UserController;
import com.toby.klass.user.application.dto.UserResult;
import com.toby.klass.user.application.port.in.FindUserUseCase;
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
import org.springframework.restdocs.payload.JsonFieldType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 사용자 조회 API 문서화 테스트.
 *
 * <p>제외 설정의 근거는 {@code AuthControllerTest} 참조.
 *
 * <p>Design Ref: §8.2 L1 API 테스트 시나리오
 */
@WebMvcTest(controllers = UserController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {SecurityConfig.class, JwtAuthenticationFilter.class}))
@AutoConfigureRestDocs
@AutoConfigureMockMvc(addFilters = false)
class UserControllerTest extends BaseControllerTest {

    private static final String ACCESS_TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.signature";
    private static final LocalDateTime CREATED_AT = LocalDateTime.parse("2026-08-30T00:00:00");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FindUserUseCase findUserUseCase;

    private UserResult chals() {
        return new UserResult(1L, "chals", List.of("ROLE_USER"), true, CREATED_AT);
    }

    @Test
    @DisplayName("내 정보 조회")
    void me() throws Exception {
        authenticateAs(1L, "chals");
        given(findUserUseCase.findById(1L)).willReturn(chals());

        mockMvc.perform(get("/v1/users/me")
                        .header("Authorization", "Bearer " + ACCESS_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("chals"))
                .andDo(document("내정보-조회", ResourceSnippetParameters.builder()
                        .tag("User")
                        .summary("내 정보 조회")
                        .description("""
                                Access 토큰으로 인증된 사용자의 정보를 조회한다.

                                토큰 클레임만으로 응답하지 않고 DB 를 다시 읽는다 —
                                `enabled`·`createdAt` 은 토큰에 없고, 권한 변경도 즉시 반영돼야 하기 때문이다.""")
                        .requestHeaders(authorizationHeader())
                        .responseFields(
                                fieldWithPath("success").type(JsonFieldType.BOOLEAN).description("성공 여부"),
                                fieldWithPath("error").type(JsonFieldType.NULL).description("성공 시 null"),
                                fieldWithPath("data.id").description("사용자 PK"),
                                fieldWithPath("data.username").description("로그인 아이디"),
                                fieldWithPath("data.roles").description("권한 목록"),
                                fieldWithPath("data.isEnabled").description("활성 여부"),
                                fieldWithPath("data.createdAt").description("생성 시각. 서버 시간대의 ISO-8601 로컬 일시 (예: `2026-08-30T00:00:00`)"))
                        .responseSchema(schema("UserResponse"))
                        .build()));
    }
}
