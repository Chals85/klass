package com.toby.klass.infrastructure.security.config;

import com.toby.klass.infrastructure.security.exception.CustomAccessDeniedHandler;
import com.toby.klass.infrastructure.security.exception.CustomAuthenticationEntryPoint;
import com.toby.klass.infrastructure.security.filter.JwtAuthenticationFilter;
import org.springframework.boot.security.autoconfigure.web.servlet.PathRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 설정.
 *
 * <h2>Spring Boot 3 코드를 그대로 옮기면 안 되는 이유</h2>
 * <ul>
 *   <li>Security 7 에서 {@code .and()} 가 <b>완전히 제거</b>됐다. 람다 DSL 만 쓴다</li>
 *   <li>Security 7 은 <b>CSRF 를 API 엔드포인트에도 기본 적용</b>한다. stateless REST 에서
 *       명시적으로 끄지 않으면 모든 POST 가 403 이 된다. 아래 {@code csrf(...disable)} 이
 *       장식이 아니라 필수인 이유다</li>
 * </ul>
 *
 * <p>Design Ref: §7 Security Considerations, Plan R-5
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** Spring 이 인스턴스를 만든다. 직접 호출하지 않는다. */
    public SecurityConfig() {
    }

    /** 인증 없이 접근 가능한 경로. 토큰을 아직 갖고 있지 않은 상태에서 호출하는 것들이다. */
    private static final String[] PUBLIC_ENDPOINTS = {
        "/v1/auth/login",
        "/v1/auth/reissue"
    };

    /**
     * 보안 필터 체인.
     *
     * <p>{@code formLogin}·{@code httpBasic} 을 명시하지 않았다. {@link HttpSecurity} 를
     * 직접 구성하면 Boot 의 기본 체인이 대체되므로, 선언하지 않은 인증 방식은 활성화되지 않는다.
     *
     * @param http           보안 설정 빌더
     * @param jwtFilter      토큰 검증 필터
     * @param entryPoint     인증 실패(401) 응답 생성기
     * @param accessDenied   권한 부족(403) 응답 생성기
     * @return 구성된 필터 체인
     * @throws Exception {@link HttpSecurity} 빌더가 던지는 설정 오류. 기동 시점에만 발생한다
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthenticationFilter jwtFilter,
                                                   CustomAuthenticationEntryPoint entryPoint,
                                                   CustomAccessDeniedHandler accessDenied) throws Exception {
        return http
                // 토큰 기반 stateless API 라 CSRF 토큰을 주고받을 세션이 없다.
                // Security 7 은 이것을 끄지 않으면 모든 상태 변경 요청을 403 으로 막는다.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDenied))
                // 인증 정보를 SecurityContext 에 채우는 일은 인가 판단보다 먼저 끝나야 한다.
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    /**
     * 보안 필터를 아예 타지 않는 경로.
     *
     * <p>{@code permitAll()} 과 다르다. {@code permitAll} 은 필터 체인을 통과시키되 허용하는
     * 것이고, {@code ignoring} 은 <b>체인 자체를 건너뛴다</b>. 정적 문서와 개발용 콘솔은
     * 인증 로직이 개입할 이유가 없어 후자를 쓴다. H2 콘솔이 iframe 을 쓰는데
     * {@code X-Frame-Options} 조정이 필요 없는 것도 이 때문이다.
     *
     * <p>실서비스라면 {@code /h2-console} 은 물론이고 문서 경로도 이렇게 열어두면 안 된다.
     *
     * @return 무시할 경로 설정
     */
    @Bean
    public WebSecurityCustomizer webSecurityCustomizer() {
        return web -> web.ignoring()
                .requestMatchers(PathRequest.toStaticResources().atCommonLocations())
                .requestMatchers(
                        "/docs/**",           // RestDocs 정본 — Redoc / Swagger UI
                        "/swagger-ui/**",     // springdoc 보조
                        "/swagger-ui.html",
                        "/v3/api-docs/**",
                        "/h2-console/**");    // 개발용 DB 콘솔
    }
}
