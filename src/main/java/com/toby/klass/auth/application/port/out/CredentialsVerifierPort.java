package com.toby.klass.auth.application.port.out;

import com.toby.klass.auth.application.port.out.dto.VerifiedCredentials;

/**
 * 아이디·비밀번호 검증 능력.
 * 구현은 {@code adapter.out.security.SpringSecurityCredentialsAdapter} 다.
 *
 * <h2>왜 포트로 감싸는가</h2>
 * 구현은 Spring Security 의 표준 인증 파이프라인
 * ({@code AuthenticationManager} → {@code DaoAuthenticationProvider} →
 * {@code UserDetailsService})을 그대로 쓴다. 그것이 정석이고, 인증 방식을 추가하거나
 * 계정 잠금·인증 이벤트 같은 기존 인프라를 붙일 때 이득이 크기 때문이다.
 *
 * <p>다만 {@code AuthenticationManager} 를 서비스에 직접 주입하면 애플리케이션 계층에
 * {@code org.springframework.security} 가 들어온다. 이 프로젝트가 지키는 §9.3 규칙에
 * 어긋나므로 포트 뒤에 둔다. {@code TokenGeneratorPort} 가 JWT 라이브러리를 숨기는 것과
 * 같은 구조다 — 표준 구현을 쓰되 그 사실이 애플리케이션 계층으로 새지 않게 한다.
 *
 * <p>Design Ref: §2.3 의존성, §9.3 File Import Rules
 */
public interface CredentialsVerifierPort {

    /**
     * 아이디·비밀번호를 검증하고 사용자 신원을 돌려준다.
     *
     * <p>구현은 <b>검증 순서</b>를 지켜야 한다. 계정 활성 여부는 비밀번호 검증 <b>이후에</b>
     * 확인해야 하며, 그러지 않으면 비밀번호를 몰라도 계정의 존재·상태를 알아낼 수 있다.
     *
     * @throws com.toby.klass.common.domain.error.BusinessException
     *         {@code INVALID_CREDENTIALS} 아이디가 없거나 비밀번호가 틀림 —
     *         <b>두 경우를 구분하지 않는다</b> /
     *         {@code USER_DISABLED} 비활성 계정 (비밀번호는 맞은 경우)
     */
    VerifiedCredentials verify(String username, String rawPassword);
}
