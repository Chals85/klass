package com.toby.klass.infrastructure.security.config;

import com.toby.klass.auth.adapter.out.security.DomainAuthenticationProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 아이디·비밀번호 인증 파이프라인 구성.
 *
 * <pre>
 *   AuthenticationManager (ProviderManager)
 *     └─ DomainAuthenticationProvider          ← 비밀번호 비교를 직접 구현
 *          ├─ DomainUserDetailsService  → UserQueryPort → H2
 *          └─ PasswordEncoder           → BCrypt
 * </pre>
 *
 * <p>이 파이프라인은 {@code SpringSecurityCredentialsAdapter} 를 통해서만 호출된다.
 * 애플리케이션 계층은 {@code CredentialsVerifierPort} 만 알고 그 뒤를 모른다.
 *
 * <p>{@code ProviderManager} 는 여러 Provider 를 순회하도록 만들어져 있다. LDAP·API Key 등
 * 다른 인증 방식을 추가할 때 Provider 를 하나 더 넘기면 되고, 서비스 코드는 그대로다.
 *
 * <p>Design Ref: §2.2 로그인 흐름, §7 Security Considerations
 */
@Configuration
public class AuthenticationConfig {

    /** Spring 이 인스턴스를 만든다. 직접 호출하지 않는다. */
    public AuthenticationConfig() {
    }

    /**
     * 비밀번호 해싱 알고리즘.
     *
     * <p>빈으로 노출하는 이유는 {@link DomainAuthenticationProvider}(비교)와
     * {@code BcryptPasswordHasherAdapter}(시딩)가 <b>같은 인스턴스</b>를 써야 하기
     * 때문이다. 강도 설정이 어긋나면 시딩한 계정으로 로그인이 되지 않는다.
     *
     * <p><b>애플리케이션 계층에서 직접 주입받아 쓰지 말 것</b> — 어댑터·설정 계층 전용이다.
     *
     * @return BCrypt 인코더. 강도는 Spring 기본값(10)
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 인증 관리자.
     *
     * <p>검사 순서 조정(계정 상태를 비밀번호 검증 뒤로)은 {@link DomainAuthenticationProvider}
     * 의 생성자에 있다. 그 이유도 거기에 적어 두었다.
     *
     * @param provider 도메인 인증 제공자
     * @return 구성된 인증 관리자
     */
    @Bean
    public AuthenticationManager authenticationManager(DomainAuthenticationProvider provider) {
        return new ProviderManager(provider);
    }
}
