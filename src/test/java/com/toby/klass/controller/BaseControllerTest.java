package com.toby.klass.controller;

import static com.epages.restdocs.apispec.ResourceDocumentation.resource;
import static org.springframework.restdocs.operation.preprocess.Preprocessors.preprocessRequest;
import static org.springframework.restdocs.operation.preprocess.Preprocessors.preprocessResponse;
import static org.springframework.restdocs.operation.preprocess.Preprocessors.prettyPrint;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.payload.PayloadDocumentation.subsectionWithPath;
import static org.springframework.restdocs.headers.HeaderDocumentation.headerWithName;

import com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper;
import com.epages.restdocs.apispec.ResourceSnippetParameters;
import com.epages.restdocs.apispec.Schema;
import com.toby.klass.infrastructure.security.principal.AuthenticatedUser;
import java.time.Instant;
import java.util.List;
import org.springframework.restdocs.headers.HeaderDescriptor;
import org.springframework.restdocs.payload.FieldDescriptor;
import org.springframework.restdocs.payload.JsonFieldType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.springframework.test.web.servlet.ResultHandler;

/**
 * RestDocs 문서화 테스트의 공통 기반.
 *
 * <p><b>이 테스트들이 곧 API 문서다.</b> 여기서 남긴 스니펫을 {@code openapi3} 태스크가
 * 모아 {@code openapi3.json} 을 만들고, 그것을 Redoc·Swagger UI 가 렌더링한다.
 * 테스트를 쓰지 않으면 문서가 나오지 않고, 문서가 없으면 jar 도 만들어지지 않는다.
 *
 * <h2>슬라이스 테스트의 한계 — 반드시 알고 있어야 한다</h2>
 * 하위 클래스는 {@code @WebMvcTest} + {@code addFilters = false} 로 돈다. 빠르고 컨트롤러
 * 계층이 격리되지만, <b>보안 필터가 꺼져 있어 JWT 인증이 실제로 동작하는지는 검증하지 못한다.</b>
 * 그 공백은 {@code AuthFlowIntegrationTest}(L4)가 메운다. 둘 중 하나만 있으면 안 된다.
 *
 * <p>Design Ref: §8.1 Test Scope, §8.2 L1 / L2 시나리오
 */
public abstract class BaseControllerTest {

    /** 인증된 principal 이 들고 있을 Access 토큰의 {@code jti}. 로그아웃 검증에 쓰인다. */
    protected static final String ACCESS_JTI = "11111111-2222-3333-4444-555555555555";

    /** 인증된 principal 이 들고 있을 Access 토큰의 만료 시각. */
    protected static final Instant ACCESS_TOKEN_EXPIRES_AT = Instant.parse("2026-08-30T12:30:00Z");

    /**
     * 인증된 사용자를 SecurityContext 에 직접 넣는다.
     *
     * <h2>왜 {@code .with(authentication(...))} 이 아닌가</h2>
     * 그 post-processor 는 {@code TestSecurityContextHolder} 에 값을 넣고, Security 필터
     * 체인의 {@code TestSecurityContextHolderPostProcessor} 가 그것을 실제
     * {@code SecurityContextHolder} 로 옮긴다. 그런데 {@code addFilters = false} 로 필터를
     * 껐으므로 그 이관이 일어나지 않아 <b>principal 이 null 로 주입되고 NPE 가 난다</b>.
     * 여기서는 컨텍스트를 직접 채워 리졸버가 바로 읽게 한다.
     *
     * <p>{@code @WithMockUser} 도 쓸 수 없다. principal 이 {@code String}/{@code UserDetails}
     * 로 고정돼 있어 커스텀 타입인 {@link AuthenticatedUser} 를 넣지 못한다.
     *
     * <p>{@code jti}/{@code tokenExpiresAt} 은 실제 필터라면 파싱된 Access 토큰에서
     * 오는 값이다. 필터를 껐으므로 여기서 고정값을 넣는다 — 로그아웃 컨트롤러가 이 값을
     * 폐기 목록에 올리므로 {@code null} 이면 그 경로를 검증할 수 없다.
     *
     * @param userId   principal 에 담을 사용자 id
     * @param username principal 에 담을 로그인 아이디
     */
    protected void authenticateAs(Long userId, String username) {
        authenticateAs(userId, username, List.of("ROLE_USER"));
    }

    /**
     * 권한까지 지정해 인증된 사용자를 넣는다.
     *
     * <p>강의 관리는 {@code ROLE_CREATOR} 를 요구하므로, 권한이 고정된 위 메서드로는
     * 그 경로를 그릴 수 없다.
     *
     * <p><b>다만 이 슬라이스에서 권한 자체는 검증되지 않는다.</b> {@code SecurityConfig} 가
     * 컨텍스트에서 배제되고 {@code addFilters = false} 라 {@code hasRole} 규칙이 적용되지
     * 않는다 — 여기서 넣는 권한은 <b>컨트롤러가 principal 에서 읽어 쓰는 값</b>일 뿐이다.
     * 권한이 실제로 막히는지는 {@code KlassFlowIntegrationTest}(L4)가 확인한다.
     *
     * @param roles 권한 문자열. {@code ROLE_} 접두어를 포함한 완전한 이름이다
     */
    protected void authenticateAs(Long userId, String username, List<String> roles) {
        AuthenticatedUser principal = new AuthenticatedUser(
                userId, username, roles, ACCESS_JTI, ACCESS_TOKEN_EXPIRES_AT);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null,
                        roles.stream().map(SimpleGrantedAuthority::new).toList()));
    }

    /** 테스트 간 인증 상태가 새지 않도록 정리한다. */
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /**
     * 문서용 Authorization 헤더 설명.
     *
     * <p>필터를 껐으므로 이 헤더가 실제 인증에 쓰이지는 않는다. 그럼에도 요청에 붙이고
     * 문서화하는 이유는, 이 스니펫이 {@code openapi3.json} 의 보안 요구사항 표기로
     * 이어지기 때문이다. 빠지면 문서를 보는 사람이 토큰이 필요한 줄 모른다.
     *
     * @return Authorization 헤더 서술자
     */
    protected HeaderDescriptor authorizationHeader() {
        return headerWithName("Authorization").description("Access Token (`Bearer {token}`)");
    }

    /** 성공 응답 봉투의 공통 필드. 각 API 는 여기에 {@code data.*} 를 덧붙인다. */
    protected FieldDescriptor[] successEnvelope() {
        return new FieldDescriptor[] {
            fieldWithPath("success").type(JsonFieldType.BOOLEAN).description("성공 여부 (항상 true)"),
            fieldWithPath("error").type(JsonFieldType.NULL).description("성공 시 null")
        };
    }

    /** 실패 응답 봉투의 공통 필드. 모든 에러 응답이 같은 형태를 쓴다. */
    protected FieldDescriptor[] errorEnvelope() {
        return new FieldDescriptor[] {
            fieldWithPath("success").type(JsonFieldType.BOOLEAN).description("성공 여부 (항상 false)"),
            fieldWithPath("data").type(JsonFieldType.NULL).description("실패 시 null"),
            fieldWithPath("error.code").type(JsonFieldType.STRING)
                    .description("에러 코드. enum 상수명이 그대로 쓰인다 (예: `INVALID_CREDENTIALS`)"),
            fieldWithPath("error.message").type(JsonFieldType.STRING).description("사용자에게 보여줄 메시지"),
            // subsectionWithPath 를 써야 details 안의 임의 키까지 한꺼번에 문서화된다.
            // fieldWithPath 로 두면 하위 키가 "문서화되지 않은 필드"로 잡혀 테스트가 실패한다.
            subsectionWithPath("error.details")
                    .description("필드별 상세. 입력 검증 실패에서만 채워지고 그 외에는 빈 객체")
        };
    }

    /**
     * 스니펫을 남기는 공통 헬퍼.
     *
     * @param identifier 스니펫 디렉터리 이름. 한글을 쓰면 문서 목록에서 알아보기 쉽다
     * @param parameters 태그·요약·필드 서술 등
     * @return MockMvc 에 적용할 결과 핸들러
     */
    protected ResultHandler document(String identifier, ResourceSnippetParameters parameters) {
        return MockMvcRestDocumentationWrapper.document(
                identifier,
                preprocessRequest(prettyPrint()),
                preprocessResponse(prettyPrint()),
                resource(parameters));
    }

    /** {@code ResourceSnippetParameters} 빌더에 스키마 이름을 붙이는 단축 표기. */
    protected Schema schema(String name) {
        return Schema.schema(name);
    }
}
