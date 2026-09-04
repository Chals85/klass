package com.toby.klass.infrastructure.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * springdoc 보조 문서 설정.
 *
 * <h2>정본이 아니다</h2>
 * 이 프로젝트의 API 문서 정본은 <b>RestDocs 테스트가 만드는 {@code openapi3.json}</b> 이며
 * {@code /docs/api-guide.html}(Redoc), {@code /docs/api-test.html}(Swagger UI) 에서 볼 수 있다.
 * springdoc 은 어노테이션만 읽어 만드는 런타임 문서라 <b>실제 동작을 보장하지 않는다</b> —
 * 응답 예시가 실제 응답과 다를 수 있고, 테스트를 쓰지 않아도 생성된다.
 *
 * <h2>컨트롤러에 어노테이션을 붙이지 않는다</h2>
 * {@code @Tag}·{@code @Operation} 을 쓰지 않으므로 이 문서는 <b>경로와 시그니처만 담긴
 * 뼈대</b>다. 의도한 것이다 — 엔드포인트 설명의 단일 출처는 RestDocs 테스트의 스니펫
 * 정의이고, 어노테이션으로 같은 설명을 한 번 더 쓰면 두 곳이 서로 어긋난다.
 *
 * <p>그럼에도 springdoc 을 남겨두는 이유는 <b>안전망</b>이다. restdocs-api-spec 이
 * Spring Boot 4 에서 깨질 경우(Plan R-1) 이쪽을 정본으로 승격할 수 있다. Phase 0 검증에서
 * 정상 동작을 확인했으므로 현재는 대기 상태이며, 승격이 필요해지면 그때 어노테이션을 붙인다.
 *
 * <p>Design Ref: project-setup §4.3 문서 산출물
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    /**
     * springdoc 이 {@code /v3/api-docs} 로 노출할 문서의 메타 정보.
     *
     * <p>Bearer 인증 스킴을 등록해 Swagger UI 의 Authorize 버튼으로 토큰을 넣을 수 있게 한다.
     */
    @Bean
    public OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("JWT Authentication Sample API (springdoc 보조)")
                        .description("""
                                Spring Boot 4 + Security 7 헥사고날 JWT 인증 예제.

                                **이 문서는 보조입니다.** 정본은 RestDocs 가 생성한 `/docs/api-guide.html` 입니다.
                                컨트롤러에 Swagger 어노테이션을 붙이지 않으므로 여기에는 경로와 시그니처만 담깁니다.""")
                        .version("1.0.0"))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("로그인 응답의 accessToken 을 넣는다")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
