package com.toby.klass.auth.adapter.out.security;

import com.toby.klass.auth.application.port.out.PasswordHasherPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * BCrypt 기반 비밀번호 해싱 어댑터.
 *
 * <p>비밀번호 <b>비교</b>는 여기서 하지 않는다. 로그인 검증은 Spring Security 의
 * {@code DaoAuthenticationProvider} 가 담당하며 {@code CredentialsVerifierPort} 뒤에 있다.
 * 이 어댑터의 책임은 새 비밀번호를 저장 가능한 형태로 바꾸는 것 하나다.
 *
 * <p>{@link PasswordEncoder} 를 직접 만들지 않고 주입받는다. 인증 파이프라인이 쓰는 것과
 * <b>같은 인스턴스</b>여야 하기 때문이다. 강도 설정이 어긋나면 시딩한 계정으로 로그인이
 * 되지 않는 상황이 생긴다.
 *
 * <p>Design Ref: §2.4 Port Signatures, §13.10 AuthenticationProvider 도입
 */
@Component
public class BcryptPasswordHasherAdapter implements PasswordHasherPort {

    private final PasswordEncoder passwordEncoder;

    /**
     * 인증 파이프라인과 같은 인코더 인스턴스를 주입받는다.
     *
     * @param passwordEncoder BCrypt 인코더
     */
    public BcryptPasswordHasherAdapter(PasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public String hash(String rawPassword) {
        return passwordEncoder.encode(rawPassword);
    }
}
