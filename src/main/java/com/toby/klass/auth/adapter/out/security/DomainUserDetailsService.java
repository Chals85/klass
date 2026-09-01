package com.toby.klass.auth.adapter.out.security;

import com.toby.klass.user.application.port.out.UserQueryPort;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * 도메인 사용자를 Spring Security 인증 파이프라인에 공급한다.
 *
 * <p>{@code DaoAuthenticationProvider} 가 이 서비스로 사용자를 찾고, 돌려받은
 * {@link SecurityUserDetails} 의 비밀번호 해시를 입력값과 비교한다.
 *
 * <p>DB 접근은 직접 하지 않고 {@link UserQueryPort} 에 위임한다. 어댑터가 다른 컨텍스트의
 * 포트를 쓰는 것은 허용된다 — 금지되는 것은 도메인끼리의 교차다(§9.3).
 *
 * <p>Design Ref: §2.2 로그인 흐름
 */
@Service
public class DomainUserDetailsService implements UserDetailsService {

    private final UserQueryPort userQueryPort;

    /**
     * 사용자 조회 포트를 주입받는다. DB 접근은 직접 하지 않는다.
     */
    public DomainUserDetailsService(UserQueryPort userQueryPort) {
        this.userQueryPort = userQueryPort;
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@link UsernameNotFoundException} 을 던지지만 이것이 그대로 응답에 노출되지는 않는다.
     * {@code DaoAuthenticationProvider} 의 {@code hideUserNotFoundExceptions} 기본값이
     * {@code true} 라 {@code BadCredentialsException} 으로 바뀌고, 어댑터가 그것을 다시
     * {@code INVALID_CREDENTIALS} 로 변환한다. 결과적으로 "아이디 없음"과 "비밀번호 틀림"이
     * 구분되지 않는다.
     */
    @Override
    public UserDetails loadUserByUsername(String username) {
        return userQueryPort.findByUsername(username)
                .map(SecurityUserDetails::from)
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다: " + username));
    }
}
