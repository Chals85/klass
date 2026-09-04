package com.toby.klass.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toby.klass.auth.domain.error.AuthError;
import com.toby.klass.common.domain.error.BusinessException;
import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link RefreshToken} 도메인 규칙 검증.
 *
 * <p>회전과 재사용 감지는 이 프로젝트의 핵심 정책이다. 서비스가 아니라 도메인이
 * 이 규칙을 들고 있어야 하므로, 서비스 없이 도메인 객체만으로 검증한다.
 *
 * <p>Design Ref: §8.2 L1 / L2 시나리오 #1~#3
 */
class RefreshTokenTest {

    private static final Long USER_ID = 1L;
    private static final String TOKEN_HASH = "a".repeat(64);
    private static final LocalDateTime ISSUED_AT = LocalDateTime.parse("2026-08-30T00:00:00");
    private static final LocalDateTime EXPIRES_AT = ISSUED_AT.plus(Duration.ofDays(14));

    private RefreshToken newToken() {
        return RefreshToken.issue(USER_ID, TOKEN_HASH, ISSUED_AT, EXPIRES_AT);
    }

    @Nested
    @DisplayName("발급")
    class Issue {

        @Test
        @DisplayName("발급 직후에는 폐기되지 않은 상태다")
        void issuedTokenIsNotRevoked() {
            RefreshToken token = newToken();

            assertThat(token.isRevoked()).isFalse();
            assertThat(token.getRevokedAt()).isNull();
            assertThat(token.getUserId()).isEqualTo(USER_ID);
            assertThat(token.getTokenHash()).isEqualTo(TOKEN_HASH);
        }

        @Test
        @DisplayName("만료가 발급보다 이르면 생성할 수 없다")
        void rejectsExpiryBeforeIssuedAt() {
            assertThatThrownBy(() -> RefreshToken.issue(USER_ID, TOKEN_HASH, ISSUED_AT, ISSUED_AT.minusSeconds(1)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("회전")
    class Rotate {

        @Test
        @DisplayName("정상 회전하면 폐기 상태가 되고 폐기 시각이 기록된다")
        void rotateMarksRevoked() {
            RefreshToken token = newToken();
            LocalDateTime now = ISSUED_AT.plus(Duration.ofDays(1));

            token.rotate(now);

            assertThat(token.isRevoked()).isTrue();
            assertThat(token.getRevokedAt()).isEqualTo(now);
        }

        @Test
        @DisplayName("이미 회전된 토큰을 다시 쓰면 재사용으로 감지한다")
        void detectsReuse() {
            RefreshToken token = newToken();
            LocalDateTime firstUse = ISSUED_AT.plus(Duration.ofDays(1));
            token.rotate(firstUse);

            // 탈취된 토큰으로 두 번째 재발급을 시도하는 상황
            assertThatThrownBy(() -> token.rotate(firstUse.plusSeconds(1)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(AuthError.REFRESH_TOKEN_REUSED);

            // 폐기 시각은 최초 회전 시점 그대로여야 한다 (두 번째 시도가 덮어쓰지 않음)
            assertThat(token.getRevokedAt()).isEqualTo(firstUse);
        }

        @Test
        @DisplayName("만료된 토큰은 회전할 수 없다 — DB 만료 시각에 대한 불변식 방어")
        void rejectsExpiredToken() {
            RefreshToken token = newToken();
            LocalDateTime afterExpiry = EXPIRES_AT.plusSeconds(1);

            assertThatThrownBy(() -> token.rotate(afterExpiry))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(AuthError.REFRESH_TOKEN_EXPIRED);

            assertThat(token.isRevoked()).isFalse();
        }

        @Test
        @DisplayName("재사용 검사가 만료 검사보다 먼저다")
        void reuseIsCheckedBeforeExpiry() {
            RefreshToken token = newToken();
            token.rotate(ISSUED_AT.plusSeconds(1));

            // 폐기됐고 동시에 만료도 된 토큰 — 탈취 신호를 우선한다
            assertThatThrownBy(() -> token.rotate(EXPIRES_AT.plusSeconds(1)))
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(AuthError.REFRESH_TOKEN_REUSED);
        }
    }
}
