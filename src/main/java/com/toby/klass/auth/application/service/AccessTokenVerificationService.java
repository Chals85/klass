package com.toby.klass.auth.application.service;

import com.toby.klass.auth.application.port.in.VerifyAccessTokenUseCase;
import com.toby.klass.auth.application.port.out.RevokedAccessTokenQueryPort;
import com.toby.klass.auth.application.port.out.TokenParserPort;
import com.toby.klass.auth.application.port.out.dto.TokenClaims;
import com.toby.klass.auth.domain.TokenType;
import com.toby.klass.auth.domain.error.AuthError;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Access 토큰 검증 구현.
 *
 * <h2>왜 {@code AuthService} 에 넣지 않았는가</h2>
 * 트랜잭션 성격이 다르다. {@code AuthService} 는 클래스 레벨에 쓰기 트랜잭션이 걸려 있는데,
 * 토큰 검증은 <b>보호된 API 요청마다</b> 도는 읽기 전용 경로다. 매 요청 쓰기 트랜잭션을
 * 여는 것은 낭비이므로 {@code readOnly = true} 로 분리했다.
 *
 * <p>책임도 다르다. {@code AuthService} 는 토큰을 <b>발급·폐기</b>하고, 이 클래스는
 * 발급된 토큰을 <b>확인</b>한다. 한쪽은 로그인 흐름, 다른 쪽은 모든 요청의 공통 경로다.
 *
 * <h2>검증 순서</h2>
 * 파싱이 먼저다. 폐기 조회는 DB 를 때리므로, <b>서명조차 맞지 않는 토큰에 쿼리를
 * 낭비할 이유가 없다</b>. 서명이 유효한 토큰만 조회 단계로 넘어가므로 위조 토큰을
 * 대량으로 던지는 방식으로 DB 를 부하 줄 수 없다.
 *
 * <p>Design Ref: §2.2 인증된 요청 흐름
 */
@Service
@Transactional(readOnly = true)
public class AccessTokenVerificationService implements VerifyAccessTokenUseCase {

    private final TokenParserPort tokenParserPort;
    private final RevokedAccessTokenQueryPort revokedAccessTokenQueryPort;

    /**
     * 검증에 필요한 포트들을 주입받는다.
     *
     * @param tokenParserPort             토큰 검증·파싱 포트
     * @param revokedAccessTokenQueryPort 폐기 목록 조회 포트
     */
    public AccessTokenVerificationService(TokenParserPort tokenParserPort,
                                          RevokedAccessTokenQueryPort revokedAccessTokenQueryPort) {
        this.tokenParserPort = tokenParserPort;
        this.revokedAccessTokenQueryPort = revokedAccessTokenQueryPort;
    }

    @Override
    public TokenClaims verify(String accessToken) {
        // ACCESS 를 기대 타입으로 넘긴다. Refresh 토큰으로는 보호된 API 에 접근할 수 없다.
        TokenClaims claims = tokenParserPort.parse(accessToken, TokenType.ACCESS);

        if (revokedAccessTokenQueryPort.isRevoked(claims.jti())) {
            throw AuthError.TOKEN_REVOKED.toException();
        }

        return claims;
    }
}
