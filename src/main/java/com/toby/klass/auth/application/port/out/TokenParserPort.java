package com.toby.klass.auth.application.port.out;

import com.toby.klass.auth.application.port.out.dto.TokenClaims;
import com.toby.klass.auth.domain.TokenType;

/**
 * JWT 검증·파싱 능력. 구현은 {@code adapter.out.token.NimbusJwtAdapter} 다.
 *
 * <p>Design Ref: §2.3 의존성
 */
public interface TokenParserPort {

    /**
     * 토큰을 검증하고 클레임을 꺼낸다.
     *
     * <p>서명·만료·타입을 <b>모두</b> 이 메서드에서 검사한다. 호출자가 나눠서 검사하면
     * 어느 한 곳에서 빠뜨리기 쉽다. 특히 {@code expected} 를 필수 인자로 둔 것은
     * 타입 검사를 잊을 수 없게 만들기 위함이다 — Refresh 토큰이 Access 토큰 자리에
     * 쓰이는 것을 막는다.
     *
     * @param token    {@code Bearer } 접두어가 제거된 JWT 문자열
     * @return 검증을 통과한 클레임
     * @throws com.toby.klass.common.domain.error.BusinessException
     *         {@code TOKEN_EXPIRED} 만료 /
     *         {@code TOKEN_INVALID} 서명 위조·형식 오류 /
     *         {@code TOKEN_TYPE_MISMATCH} {@code typ} 이 {@code expected} 와 다름
     */
    TokenClaims parse(String token, TokenType expected);
}
