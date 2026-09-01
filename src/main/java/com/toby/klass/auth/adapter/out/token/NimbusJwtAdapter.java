package com.toby.klass.auth.adapter.out.token;

import com.toby.klass.auth.application.port.out.TokenGeneratorPort;
import com.toby.klass.auth.application.port.out.TokenParserPort;
import com.toby.klass.auth.application.port.out.dto.GeneratedToken;
import com.toby.klass.auth.application.port.out.dto.TokenClaims;
import com.toby.klass.auth.domain.TokenType;
import com.toby.klass.auth.domain.error.AuthError;
import com.toby.klass.infrastructure.security.jwt.JwtProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

/**
 * Nimbus 기반 JWT 발급·검증 어댑터.
 *
 * <p><b>이 프로젝트에서 JWT 라이브러리를 아는 유일한 클래스다.</b> 애플리케이션 계층은
 * {@link TokenGeneratorPort}/{@link TokenParserPort} 만 보므로, 이 클래스를 교체하면
 * jjwt 든 직접 구현이든 갈아끼울 수 있다. 헥사고날의 실익이 드러나는 지점이다.
 *
 * <p>두 포트를 한 클래스가 구현한다. 발급과 검증이 <b>같은 클레임 규약</b>을 공유하므로
 * (클레임 이름 상수, 타입 표기) 나누면 오히려 규약이 두 곳으로 흩어진다.
 *
 * <p>Design Ref: §2.1 Component Diagram, §2.4 Port Signatures, §3.4 JWT 클레임 구조
 */
@Component
public class NimbusJwtAdapter implements TokenGeneratorPort, TokenParserPort {

    /** 토큰 종류를 담는 커스텀 클레임. 타입 혼동 공격을 막는 핵심 값이다. */
    private static final String CLAIM_TYPE = "typ";

    /** 사용자 표시 이름. Access 토큰에만 실린다. */
    private static final String CLAIM_USERNAME = "username";

    /** 권한 목록. Access 토큰에만 실린다. */
    private static final String CLAIM_ROLES = "roles";

    private final JwtEncoder jwtEncoder;
    private final JwtDecoder jwtDecoder;
    private final JwtProperties properties;
    private final Clock clock;

    /**
     * JWT 인코더·디코더와 설정, 시계를 주입받는다.
     */
    public NimbusJwtAdapter(JwtEncoder jwtEncoder, JwtDecoder jwtDecoder,
                            JwtProperties properties, Clock clock) {
        this.jwtEncoder = jwtEncoder;
        this.jwtDecoder = jwtDecoder;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public GeneratedToken generateAccessToken(Long userId, String username, List<String> roles) {
        return generate(userId, properties.accessTokenValidity(), TokenType.ACCESS, builder ->
                builder.claim(CLAIM_USERNAME, username)
                        .claim(CLAIM_ROLES, roles));
    }

    @Override
    public GeneratedToken generateRefreshToken(Long userId) {
        // 권한·사용자명을 싣지 않는다. 재발급 시점에 DB 에서 최신 권한을 다시 읽으므로
        // 권한 변경이 즉시 반영되고, 탈취 시 노출되는 정보도 줄어든다.
        return generate(userId, properties.refreshTokenValidity(), TokenType.REFRESH, builder -> builder);
    }

    /**
     * 토큰 발급의 공통 절차.
     *
     * <p>{@code issuedAt}/{@code expiresAt} 을 계산해 서명에 넣고, <b>같은 값</b>을
     * {@link GeneratedToken} 으로 돌려준다. 호출자가 만료를 다시 계산하지 않게 해서
     * JWT 의 {@code exp} 와 DB 의 {@code expires_at} 이 어긋나는 것을 막는다.
     *
     * @param userId       {@code sub} 로 들어갈 사용자 id
     * @param claimEnricher 종류별 추가 클레임을 붙이는 함수
     * @return 서명된 토큰과 발급·만료 시각
     */
    private GeneratedToken generate(Long userId, Duration validity, TokenType type,
                                    java.util.function.UnaryOperator<JwtClaimsSet.Builder> claimEnricher) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(validity);

        JwtClaimsSet.Builder builder = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(String.valueOf(userId))
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                // jti — 토큰 하나를 유일하게 가리키는 값. 로그 추적용이자
                // 로그아웃 시 폐기 목록(RevokedAccessToken)에 올릴 키다.
                .id(UUID.randomUUID().toString())
                .claim(CLAIM_TYPE, type.name());

        JwtClaimsSet claims = claimEnricher.apply(builder).build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();

        String value = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new GeneratedToken(value, issuedAt, expiresAt);
    }

    /**
     * {@inheritDoc}
     *
     * <p>검증 순서가 곧 에러 코드의 우선순위다.
     * <ol>
     *   <li>서명·형식 — 디코더가 담당. 실패하면 {@code TOKEN_INVALID}</li>
     *   <li>만료 — 주입된 {@code Clock} 기준. 실패하면 {@code TOKEN_EXPIRED}</li>
     *   <li>{@code jti} 존재 — 없으면 우리가 발급한 토큰이 아니다.
     *       실패하면 {@code TOKEN_INVALID}</li>
     *   <li>토큰 종류 — {@code typ} 클레임과 {@code expected} 대조.
     *       실패하면 {@code TOKEN_TYPE_MISMATCH}</li>
     * </ol>
     * <b>만료를 타입보다 먼저</b> 보는 이유는, 만료된 토큰의 종류를 알려줄 필요가 없어서다.
     * <b>{@code jti} 를 타입보다 먼저</b> 보는 이유는, {@code jti} 가 없다는 것이 "종류가
     * 틀렸다"보다 근본적인 문제이기 때문이다 — 우리가 발급한 토큰이 아니라는 뜻이다.
     */
    @Override
    public TokenClaims parse(String token, TokenType expected) {
        Jwt jwt = decode(token);

        Instant expiresAt = jwt.getExpiresAt();
        if (expiresAt == null || expiresAt.isBefore(clock.instant())) {
            throw AuthError.TOKEN_EXPIRED.toException();
        }

        String jti = readJti(jwt);

        TokenType actual = readTokenType(jwt);
        if (actual != expected) {
            throw AuthError.TOKEN_TYPE_MISMATCH.toException();
        }

        return new TokenClaims(
                jti,
                parseUserId(jwt),
                jwt.getClaimAsString(CLAIM_USERNAME),
                readRoles(jwt),
                actual,
                jwt.getIssuedAt(),
                expiresAt);
    }

    /**
     * 서명과 형식을 검증한다.
     *
     * @throws com.toby.klass.common.domain.error.BusinessException
     *         {@link AuthError#TOKEN_INVALID} 서명 위조·형식 오류·알고리즘 불일치
     */
    private Jwt decode(String token) {
        try {
            return jwtDecoder.decode(token);
        } catch (JwtException e) {
            // 원인은 로그에만 남긴다. 응답에 노출하면 공격자에게 검증 로직을 알려주는 셈이다.
            throw AuthError.TOKEN_INVALID.toException();
        }
    }

    /**
     * {@code jti} 클레임을 꺼낸다.
     *
     * <p>우리가 발급한 토큰은 항상 {@code jti} 를 갖는다. 없다면 서명이 유효하더라도
     * 우리가 만든 토큰이 아니라는 뜻이므로 무효 처리한다. 여기서 {@code null} 을
     * 통과시키면 <b>폐기 대조를 건너뛰는 토큰</b>이 생겨 로그아웃이 무력화된다.
     *
     * @throws com.toby.klass.common.domain.error.BusinessException
     *         {@link AuthError#TOKEN_INVALID} {@code jti} 가 없는 경우
     */
    private String readJti(Jwt jwt) {
        String jti = jwt.getId();
        if (jti == null || jti.isBlank()) {
            throw AuthError.TOKEN_INVALID.toException();
        }
        return jti;
    }

    /**
     * {@code typ} 클레임을 {@link TokenType} 으로 변환한다.
     *
     * <p>클레임이 없거나 알 수 없는 값이면 타입 불일치로 간주한다. 서명은 유효하지만
     * 우리가 발급하지 않은 형태이므로 통과시켜서는 안 된다.
     */
    private TokenType readTokenType(Jwt jwt) {
        String raw = jwt.getClaimAsString(CLAIM_TYPE);
        if (raw == null) {
            throw AuthError.TOKEN_TYPE_MISMATCH.toException();
        }
        try {
            return TokenType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw AuthError.TOKEN_TYPE_MISMATCH.toException();
        }
    }

    /**
     * {@code sub} 를 사용자 id 로 변환한다.
     *
     * <p>JWT 의 {@code sub} 는 문자열이므로 숫자가 아닌 값이 들어올 수 있다.
     * 서명이 유효하더라도 우리가 만든 토큰이 아니라는 뜻이므로 무효 처리한다.
     */
    private Long parseUserId(Jwt jwt) {
        try {
            return Long.valueOf(jwt.getSubject());
        } catch (NumberFormatException | NullPointerException e) {
            throw AuthError.TOKEN_INVALID.toException();
        }
    }

    /** Refresh 토큰에는 권한 클레임이 없으므로 빈 목록을 돌려준다. */
    private List<String> readRoles(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList(CLAIM_ROLES);
        return roles == null ? List.of() : roles;
    }
}
