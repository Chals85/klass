package com.toby.klass.auth.application.port.out;

/**
 * Refresh 토큰의 저장·조회 키를 만드는 해싱 능력.
 * 구현은 {@code adapter.out.security.Sha256TokenHasherAdapter} 다.
 *
 * <h2>왜 {@code PasswordHasherPort} 와 분리했는가</h2>
 * 비밀번호는 BCrypt 로 해싱한다. BCrypt 는 의도적으로 느리고 <b>매번 다른 값</b>을
 * 만들기 때문에(솔트가 섞인다) 조회 키로 쓸 수 없다. Refresh 토큰은 해시로 DB 를
 * 조회해야 하므로 결정적(deterministic)이면서 빠른 SHA-256 이 필요하다.
 * 용도가 다르므로 포트도 분리한다.
 *
 * <p>토큰 자체가 이미 충분한 엔트로피를 가진 무작위 값이라 솔트 없는 SHA-256 으로도
 * 무차별 대입이 현실적이지 않다. 비밀번호처럼 사람이 만든 저엔트로피 값이 아니다.
 *
 * <p>Design Ref: §2.4 Port Signatures, §7 Security Considerations
 */
public interface TokenHasherPort {

    /**
     * 토큰 원문을 저장용 해시로 바꾼다.
     *
     * @param rawToken 토큰 원문(JWT 문자열)
     * @return 소문자 16진수 64자. 같은 입력에 항상 같은 출력이다
     */
    String sha256Hex(String rawToken);
}
