package com.toby.klass.auth.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.toby.klass.auth.application.dto.LoginCommand;
import com.toby.klass.auth.application.dto.LogoutCommand;
import com.toby.klass.auth.application.dto.ReissueCommand;
import com.toby.klass.auth.application.dto.TokenResult;
import com.toby.klass.auth.application.port.out.CredentialsVerifierPort;
import com.toby.klass.auth.application.port.out.RefreshTokenCommandPort;
import com.toby.klass.auth.application.port.out.RefreshTokenQueryPort;
import com.toby.klass.auth.application.port.out.RevokedAccessTokenCommandPort;
import com.toby.klass.auth.application.port.out.TokenGeneratorPort;
import com.toby.klass.auth.application.port.out.TokenHasherPort;
import com.toby.klass.auth.application.port.out.TokenParserPort;
import com.toby.klass.auth.application.port.out.dto.GeneratedToken;
import com.toby.klass.auth.application.port.out.dto.VerifiedCredentials;
import com.toby.klass.auth.domain.RefreshToken;
import com.toby.klass.auth.domain.RevokedAccessToken;
import com.toby.klass.auth.domain.TokenType;
import com.toby.klass.auth.domain.error.AuthError;
import com.toby.klass.common.domain.error.BusinessException;
import com.toby.klass.common.domain.error.ErrorCode;
import com.toby.klass.user.application.port.out.UserQueryPort;
import com.toby.klass.user.domain.Role;
import com.toby.klass.user.domain.User;
import com.toby.klass.user.domain.error.UserError;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link AuthService} 흐름 검증.
 *
 * <p>포트를 전부 목으로 대체해 <b>조율 로직만</b> 검증한다. 토큰 서명이나 DB 동작은
 * 각 어댑터의 테스트가 맡는다.
 *
 * <p>특히 재사용 감지 시 침해 대응이 호출되는지를 {@code verify} 로 고정한다 —
 * 이 호출이 빠지면 탈취된 토큰이 계속 유효한 상태로 남는다.
 *
 * <p>Design Ref: §8.2 L1 / L2 시나리오 #5, #6, #11, #12
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

        /** JWT 경계에서 쓰는 절대 시각. 토큰 생성 포트가 이 값을 돌려준다. */
    private static final Instant NOW = Instant.parse("2026-08-30T12:00:00Z");

    /** 도메인이 쓰는 벽시계 시각. UTC 시계를 주입하므로 NOW 와 같은 시점이다. */
    private static final LocalDateTime NOW_LOCAL = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
    private static final Long USER_ID = 7L;
    private static final String USERNAME = "chals";
    private static final String RAW_PASSWORD = "test";
    private static final String REFRESH_TOKEN = "refresh.token.value";
    private static final String TOKEN_HASH = "b".repeat(64);

    /** 로그아웃 요청에 실려 오는 현재 Access 토큰의 jti. 필터가 파싱해 principal 에 담아둔 값이다. */
    private static final String ACCESS_JTI = "11111111-2222-3333-4444-555555555555";

    /** 그 Access 토큰의 만료 시각. 폐기 기록의 정리 기준이 된다. */
    private static final Instant ACCESS_EXPIRES_AT = NOW.plus(Duration.ofMinutes(30));

    @Mock private UserQueryPort userQueryPort;
    @Mock private CredentialsVerifierPort credentialsVerifierPort;
    @Mock private TokenGeneratorPort tokenGeneratorPort;
    @Mock private TokenParserPort tokenParserPort;
    @Mock private TokenHasherPort tokenHasherPort;
    @Mock private RefreshTokenQueryPort refreshTokenQueryPort;
    @Mock private RefreshTokenCommandPort refreshTokenCommandPort;
    @Mock private RevokedAccessTokenCommandPort revokedAccessTokenCommandPort;
    @Mock private RefreshTokenBreachHandler breachHandler;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(
                userQueryPort, credentialsVerifierPort, tokenGeneratorPort, tokenParserPort,
                tokenHasherPort, refreshTokenQueryPort, refreshTokenCommandPort,
                revokedAccessTokenCommandPort, breachHandler, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // ── 테스트 픽스처 ────────────────────────────────────────────────

    /** id 가 채워진 사용자. 실제로는 JPA 가 채우므로 테스트에서는 리플렉션으로 넣는다. */
    private User user() {
        User user = User.register(USERNAME, "$2a$10$hashed", Set.of(Role.ROLE_USER), NOW_LOCAL);
        setField(user, "id", USER_ID);
        return user;
    }

    private void setField(Object target, String name, Object value) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("테스트 데이터 준비 실패", e);
        }
    }

    /** 토큰 발급 포트가 정상 동작하도록 설정한다. */
    private void givenTokenGeneration() {
        given(tokenGeneratorPort.generateAccessToken(eq(USER_ID), eq(USERNAME), any()))
                .willReturn(new GeneratedToken("access.token", NOW, NOW.plus(Duration.ofMinutes(30))));
        given(tokenGeneratorPort.generateRefreshToken(USER_ID))
                .willReturn(new GeneratedToken(REFRESH_TOKEN, NOW, NOW.plus(Duration.ofDays(14))));
        given(tokenHasherPort.sha256Hex(REFRESH_TOKEN)).willReturn(TOKEN_HASH);
    }

    private ErrorCode errorCodeOf(Throwable t) {
        return ((BusinessException) t).errorCode();
    }

    // ── 로그인 ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("로그인")
    class Login {

        private final VerifiedCredentials verified =
                new VerifiedCredentials(USER_ID, USERNAME, List.of("ROLE_USER"));

        @Test
        @DisplayName("자격 증명이 확인되면 토큰 쌍을 발급하고 Refresh 기록을 저장한다")
        void issuesTokenPair() {
            given(credentialsVerifierPort.verify(USERNAME, RAW_PASSWORD)).willReturn(verified);
            givenTokenGeneration();

            TokenResult result = authService.login(new LoginCommand(USERNAME, RAW_PASSWORD));

            assertThat(result.tokenType()).isEqualTo("Bearer");
            assertThat(result.accessToken()).isEqualTo("access.token");
            assertThat(result.accessTokenExpiresIn()).isEqualTo(1800);
            assertThat(result.refreshTokenExpiresIn()).isEqualTo(Duration.ofDays(14).toSeconds());
            // 남은 초와 만료 일시가 같은 시점을 가리켜야 한다
            assertThat(result.accessTokenExpiresAt()).isEqualTo(NOW_LOCAL.plusMinutes(30));
            assertThat(result.refreshTokenExpiresAt()).isEqualTo(NOW_LOCAL.plusDays(14));
            // 저장 호출이 일어났는지만 본다 — tokenHash 값 검증은 RefreshTokenTest 소관이다
            verify(refreshTokenCommandPort).save(any(RefreshToken.class));
        }

        @Test
        @DisplayName("검증 실패는 그대로 전파한다 — 서비스가 원인을 재해석하지 않는다")
        void propagatesVerificationFailure() {
            // 아이디 없음·비밀번호 불일치를 구분하지 않는 책임은 어댑터에 있다.
            // 서비스는 그 결정을 덮어쓰지 않는다.
            willThrow(AuthError.INVALID_CREDENTIALS.toException())
                    .given(credentialsVerifierPort).verify(USERNAME, RAW_PASSWORD);

            assertThatThrownBy(() -> authService.login(new LoginCommand(USERNAME, RAW_PASSWORD)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(AuthServiceTest.this::errorCodeOf)
                    .isEqualTo(AuthError.INVALID_CREDENTIALS);

            // 인증에 실패했으면 토큰을 만들지 않는다
            verify(tokenGeneratorPort, never()).generateAccessToken(any(), any(), any());
            verify(refreshTokenCommandPort, never()).save(any());
        }

        @Test
        @DisplayName("비활성 계정도 어댑터가 판단한 코드를 그대로 올린다")
        void propagatesDisabledAccount() {
            willThrow(UserError.USER_DISABLED.toException())
                    .given(credentialsVerifierPort).verify(USERNAME, RAW_PASSWORD);

            assertThatThrownBy(() -> authService.login(new LoginCommand(USERNAME, RAW_PASSWORD)))
                    .extracting(AuthServiceTest.this::errorCodeOf)
                    .isEqualTo(UserError.USER_DISABLED);
        }

        @Test
        @DisplayName("로그인은 사용자 조회 포트를 쓰지 않는다 — 인증은 어댑터의 책임이다")
        void doesNotQueryUserDirectly() {
            given(credentialsVerifierPort.verify(USERNAME, RAW_PASSWORD)).willReturn(verified);
            givenTokenGeneration();

            authService.login(new LoginCommand(USERNAME, RAW_PASSWORD));

            verify(userQueryPort, never()).findByUsername(any());
        }
    }

    // ── 재발급 ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("재발급")
    class Reissue {

        private RefreshToken storedToken() {
            return RefreshToken.issue(USER_ID, TOKEN_HASH, NOW_LOCAL.minusDays(1), NOW_LOCAL.plusDays(13));
        }

        @Test
        @DisplayName("정상 회전하면 기존 토큰이 폐기되고 새 쌍이 발급된다")
        void rotatesAndIssuesNewPair() {
            RefreshToken stored = storedToken();
            given(tokenHasherPort.sha256Hex(REFRESH_TOKEN)).willReturn(TOKEN_HASH);
            given(refreshTokenQueryPort.findByTokenHash(TOKEN_HASH)).willReturn(Optional.of(stored));
            given(userQueryPort.findById(USER_ID)).willReturn(Optional.of(user()));
            givenTokenGeneration();

            TokenResult result = authService.reissue(new ReissueCommand(REFRESH_TOKEN));

            assertThat(stored.isRevoked()).isTrue();
            assertThat(stored.getRevokedAt()).isEqualTo(NOW_LOCAL);
            assertThat(result.accessToken()).isEqualTo("access.token");
            verify(tokenParserPort).parse(REFRESH_TOKEN, TokenType.REFRESH);
            verify(breachHandler, never()).revokeAllOf(any());
        }

        @Test
        @DisplayName("DB 에 없는 토큰은 REFRESH_TOKEN_NOT_FOUND 다")
        void unknownTokenIsRejected() {
            given(tokenHasherPort.sha256Hex(REFRESH_TOKEN)).willReturn(TOKEN_HASH);
            given(refreshTokenQueryPort.findByTokenHash(TOKEN_HASH)).willReturn(Optional.empty());

            assertThatThrownBy(() -> authService.reissue(new ReissueCommand(REFRESH_TOKEN)))
                    .extracting(AuthServiceTest.this::errorCodeOf)
                    .isEqualTo(AuthError.REFRESH_TOKEN_NOT_FOUND);
        }

        @Test
        @DisplayName("재사용이 감지되면 전체 무효화를 호출한 뒤 예외를 올린다")
        void revokesAllOnReuseDetection() {
            RefreshToken alreadyRotated = storedToken();
            alreadyRotated.rotate(NOW_LOCAL.minusSeconds(60));   // 이미 한 번 쓰인 토큰
            given(tokenHasherPort.sha256Hex(REFRESH_TOKEN)).willReturn(TOKEN_HASH);
            given(refreshTokenQueryPort.findByTokenHash(TOKEN_HASH))
                    .willReturn(Optional.of(alreadyRotated));

            assertThatThrownBy(() -> authService.reissue(new ReissueCommand(REFRESH_TOKEN)))
                    .extracting(AuthServiceTest.this::errorCodeOf)
                    .isEqualTo(AuthError.REFRESH_TOKEN_REUSED);

            // 이 호출이 빠지면 탈취된 토큰으로 계속 재발급할 수 있다
            verify(breachHandler).revokeAllOf(USER_ID);
            // 침해 상황에서는 새 토큰을 발급하지 않는다
            verify(refreshTokenCommandPort, never()).save(any());
        }

        @Test
        @DisplayName("재사용이 아닌 실패에는 전체 무효화를 하지 않는다")
        void doesNotRevokeAllOnNonReuseFailure() {
            RefreshToken expired = RefreshToken.issue(USER_ID, TOKEN_HASH,
                    NOW_LOCAL.minusDays(20), NOW_LOCAL.minusDays(1));
            given(tokenHasherPort.sha256Hex(REFRESH_TOKEN)).willReturn(TOKEN_HASH);
            given(refreshTokenQueryPort.findByTokenHash(TOKEN_HASH)).willReturn(Optional.of(expired));

            assertThatThrownBy(() -> authService.reissue(new ReissueCommand(REFRESH_TOKEN)))
                    .extracting(AuthServiceTest.this::errorCodeOf)
                    .isEqualTo(AuthError.REFRESH_TOKEN_EXPIRED);

            // 단순 만료는 탈취 신호가 아니다
            verify(breachHandler, never()).revokeAllOf(any());
        }

        @Test
        @DisplayName("재발급 시 사용자를 DB 에서 다시 읽어 권한을 최신화한다")
        void reloadsUserFromDatabase() {
            given(tokenHasherPort.sha256Hex(REFRESH_TOKEN)).willReturn(TOKEN_HASH);
            given(refreshTokenQueryPort.findByTokenHash(TOKEN_HASH)).willReturn(Optional.of(storedToken()));
            given(userQueryPort.findById(USER_ID)).willReturn(Optional.of(user()));
            givenTokenGeneration();

            authService.reissue(new ReissueCommand(REFRESH_TOKEN));

            // 토큰 클레임이 아니라 DB 를 신뢰한다
            verify(userQueryPort).findById(USER_ID);
        }
    }

    // ── 로그아웃 ────────────────────────────────────────────────────

    @Nested
    @DisplayName("로그아웃")
    class Logout {

        @Test
        @DisplayName("토큰 해시와 소유자를 함께 넘겨 삭제한다")
        void deletesWithOwnerCheck() {
            given(tokenHasherPort.sha256Hex(REFRESH_TOKEN)).willReturn(TOKEN_HASH);

            authService.logout(logoutCommand());

            // userId 조건이 빠지면 남의 토큰을 지울 수 있다
            verify(refreshTokenCommandPort).deleteByTokenHashAndUserId(TOKEN_HASH, USER_ID);
        }

        @Test
        @DisplayName("현재 Access 토큰을 폐기 목록에 올린다")
        void revokesCurrentAccessToken() {
            given(tokenHasherPort.sha256Hex(REFRESH_TOKEN)).willReturn(TOKEN_HASH);

            authService.logout(logoutCommand());

            // 이 등록이 빠지면 로그아웃해도 Access 토큰이 만료까지 그대로 통과한다
            var captor = forClass(RevokedAccessToken.class);
            verify(revokedAccessTokenCommandPort).revoke(captor.capture());

            RevokedAccessToken revoked = captor.getValue();
            assertThat(revoked.getJti()).isEqualTo(ACCESS_JTI);
            assertThat(revoked.getUserId()).isEqualTo(USER_ID);
            assertThat(revoked.getRevokedAt()).isEqualTo(NOW_LOCAL);
        }

        @Test
        @DisplayName("폐기 기록의 만료 시각은 원 토큰의 exp 와 같은 시점이다")
        void keepsOriginalExpiry() {
            given(tokenHasherPort.sha256Hex(REFRESH_TOKEN)).willReturn(TOKEN_HASH);

            authService.logout(logoutCommand());

            var captor = forClass(RevokedAccessToken.class);
            verify(revokedAccessTokenCommandPort).revoke(captor.capture());

            // 앞당겨 잡으면 아직 유효한 토큰이 블랙리스트에서 먼저 사라져 되살아난다
            assertThat(captor.getValue().getExpiresAt())
                    .isEqualTo(LocalDateTime.ofInstant(ACCESS_EXPIRES_AT, ZoneOffset.UTC));
        }

        @Test
        @DisplayName("삭제된 행이 없어도 예외 없이 통과한다 — 멱등")
        void isIdempotent() {
            given(tokenHasherPort.sha256Hex(anyString())).willReturn(TOKEN_HASH);
            given(refreshTokenCommandPort.deleteByTokenHashAndUserId(TOKEN_HASH, USER_ID)).willReturn(0L);

            // 예외가 나지 않아야 한다
            authService.logout(logoutCommand());
        }

        private LogoutCommand logoutCommand() {
            return new LogoutCommand(USER_ID, REFRESH_TOKEN, ACCESS_JTI, ACCESS_EXPIRES_AT);
        }
    }
}
