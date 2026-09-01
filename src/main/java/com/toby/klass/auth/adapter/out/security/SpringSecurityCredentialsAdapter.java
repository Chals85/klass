package com.toby.klass.auth.adapter.out.security;

import com.toby.klass.auth.application.port.out.CredentialsVerifierPort;
import com.toby.klass.auth.application.port.out.dto.VerifiedCredentials;
import com.toby.klass.auth.domain.error.AuthError;
import com.toby.klass.user.domain.error.UserError;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;

/**
 * Spring Security 인증 파이프라인을 {@link CredentialsVerifierPort} 뒤에 감춘다.
 *
 * <p><b>이 클래스와 {@code AuthenticationConfig} 만이 Security 의 인증 API 를 안다.</b>
 * 애플리케이션 계층은 포트만 보므로, 인증 방식을 바꿔도({@code LdapAuthenticationProvider}
 * 추가 등) 서비스 코드는 그대로다.
 *
 * <h2>예외 번역이 이 어댑터의 핵심 책임이다</h2>
 * Security 의 {@code AuthenticationException} 계층을 이 프로젝트의 {@code ErrorCode} 로
 * 옮긴다. 번역하지 않고 그대로 두면 애플리케이션 계층이 Security 예외를 잡아야 하고,
 * 그러면 포트로 감춘 의미가 없어진다.
 *
 * <p>Design Ref: §2.2 로그인 흐름, §6.1 Error Code Definition
 */
@Component
public class SpringSecurityCredentialsAdapter implements CredentialsVerifierPort {

    private final AuthenticationManager authenticationManager;

    /**
     * Spring Security 인증 관리자를 주입받는다.
     */
    public SpringSecurityCredentialsAdapter(AuthenticationManager authenticationManager) {
        this.authenticationManager = authenticationManager;
    }

    /**
     * {@inheritDoc}
     *
     * <p>검사 순서는 {@code AuthenticationConfig} 가 보장한다 — 비밀번호를 먼저 확인하고
     * 계정 상태는 그 뒤에 본다. 따라서 여기서 {@code DisabledException} 을 받았다는 것은
     * <b>비밀번호가 맞았다</b>는 뜻이므로, 계정 상태를 알려줘도 정보가 새지 않는다.
     */
    @Override
    public VerifiedCredentials verify(String username, String rawPassword) {
        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(username, rawPassword));
            return toVerifiedCredentials(authentication);

        } catch (DisabledException | LockedException e) {
            // 비밀번호 검증을 통과한 뒤에야 도달하는 지점이다(AuthenticationConfig 참조).
            throw UserError.USER_DISABLED.toException();

        } catch (AuthenticationException e) {
            // 아이디 없음·비밀번호 불일치를 구분하지 않는다. 구분하면 사용자 열거가 가능해진다.
            // hideUserNotFoundExceptions 기본값 덕분에 전자도 BadCredentialsException 으로 온다.
            throw AuthError.INVALID_CREDENTIALS.toException();
        }
    }

    /**
     * 인증 결과에서 토큰 발급에 필요한 값만 꺼낸다.
     *
     * @throws com.toby.klass.common.domain.error.BusinessException principal 이 예상 타입이
     *         아닌 경우. {@code DomainUserDetailsService} 가 항상 {@link SecurityUserDetails} 를
     *         돌려주므로 정상 경로에서는 발생하지 않는다
     */
    private VerifiedCredentials toVerifiedCredentials(Authentication authentication) {
        if (!(authentication.getPrincipal() instanceof SecurityUserDetails principal)) {
            throw AuthError.INVALID_CREDENTIALS.toException();
        }
        return new VerifiedCredentials(
                principal.userId(), principal.getUsername(), principal.roleNames());
    }
}
