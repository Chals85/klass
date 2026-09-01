package com.toby.klass.auth.application.port.out;

/**
 * 비밀번호 해싱 능력.
 * 구현은 {@code adapter.out.security.BcryptPasswordHasherAdapter} 다.
 *
 * <h2>비교(matches) 메서드가 없는 이유</h2>
 * <b>비밀번호 비교는 이 포트의 책임이 아니다.</b> 로그인 검증은 Spring Security 의
 * {@code DaoAuthenticationProvider} 가 {@code PasswordEncoder.matches()} 로 수행하며,
 * 그 경로는 {@code CredentialsVerifierPort} 뒤에 있다.
 *
 * <p>따라서 이 포트에 남는 책임은 <b>새 비밀번호를 저장 가능한 형태로 바꾸는 것</b> 하나다.
 * 현재 유일한 사용처는 {@code DefaultUserInitializer} — 기본 계정을 심을 때 설정의 평문을
 * 해싱한다. 회원가입 기능이 생기면 그쪽에서도 쓰게 된다.
 *
 * <p>Design Ref: §2.4 Port Signatures, §13.10 AuthenticationProvider 도입
 */
public interface PasswordHasherPort {

    /**
     * 평문 비밀번호를 저장용 해시로 바꾼다.
     *
     * @return BCrypt 해시. 솔트가 섞이므로 호출할 때마다 결과가 다르다 —
     *         <b>문자열 비교로 검증할 수 없다</b>
     */
    String hash(String rawPassword);
}
