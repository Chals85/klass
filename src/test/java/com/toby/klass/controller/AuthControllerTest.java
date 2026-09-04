package com.toby.klass.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.epages.restdocs.apispec.ResourceSnippetParameters;
import com.epages.restdocs.apispec.ResourceSnippetParametersBuilder;
import com.toby.klass.auth.adapter.in.web.controller.AuthController;
import com.toby.klass.auth.application.dto.TokenResult;
import com.toby.klass.auth.application.port.in.LoginUseCase;
import com.toby.klass.auth.application.port.in.LogoutUseCase;
import com.toby.klass.auth.application.port.in.ReissueTokenUseCase;
import com.toby.klass.auth.domain.error.AuthError;
import com.toby.klass.infrastructure.security.config.SecurityConfig;
import java.time.LocalDateTime;
import com.toby.klass.infrastructure.security.filter.JwtAuthenticationFilter;
import org.junit.jupiter.api.DisplayName;
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
 * 인증 API 문서화 테스트.
 *
 * <p>여기서 남기는 스니펫이 {@code openapi3.json} 의 {@code /v1/auth/*} 경로가 된다.
 *
 * <h2>왜 SecurityConfig 와 필터를 스캔에서 제외하는가</h2>
 * {@code @WebMvcTest} 는 컨트롤러만 올리는 게 아니라 {@code WebSecurityConfigurer} 와
 * {@code Filter} 타입 빈도 함께 스캔한다. 그대로 두면 {@link JwtAuthenticationFilter} 가
 * 생성되면서 {@code TokenParserPort} 빈까지 요구해 컨텍스트가 뜨지 않는다
 * ({@code addFilters = false} 는 필터를 <b>체인에 등록하지 않을</b> 뿐, 빈 생성은 막지 못한다).
 * 인증 동작은 L4 통합 테스트가 검증하므로 여기서는 둘 다 제외한다.
 *
 * <p>Design Ref: §8.2 L1 / L2 시나리오 #1~#6
 */
@WebMvcTest(controllers = AuthController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {SecurityConfig.class, JwtAuthenticationFilter.class}))
@AutoConfigureRestDocs
@AutoConfigureMockMvc(addFilters = false)
class AuthControllerTest extends BaseControllerTest {

    private static final String ACCESS_TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.signature";
    private static final String REFRESH_TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.refresh";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean private LoginUseCase loginUseCase;
    @MockitoBean private ReissueTokenUseCase reissueTokenUseCase;
    @MockitoBean private LogoutUseCase logoutUseCase;

    private TokenResult tokenResult() {
        return new TokenResult(
                TokenResult.BEARER,
                ACCESS_TOKEN, 1800, LocalDateTime.parse("2026-08-30T12:30:00"),
                REFRESH_TOKEN, 1209600, LocalDateTime.parse("2026-09-13T12:00:00"));
    }

    /** 토큰 응답의 {@code data.*} 필드 서술. 로그인과 재발급이 공유한다. */
    private ResourceSnippetParametersBuilder tokenResponseFields(ResourceSnippetParametersBuilder builder) {
        return builder.responseFields(
                fieldWithPath("success").type(JsonFieldType.BOOLEAN).description("성공 여부"),
                fieldWithPath("error").type(JsonFieldType.NULL).description("성공 시 null"),
                fieldWithPath("data.tokenType").description("인증 스킴. 항상 `Bearer`"),
                fieldWithPath("data.accessToken").description("Access 토큰. 보호된 API 호출에 쓴다"),
                fieldWithPath("data.accessTokenExpiresIn")
                        .description("Access 유효 시간(초). 클라이언트 시계 기준으로 갱신 타이머를 걸 때 쓴다"),
                fieldWithPath("data.accessTokenExpiresAt")
                        .description("Access 만료 일시 (서버 시간대). 표시·저장용"),
                fieldWithPath("data.refreshToken").description("Refresh 토큰. 재발급에만 쓴다"),
                fieldWithPath("data.refreshTokenExpiresIn").description("Refresh 유효 시간(초)"),
                fieldWithPath("data.refreshTokenExpiresAt")
                        .description("Refresh 만료 일시 (서버 시간대)"));
    }

    @Test
    @DisplayName("로그인 - 토큰 발급")
    void login() throws Exception {
        given(loginUseCase.login(any())).willReturn(tokenResult());

        mockMvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"chals","password":"test"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").exists())
                .andExpect(jsonPath("$.data.refreshToken").exists())
                .andDo(document("로그인-토큰발급", tokenResponseFields(
                        ResourceSnippetParameters.builder()
                                .tag("Auth")
                                .summary("로그인")
                                .description("""
                                        아이디·비밀번호로 Access/Refresh 토큰을 발급받는다.

                                        기본 계정 `chals / test` 가 기동 시 자동 생성되므로 별도 가입 없이 바로 호출할 수 있다.""")
                                .requestFields(
                                        fieldWithPath("username").description("로그인 아이디 (최대 50자)"),
                                        fieldWithPath("password").description("평문 비밀번호"))
                                .requestSchema(schema("LoginRequest"))
                                .responseSchema(schema("TokenResponse")))
                        .build()));
    }

    @Test
    @DisplayName("로그인 - 입력값 검증 실패")
    void loginWithBlankFields() throws Exception {
        mockMvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"","password":""}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details.username").exists())
                .andDo(document("로그인-검증실패", ResourceSnippetParameters.builder()
                        .tag("Auth")
                        .summary("로그인 실패 - 입력값 검증")
                        .description("필드가 비어 있으면 400 과 함께 `details` 에 필드별 메시지가 담긴다.")
                        .requestFields(
                                fieldWithPath("username").description("로그인 아이디"),
                                fieldWithPath("password").description("평문 비밀번호"))
                        .responseFields(errorEnvelope())
                        .responseSchema(schema("ErrorResponse"))
                        .build()));
    }

    @Test
    @DisplayName("로그인 - 자격 증명 불일치")
    void loginWithInvalidCredentials() throws Exception {
        willThrow(AuthError.INVALID_CREDENTIALS.toException()).given(loginUseCase).login(any());

        mockMvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"chals","password":"wrong"}"""))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"))
                .andDo(document("로그인-자격증명불일치", ResourceSnippetParameters.builder()
                        .tag("Auth")
                        .summary("로그인 실패 - 자격 증명 불일치")
                        .description("""
                                아이디가 없는 경우와 비밀번호가 틀린 경우를 **구분하지 않는다**.
                                구분하면 어떤 아이디가 존재하는지 알아낼 수 있다(사용자 열거 공격).""")
                        .requestFields(
                                fieldWithPath("username").description("로그인 아이디"),
                                fieldWithPath("password").description("평문 비밀번호"))
                        .responseFields(errorEnvelope())
                        .responseSchema(schema("ErrorResponse"))
                        .build()));
    }

    @Test
    @DisplayName("토큰 재발급 - 회전")
    void reissue() throws Exception {
        given(reissueTokenUseCase.reissue(any())).willReturn(tokenResult());

        mockMvc.perform(post("/v1/auth/reissue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}""".formatted(REFRESH_TOKEN)))
                .andExpect(status().isOk())
                .andDo(document("토큰재발급-회전", tokenResponseFields(
                        ResourceSnippetParameters.builder()
                                .tag("Auth")
                                .summary("토큰 재발급")
                                .description("""
                                        Refresh 토큰을 회전(RTR)시켜 새 토큰 쌍을 발급한다.

                                        **기존 Refresh 토큰은 폐기되므로 한 번만 쓸 수 있다.**
                                        응답으로 받은 새 Refresh 토큰을 반드시 저장해야 한다.""")
                                .requestFields(fieldWithPath("refreshToken").description("현재 보유한 Refresh 토큰"))
                                .requestSchema(schema("ReissueRequest"))
                                .responseSchema(schema("TokenResponse")))
                        .build()));
    }

    @Test
    @DisplayName("토큰 재발급 - 재사용 감지")
    void reissueWithReusedToken() throws Exception {
        willThrow(AuthError.REFRESH_TOKEN_REUSED.toException())
                .given(reissueTokenUseCase).reissue(any());

        mockMvc.perform(post("/v1/auth/reissue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}""".formatted(REFRESH_TOKEN)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("REFRESH_TOKEN_REUSED"))
                .andDo(document("토큰재발급-재사용감지", ResourceSnippetParameters.builder()
                        .tag("Auth")
                        .summary("토큰 재발급 실패 - 재사용 감지")
                        .description("""
                                이미 회전된 Refresh 토큰을 다시 사용하면 토큰 탈취로 간주한다.

                                **해당 사용자의 모든 Refresh 토큰이 무효화되므로 다시 로그인해야 한다.**
                                정상 사용자와 공격자 중 누가 먼저 썼는지 알 수 없어 양쪽 모두 끊는다.""")
                        .requestFields(fieldWithPath("refreshToken").description("이미 사용된 Refresh 토큰"))
                        .responseFields(errorEnvelope())
                        .responseSchema(schema("ErrorResponse"))
                        .build()));
    }

    @Test
    @DisplayName("로그아웃")
    void logout() throws Exception {
        authenticateAs(1L, "chals");

        mockMvc.perform(post("/v1/auth/logout")
                        .header("Authorization", "Bearer " + ACCESS_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}""".formatted(REFRESH_TOKEN)))
                .andExpect(status().isNoContent())
                .andDo(document("로그아웃", ResourceSnippetParameters.builder()
                        .tag("Auth")
                        .summary("로그아웃")
                        .description("""
                                Refresh 토큰을 폐기한다. 응답 본문은 없다.

                                **멱등하다.** 이미 로그아웃했거나 존재하지 않는 토큰을 보내도 204 를 반환한다 —
                                토큰의 존재 여부를 응답으로 알려주지 않기 위함이다.""")
                        .requestHeaders(authorizationHeader())
                        .requestFields(fieldWithPath("refreshToken").description("폐기할 Refresh 토큰"))
                        .requestSchema(schema("LogoutRequest"))
                        .build()));
    }
}
