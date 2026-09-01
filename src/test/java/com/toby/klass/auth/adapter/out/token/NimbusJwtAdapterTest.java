package com.toby.klass.auth.adapter.out.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toby.klass.auth.application.port.out.dto.GeneratedToken;
import com.toby.klass.auth.application.port.out.dto.TokenClaims;
import com.toby.klass.auth.domain.TokenType;
import com.toby.klass.auth.domain.error.AuthError;
import com.toby.klass.common.domain.error.BusinessException;
import com.toby.klass.common.domain.error.ErrorCode;
import com.toby.klass.infrastructure.security.jwt.JwtKeyConfig;
import com.toby.klass.infrastructure.security.jwt.JwtProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

/**
 * {@link NimbusJwtAdapter} 발급·검증 검증.
 *
 * <p>Spring 컨텍스트 없이 순수 단위 테스트로 돌린다. 어댑터가 주입받는 것이
 * 인코더·디코더·설정·{@link Clock} 네 개뿐이라 직접 조립할 수 있고, {@code Clock} 을
 * 고정하면 만료 상황을 시간 대기 없이 재현할 수 있다.
 *
 * <p>Design Ref: §8.3 L2 단위 테스트 #7~#10
 */
class NimbusJwtAdapterTest {

    private static final Instant NOW = Instant.parse("2026-08-30T12:00:00Z");
    private static final Long USER_ID = 42L;
    private static final String USERNAME = "chals";
    private static final List<String> ROLES = List.of("ROLE_USER");

    private static final JwtProperties PROPERTIES = new JwtProperties(
            "sample-jwt-authentication",
            Base64.getEncoder().encodeToString("test-secret-key-for-unit-test-32b!".getBytes()),
            Duration.ofMinutes(30),
            Duration.ofDays(14));

    private final JwtKeyConfig keyConfig = new JwtKeyConfig();
    private final SecretKey secretKey = keyConfig.jwtSecretKey(PROPERTIES);
    private final JwtEncoder encoder = keyConfig.jwtEncoder(secretKey);
    private final JwtDecoder decoder = keyConfig.jwtDecoder(secretKey);

    /** 지정한 시각에 고정된 시계를 가진 어댑터. 발급·검증 시점을 다르게 두려고 쓴다. */
    private NimbusJwtAdapter adapterAt(Instant now) {
        return new NimbusJwtAdapter(encoder, decoder, PROPERTIES, Clock.fixed(now, ZoneOffset.UTC));
    }

    /** 예외에서 에러 코드만 꺼낸다. */
    private ErrorCode errorCodeOf(Throwable t) {
        return ((BusinessException) t).errorCode();
    }

    /**
     * 우리 키로 <b>정상 서명</b>하되 {@code jti} 만 빠뜨린 토큰을 만든다.
     *
     * <p>서명이 유효하므로 디코더는 통과한다 — 그 뒤 어느 검사에 걸리는지를 보기 위한 픽스처다.
     */
    private String signWithoutJti(TokenType type) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(PROPERTIES.issuer())
                .subject(String.valueOf(USER_ID))
                .issuedAt(NOW)
                .expiresAt(NOW.plus(Duration.ofMinutes(30)))
                .claim("typ", type.name())
                .build();
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    @Nested
    @DisplayName("Access 토큰")
    class AccessToken {

        @Test
        @DisplayName("발급 후 파싱하면 클레임이 그대로 복원된다")
        void roundTrip() {
            NimbusJwtAdapter adapter = adapterAt(NOW);

            GeneratedToken generated = adapter.generateAccessToken(USER_ID, USERNAME, ROLES);
            TokenClaims claims = adapter.parse(generated.value(), TokenType.ACCESS);

            assertThat(claims.userId()).isEqualTo(USER_ID);
            assertThat(claims.username()).isEqualTo(USERNAME);
            assertThat(claims.roles()).containsExactly("ROLE_USER");
            assertThat(claims.type()).isEqualTo(TokenType.ACCESS);
        }

        @Test
        @DisplayName("jti 가 채워지고 발급할 때마다 달라진다")
        void hasUniqueTokenId() {
            NimbusJwtAdapter adapter = adapterAt(NOW);

            // jti 는 로그아웃 시 폐기 목록의 키다. 같은 값이 재사용되면
            // 한 번의 로그아웃이 다른 토큰까지 막아버린다.
            String first = adapter.parse(
                    adapter.generateAccessToken(USER_ID, USERNAME, ROLES).value(),
                    TokenType.ACCESS).jti();
            String second = adapter.parse(
                    adapter.generateAccessToken(USER_ID, USERNAME, ROLES).value(),
                    TokenType.ACCESS).jti();

            assertThat(first).isNotBlank();
            assertThat(second).isNotBlank();
            assertThat(first).isNotEqualTo(second);
        }

        @Test
        @DisplayName("만료 시각은 설정된 유효 기간과 정확히 일치한다")
        void expiryMatchesConfiguredValidity() {
            GeneratedToken generated = adapterAt(NOW).generateAccessToken(USER_ID, USERNAME, ROLES);

            assertThat(generated.issuedAt()).isEqualTo(NOW);
            assertThat(generated.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(30)));
            // 응답의 accessTokenExpiresIn 으로 나가는 값
            assertThat(generated.expiresInSeconds()).isEqualTo(1800);
        }
    }

    @Nested
    @DisplayName("Refresh 토큰")
    class RefreshToken {

        @Test
        @DisplayName("발급 후 파싱하면 사용자 id 만 복원되고 권한은 비어 있다")
        void roundTripWithoutRoles() {
            NimbusJwtAdapter adapter = adapterAt(NOW);

            GeneratedToken generated = adapter.generateRefreshToken(USER_ID);
            TokenClaims claims = adapter.parse(generated.value(), TokenType.REFRESH);

            assertThat(claims.userId()).isEqualTo(USER_ID);
            assertThat(claims.type()).isEqualTo(TokenType.REFRESH);
            // 권한을 싣지 않는다 — 재발급 시 DB 에서 최신 권한을 다시 읽기 때문
            assertThat(claims.roles()).isEmpty();
            assertThat(claims.username()).isNull();
        }

        @Test
        @DisplayName("만료 시각은 Access 보다 길다")
        void hasLongerValidityThanAccess() {
            NimbusJwtAdapter adapter = adapterAt(NOW);

            GeneratedToken access = adapter.generateAccessToken(USER_ID, USERNAME, ROLES);
            GeneratedToken refresh = adapter.generateRefreshToken(USER_ID);

            assertThat(refresh.expiresAt()).isAfter(access.expiresAt());
            assertThat(refresh.expiresInSeconds()).isEqualTo(Duration.ofDays(14).toSeconds());
        }
    }

    @Nested
    @DisplayName("검증 실패")
    class Failures {

        @Test
        @DisplayName("만료된 토큰은 TOKEN_EXPIRED 로 거부된다")
        void rejectsExpiredToken() {
            // 31분 전에 발급된 Access 토큰(유효 30분) → 지금은 만료 상태
            GeneratedToken expired = adapterAt(NOW.minus(Duration.ofMinutes(31)))
                    .generateAccessToken(USER_ID, USERNAME, ROLES);
            NimbusJwtAdapter now = adapterAt(NOW);

            assertThatThrownBy(() -> now.parse(expired.value(), TokenType.ACCESS))
                    .isInstanceOf(BusinessException.class)
                    .extracting(NimbusJwtAdapterTest.this::errorCodeOf)
                    .isEqualTo(AuthError.TOKEN_EXPIRED);
        }

        @Test
        @DisplayName("서명이 교체된 토큰은 TOKEN_INVALID 로 거부된다")
        void rejectsTamperedSignature() {
            NimbusJwtAdapter adapter = adapterAt(NOW);
            String token = adapter.generateAccessToken(USER_ID, USERNAME, ROLES).value();

            // 서명 세그먼트를 통째로 갈아끼운다.
            //
            // 마지막 한 글자만 바꾸는 방식은 쓰면 안 된다. HS256 서명은 32바이트인데
            // Base64URL 로는 43자가 되고, 마지막 문자는 4비트만 유효하다(나머지는 패딩).
            // 따라서 마지막 글자를 바꿔도 디코딩 결과가 같아 서명이 통과할 수 있다.
            // jti 가 매번 달라 토큰도 매번 달라지므로, 그런 테스트는 간헐적으로 실패한다.
            String[] parts = token.split("\\.");
            String tampered = parts[0] + "." + parts[1] + ".Zm9yZ2VkLXNpZ25hdHVyZS12YWx1ZQ";

            assertThatThrownBy(() -> adapter.parse(tampered, TokenType.ACCESS))
                    .isInstanceOf(BusinessException.class)
                    .extracting(NimbusJwtAdapterTest.this::errorCodeOf)
                    .isEqualTo(AuthError.TOKEN_INVALID);
        }

        @Test
        @DisplayName("페이로드가 변조된 토큰은 TOKEN_INVALID 로 거부된다")
        void rejectsTamperedPayload() {
            NimbusJwtAdapter adapter = adapterAt(NOW);
            String token = adapter.generateAccessToken(USER_ID, USERNAME, ROLES).value();

            // 다른 사용자의 토큰인 것처럼 페이로드만 바꿔치기한다. 서명은 그대로이므로 불일치가 난다.
            String[] parts = token.split("\\.");
            String forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString("{\"sub\":\"999\",\"typ\":\"ACCESS\"}".getBytes());
            String tampered = parts[0] + "." + forgedPayload + "." + parts[2];

            assertThatThrownBy(() -> adapter.parse(tampered, TokenType.ACCESS))
                    .extracting(NimbusJwtAdapterTest.this::errorCodeOf)
                    .isEqualTo(AuthError.TOKEN_INVALID);
        }

        @Test
        @DisplayName("jti 검사가 타입 검사보다 먼저다")
        void jtiIsCheckedBeforeType() {
            // jti 도 없고 타입도 틀린 토큰. 두 검사 모두 실패할 수 있는 상황에서
            // 어느 쪽 에러가 나오는지로 순서를 고정한다.
            String token = signWithoutJti(TokenType.REFRESH);

            assertThatThrownBy(() -> adapterAt(NOW).parse(token, TokenType.ACCESS))
                    .isInstanceOf(BusinessException.class)
                    .extracting(NimbusJwtAdapterTest.this::errorCodeOf)
                    .as("jti 없음이 타입 불일치보다 근본적인 문제다")
                    .isEqualTo(AuthError.TOKEN_INVALID);
        }

        @Test
        @DisplayName("jti 가 없는 토큰은 TOKEN_INVALID 로 거부된다")
        void tokenWithoutJtiIsRejected() {
            String token = signWithoutJti(TokenType.ACCESS);

            // jti 가 없으면 폐기 대조를 할 수 없다. 통과시키면 로그아웃이 무력화된다.
            assertThatThrownBy(() -> adapterAt(NOW).parse(token, TokenType.ACCESS))
                    .isInstanceOf(BusinessException.class)
                    .extracting(NimbusJwtAdapterTest.this::errorCodeOf)
                    .isEqualTo(AuthError.TOKEN_INVALID);
        }

        @Test
        @DisplayName("JWT 형식이 아닌 문자열은 TOKEN_INVALID 로 거부된다")
        void rejectsMalformedToken() {
            NimbusJwtAdapter adapter = adapterAt(NOW);

            assertThatThrownBy(() -> adapter.parse("not-a-jwt", TokenType.ACCESS))
                    .extracting(NimbusJwtAdapterTest.this::errorCodeOf)
                    .isEqualTo(AuthError.TOKEN_INVALID);
        }

        @Test
        @DisplayName("Refresh 토큰을 Access 자리에 쓰면 TOKEN_TYPE_MISMATCH 로 막는다")
        void rejectsRefreshTokenWhereAccessExpected() {
            NimbusJwtAdapter adapter = adapterAt(NOW);
            String refresh = adapter.generateRefreshToken(USER_ID).value();

            // 이 검증이 없으면 유효기간 14일짜리 토큰을 Access 토큰처럼 쓸 수 있다
            assertThatThrownBy(() -> adapter.parse(refresh, TokenType.ACCESS))
                    .extracting(NimbusJwtAdapterTest.this::errorCodeOf)
                    .isEqualTo(AuthError.TOKEN_TYPE_MISMATCH);
        }

        @Test
        @DisplayName("Access 토큰으로는 재발급할 수 없다")
        void rejectsAccessTokenWhereRefreshExpected() {
            NimbusJwtAdapter adapter = adapterAt(NOW);
            String access = adapter.generateAccessToken(USER_ID, USERNAME, ROLES).value();

            assertThatThrownBy(() -> adapter.parse(access, TokenType.REFRESH))
                    .extracting(NimbusJwtAdapterTest.this::errorCodeOf)
                    .isEqualTo(AuthError.TOKEN_TYPE_MISMATCH);
        }

        @Test
        @DisplayName("만료 검사가 타입 검사보다 먼저다")
        void expiryIsCheckedBeforeType() {
            GeneratedToken expiredRefresh = adapterAt(NOW.minus(Duration.ofDays(15)))
                    .generateRefreshToken(USER_ID);
            NimbusJwtAdapter now = adapterAt(NOW);

            // 만료됐고 타입도 다른 토큰 — 만료된 토큰의 종류까지 알려줄 필요는 없다
            assertThatThrownBy(() -> now.parse(expiredRefresh.value(), TokenType.ACCESS))
                    .extracting(NimbusJwtAdapterTest.this::errorCodeOf)
                    .isEqualTo(AuthError.TOKEN_EXPIRED);
        }

        @Test
        @DisplayName("다른 키로 서명된 토큰은 거부된다")
        void rejectsTokenSignedWithDifferentKey() {
            JwtProperties otherProps = new JwtProperties(
                    "attacker",
                    Base64.getEncoder().encodeToString("another-secret-key-32bytes-long!!".getBytes()),
                    Duration.ofMinutes(30),
                    Duration.ofDays(14));
            SecretKey otherKey = keyConfig.jwtSecretKey(otherProps);
            NimbusJwtAdapter forged = new NimbusJwtAdapter(
                    keyConfig.jwtEncoder(otherKey), keyConfig.jwtDecoder(otherKey),
                    otherProps, Clock.fixed(NOW, ZoneOffset.UTC));

            String forgedToken = forged.generateAccessToken(USER_ID, USERNAME, ROLES).value();
            NimbusJwtAdapter ours = adapterAt(NOW);

            assertThatThrownBy(() -> ours.parse(forgedToken, TokenType.ACCESS))
                    .extracting(NimbusJwtAdapterTest.this::errorCodeOf)
                    .isEqualTo(AuthError.TOKEN_INVALID);
        }
    }
}
