package com.toby.klass.infrastructure.security.filter;

import com.toby.klass.auth.application.port.in.VerifyAccessTokenUseCase;
import com.toby.klass.auth.application.port.out.dto.TokenClaims;
import com.toby.klass.common.domain.error.BusinessException;
import com.toby.klass.infrastructure.security.principal.AuthenticatedUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authorization 헤더의 Access 토큰을 검증해 SecurityContext 를 채운다.
 *
 * <h2>이 필터는 요청을 직접 거부하지 않는다</h2>
 * 토큰이 없거나 잘못됐어도 {@code 401} 을 바로 쓰지 않고 <b>체인을 계속 진행</b>한다.
 * 인증 없이 접근 가능한 경로({@code /v1/auth/login} 등)가 있기 때문이다. 보호된 자원이면
 * 뒤쪽의 {@code AuthorizationFilter} 가 막고, 그때 {@code CustomAuthenticationEntryPoint}
 * 가 응답을 만든다.
 *
 * <p>실패 원인은 {@link #AUTH_ERROR_ATTRIBUTE} 로 request 에 실어 보낸다. EntryPoint 는
 * {@code AuthenticationException} 만 받을 뿐 우리 에러 코드를 알 수 없어서, 이 통로로
 * "왜 실패했는지"를 전달해야 {@code TOKEN_EXPIRED}/{@code TOKEN_TYPE_MISMATCH} 를 구분해
 * 응답할 수 있다.
 *
 * <h2>검증 자체는 하지 않는다</h2>
 * 이 필터가 아는 것은 "Authorization 헤더에서 토큰을 꺼내 유즈케이스에 넘기고,
 * 결과를 SecurityContext 에 담는다"까지다. 무엇이 유효한 토큰인지는
 * {@link VerifyAccessTokenUseCase} 가 정한다 — 서명·만료·타입에 더해 로그아웃으로
 * 폐기됐는지까지 확인한다. 그 규칙을 여기에 인라인하면 인증 로직의 절반이
 * infrastructure 로 새어나간다.
 *
 * <p>Design Ref: §2.2 인증된 요청 흐름, §6.3 예외 처리 경로가 둘인 점
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /**
     * 토큰 검증 실패 시 {@code ErrorCode} 를 담아 두는 request 속성 키.
     * {@code CustomAuthenticationEntryPoint} 가 읽는다.
     */
    public static final String AUTH_ERROR_ATTRIBUTE = "jwtAuthenticationError";

    private static final String BEARER_PREFIX = "Bearer ";

    private final VerifyAccessTokenUseCase verifyAccessTokenUseCase;

    /**
     * 토큰 검증 유즈케이스를 주입받는다.
     */
    public JwtAuthenticationFilter(VerifyAccessTokenUseCase verifyAccessTokenUseCase) {
        this.verifyAccessTokenUseCase = verifyAccessTokenUseCase;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String token = extractBearerToken(request);
        if (token == null) {
            // 헤더가 없으면 인증을 시도하지 않는다. attribute 도 남기지 않으므로
            // 보호된 자원이면 EntryPoint 가 기본값 UNAUTHENTICATED 로 응답한다.
            filterChain.doFilter(request, response);
            return;
        }

        try {
            TokenClaims claims = verifyAccessTokenUseCase.verify(token);
            SecurityContextHolder.getContext().setAuthentication(toAuthentication(claims));
        } catch (BusinessException e) {
            // 검증 실패 시 컨텍스트를 비운다. 이전 요청의 인증이 남아 있을 가능성은 없지만
            // (요청마다 새 컨텍스트) 방어적으로 정리한다.
            SecurityContextHolder.clearContext();
            request.setAttribute(AUTH_ERROR_ATTRIBUTE, e.errorCode());
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Authorization 헤더에서 토큰만 꺼낸다.
     *
     * @return {@code Bearer } 접두어를 제거한 토큰. 헤더가 없거나 형식이 다르면 {@code null}
     */
    private String extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    /**
     * 검증된 클레임을 Security 의 인증 객체로 바꾼다.
     *
     * <p>credentials 자리에 {@code null} 을 넣는다. 토큰 문자열을 그대로 두면 로그나
     * 디버거에 노출될 수 있고, 인증이 끝난 시점에는 더 이상 필요하지 않다.
     */
    private UsernamePasswordAuthenticationToken toAuthentication(TokenClaims claims) {
        AuthenticatedUser principal = new AuthenticatedUser(
                claims.userId(), claims.username(), claims.roles(),
                claims.jti(), claims.expiresAt());
        List<GrantedAuthority> authorities = claims.roles().stream()
                .map(SimpleGrantedAuthority::new)
                .map(GrantedAuthority.class::cast)
                .toList();
        return new UsernamePasswordAuthenticationToken(principal, null, authorities);
    }
}
