package com.toby.klass.auth.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.toby.klass.auth.domain.RevokedAccessToken;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

/**
 * {@link RevokedAccessTokenRepositoryAdapter} 영속 동작 검증.
 *
 * <p>{@code @DataJpaTest} 로 JPA 계층만 띄운다. 어댑터는 {@code @Component} 라
 * 이 슬라이스가 자동으로 잡지 않으므로 {@code @Import} 로 넣어준다.
 *
 * <p>여기서 확인하는 것들은 <b>목으로는 검증할 수 없다</b> — unique 제약 아래에서의
 * 멱등성과, 실제 DELETE 쿼리의 경계 조건이기 때문이다.
 *
 * <p>Design Ref: §8.2 L1 / L2 시나리오, §3.1 Entity Definition
 */
@DataJpaTest
@Import(RevokedAccessTokenRepositoryAdapter.class)
class RevokedAccessTokenRepositoryAdapterTest {

    private static final String JTI = "11111111-2222-3333-4444-555555555555";
    private static final Long USER_ID = 7L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 30, 12, 0);

    @Autowired private RevokedAccessTokenRepositoryAdapter adapter;
    @Autowired private RevokedAccessTokenJpaRepository jpaRepository;

    private RevokedAccessToken revoked(String jti, LocalDateTime expiresAt) {
        return RevokedAccessToken.revoke(jti, USER_ID, expiresAt, NOW);
    }

    @Test
    @DisplayName("등록한 토큰은 폐기된 것으로 조회된다")
    void revokedTokenIsFound() {
        adapter.revoke(revoked(JTI, NOW.plusMinutes(30)));

        assertThat(adapter.isRevoked(JTI)).isTrue();
        assertThat(adapter.isRevoked("other-token-id")).isFalse();
    }

    @Test
    @DisplayName("jti 가 null 이면 조회하지 않고 false 다")
    void nullTokenIdIsNotRevoked() {
        // 방어적 처리다. jti 없는 토큰은 파서가 이미 막지만,
        // 여기서 NPE 가 나면 인증 경로 전체가 500 이 된다.
        assertThat(adapter.isRevoked(null)).isFalse();
    }

    @Test
    @DisplayName("같은 토큰을 두 번 등록해도 예외가 나지 않는다 — 멱등")
    void revokeIsIdempotent() {
        adapter.revoke(revoked(JTI, NOW.plusMinutes(30)));

        // unique 제약 위반이 새어나오면 트랜잭션이 rollback-only 가 되어
        // 로그아웃 전체가 실패한다.
        assertThatCode(() -> adapter.revoke(revoked(JTI, NOW.plusMinutes(30))))
                .doesNotThrowAnyException();

        assertThat(jpaRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("만료된 기록만 정리한다 — 아직 유효한 것은 남긴다")
    void deletesOnlyExpired() {
        adapter.revoke(revoked("expired-long-ago", NOW.minusHours(1)));
        adapter.revoke(revoked("expired-just-now", NOW));
        adapter.revoke(revoked("still-valid", NOW.plusMinutes(1)));

        long purged = adapter.deleteExpired(NOW);

        // 아직 유효한 토큰을 지우면 그 토큰이 폐기 목록에서 사라져 다시 통과한다.
        assertThat(purged).isEqualTo(2);
        assertThat(adapter.isRevoked("still-valid")).isTrue();
        assertThat(adapter.isRevoked("expired-just-now")).isFalse();
    }

    @Test
    @DisplayName("지울 것이 없으면 0 을 돌려준다")
    void deletesNothingWhenAllValid() {
        adapter.revoke(revoked(JTI, NOW.plusMinutes(30)));

        assertThat(adapter.deleteExpired(NOW)).isZero();
    }
}
