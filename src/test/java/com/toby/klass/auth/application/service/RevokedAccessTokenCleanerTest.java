package com.toby.klass.auth.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.toby.klass.auth.application.port.out.RevokedAccessTokenCommandPort;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link RevokedAccessTokenCleaner} 검증.
 *
 * <p>스케줄러를 실제로 돌리지 않고 메서드를 직접 부른다. 확인할 것은 "주기가 맞는가"가
 * 아니라 <b>주입된 시계를 기준으로 삭제를 위임하는가</b>이다. 시스템 시계를 쓰면
 * 테스트에서 경계 조건을 재현할 수 없다.
 *
 * <p>Design Ref: §8.3 L2 단위 테스트
 */
@ExtendWith(MockitoExtension.class)
class RevokedAccessTokenCleanerTest {

    private static final Instant NOW = Instant.parse("2026-08-30T12:00:00Z");
    private static final LocalDateTime NOW_LOCAL = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);

    @Mock private RevokedAccessTokenCommandPort commandPort;

    private RevokedAccessTokenCleaner cleaner() {
        return new RevokedAccessTokenCleaner(commandPort, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("주입된 시계 기준으로 만료된 기록을 지운다")
    void purgesUsingInjectedClock() {
        given(commandPort.deleteExpired(NOW_LOCAL)).willReturn(3L);

        assertThat(cleaner().purgeExpired()).isEqualTo(3);

        // 시스템 시계를 쓰면 이 검증이 불가능해진다.
        verify(commandPort).deleteExpired(NOW_LOCAL);
    }

    @Test
    @DisplayName("지울 것이 없어도 조용히 끝난다")
    void purgesNothingQuietly() {
        given(commandPort.deleteExpired(NOW_LOCAL)).willReturn(0L);

        assertThat(cleaner().purgeExpired()).isZero();
    }
}
