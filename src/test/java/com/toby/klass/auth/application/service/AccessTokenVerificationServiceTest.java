package com.toby.klass.auth.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.toby.klass.auth.application.port.out.RevokedAccessTokenQueryPort;
import com.toby.klass.auth.application.port.out.TokenParserPort;
import com.toby.klass.auth.application.port.out.dto.TokenClaims;
import com.toby.klass.auth.domain.TokenType;
import com.toby.klass.auth.domain.error.AuthError;
import com.toby.klass.common.domain.error.BusinessException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link AccessTokenVerificationService} 검증.
 *
 * <p>이 클래스는 보호된 API 요청마다 도는 관문이다. 여기서 규칙이 하나라도 빠지면
 * 인증 전체가 뚫리므로, <b>순서</b>까지 고정해 둔다.
 *
 * <p>Design Ref: §8.3 L2 단위 테스트, §2.2 인증된 요청 흐름
 */
@ExtendWith(MockitoExtension.class)
class AccessTokenVerificationServiceTest {

    private static final String TOKEN = "access.token.value";
    private static final String JTI = "11111111-2222-3333-4444-555555555555";

    @Mock private TokenParserPort tokenParserPort;
    @Mock private RevokedAccessTokenQueryPort revokedAccessTokenQueryPort;

    @InjectMocks private AccessTokenVerificationService service;

    private TokenClaims claims() {
        return new TokenClaims(JTI, 7L, "chals", List.of("ROLE_USER"),
                TokenType.ACCESS, Instant.parse("2026-08-30T12:00:00Z"),
                Instant.parse("2026-08-30T12:30:00Z"));
    }

    @Test
    @DisplayName("폐기되지 않은 토큰은 클레임을 그대로 돌려준다")
    void returnsClaimsWhenNotRevoked() {
        given(tokenParserPort.parse(TOKEN, TokenType.ACCESS)).willReturn(claims());
        given(revokedAccessTokenQueryPort.isRevoked(JTI)).willReturn(false);

        TokenClaims verified = service.verify(TOKEN);

        assertThat(verified.jti()).isEqualTo(JTI);
        assertThat(verified.userId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("ACCESS 를 기대 타입으로 넘긴다 — Refresh 토큰으로는 접근할 수 없다")
    void expectsAccessType() {
        given(tokenParserPort.parse(TOKEN, TokenType.ACCESS)).willReturn(claims());
        given(revokedAccessTokenQueryPort.isRevoked(JTI)).willReturn(false);

        service.verify(TOKEN);

        // REFRESH 를 넘기거나 타입 검사를 생략하면 유효기간이 훨씬 긴
        // Refresh 토큰을 Access 처럼 쓸 수 있다.
        verify(tokenParserPort).parse(TOKEN, TokenType.ACCESS);
    }

    @Test
    @DisplayName("폐기된 토큰은 TOKEN_REVOKED 로 거부된다")
    void rejectsRevokedToken() {
        given(tokenParserPort.parse(TOKEN, TokenType.ACCESS)).willReturn(claims());
        given(revokedAccessTokenQueryPort.isRevoked(JTI)).willReturn(true);

        assertThatThrownBy(() -> service.verify(TOKEN))
                .isInstanceOf(BusinessException.class)
                .extracting(t -> ((BusinessException) t).errorCode())
                .isEqualTo(AuthError.TOKEN_REVOKED);
    }

    @Test
    @DisplayName("파싱이 실패하면 폐기 조회를 하지 않는다 — 위조 토큰에 DB 를 낭비하지 않는다")
    void doesNotQueryWhenParsingFails() {
        given(tokenParserPort.parse(TOKEN, TokenType.ACCESS))
                .willThrow(AuthError.TOKEN_INVALID.toException());

        assertThatThrownBy(() -> service.verify(TOKEN))
                .isInstanceOf(BusinessException.class)
                .extracting(t -> ((BusinessException) t).errorCode())
                .isEqualTo(AuthError.TOKEN_INVALID);

        // 순서가 뒤바뀌면 서명조차 맞지 않는 토큰을 대량으로 던져 DB 를 부하 줄 수 있다.
        verify(revokedAccessTokenQueryPort, never()).isRevoked(JTI);
    }
}
