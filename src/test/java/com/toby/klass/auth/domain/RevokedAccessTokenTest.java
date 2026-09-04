package com.toby.klass.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link RevokedAccessToken} 도메인 규칙 검증.
 *
 * <p>Spring 도 DB 도 필요 없다. 이 엔티티가 스스로 지켜야 하는 것만 확인한다.
 *
 * <p>Design Ref: §8.2 L1 / L2 시나리오
 */
class RevokedAccessTokenTest {

    private static final String JTI = "11111111-2222-3333-4444-555555555555";
    private static final Long USER_ID = 7L;
    private static final LocalDateTime REVOKED_AT = LocalDateTime.of(2026, 8, 30, 12, 0);
    private static final LocalDateTime EXPIRES_AT = REVOKED_AT.plusMinutes(30);

    @Nested
    @DisplayName("생성")
    class Creation {

        @Test
        @DisplayName("넘긴 값이 그대로 담긴다")
        void keepsGivenValues() {
            RevokedAccessToken revoked =
                    RevokedAccessToken.revoke(JTI, USER_ID, EXPIRES_AT, REVOKED_AT);

            assertThat(revoked.getJti()).isEqualTo(JTI);
            assertThat(revoked.getUserId()).isEqualTo(USER_ID);
            assertThat(revoked.getExpiresAt()).isEqualTo(EXPIRES_AT);
            assertThat(revoked.getRevokedAt()).isEqualTo(REVOKED_AT);
            assertThat(revoked.getId()).as("아직 영속화 전이므로 PK 는 비어 있다").isNull();
        }

        @Test
        @DisplayName("jti 가 비어 있으면 만들 수 없다")
        void rejectsBlankTokenId() {
            // jti 없이 저장되면 어떤 토큰을 막는 기록인지 알 수 없다.
            assertThatThrownBy(() -> RevokedAccessToken.revoke("  ", USER_ID, EXPIRES_AT, REVOKED_AT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("jti");

            assertThatThrownBy(() -> RevokedAccessToken.revoke(null, USER_ID, EXPIRES_AT, REVOKED_AT))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("정리 대상 판정")
    class Purgeable {

        @Test
        @DisplayName("원 토큰이 아직 유효하면 지우면 안 된다")
        void notPurgeableWhileValid() {
            RevokedAccessToken revoked =
                    RevokedAccessToken.revoke(JTI, USER_ID, EXPIRES_AT, REVOKED_AT);

            // 만료 전에 지우면 그 토큰이 폐기 목록에서 사라져 다시 통과한다.
            assertThat(revoked.isPurgeableAt(EXPIRES_AT.minusSeconds(1))).isFalse();
        }

        @Test
        @DisplayName("만료 시각에 도달하면 지워도 된다")
        void purgeableAtExpiry() {
            RevokedAccessToken revoked =
                    RevokedAccessToken.revoke(JTI, USER_ID, EXPIRES_AT, REVOKED_AT);

            // 이 시점부터는 토큰 파싱이 TOKEN_EXPIRED 로 먼저 막으므로 기록이 불필요하다.
            assertThat(revoked.isPurgeableAt(EXPIRES_AT)).isTrue();
            assertThat(revoked.isPurgeableAt(EXPIRES_AT.plusSeconds(1))).isTrue();
        }
    }
}
