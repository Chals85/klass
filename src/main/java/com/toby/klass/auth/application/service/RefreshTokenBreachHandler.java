package com.toby.klass.auth.application.service;

import com.toby.klass.auth.application.port.out.RefreshTokenCommandPort;
import java.time.Clock;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Refresh 토큰 재사용이 감지됐을 때 해당 사용자의 토큰을 전부 무효화한다.
 *
 * <h2>왜 별도 클래스인가 — 이 설계에서 가장 미묘한 지점</h2>
 * {@code AuthService} 안에 메서드로 두면 <b>동작하지 않는다.</b> 이유가 둘이다.
 *
 * <ol>
 *   <li><b>트랜잭션 롤백</b>: 재사용 감지는 예외로 표현되고, 그 예외는 호출자에게 전파돼야
 *       401 이 나간다. 그런데 같은 트랜잭션 안에서 무효화 UPDATE 를 하고 예외를 던지면
 *       트랜잭션이 rollback-only 로 마킹되어 <b>무효화까지 함께 사라진다</b>.
 *       {@code REQUIRES_NEW} 로 독립 트랜잭션을 열어야 UPDATE 가 살아남는다.</li>
 *   <li><b>self-invocation</b>: Spring 의 {@code @Transactional} 은 프록시로 동작한다.
 *       같은 클래스의 메서드를 {@code this.method()} 로 부르면 프록시를 거치지 않아
 *       {@code REQUIRES_NEW} 가 <b>조용히 무시된다</b>. 다른 빈으로 분리해야 프록시를 탄다.</li>
 * </ol>
 *
 * <p>이 두 가지는 컴파일러도 테스트도 잡아주지 않는다 — 통합 테스트에서 "무효화가 반영되지
 * 않는다"는 증상으로만 드러난다. §8.4 #5 가 그것을 지키는 시나리오다.
 *
 * <p>Design Ref: §2.2 [재사용 감지 처리] 트랜잭션 경계
 */
@Service
public class RefreshTokenBreachHandler {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenBreachHandler.class);

    private final RefreshTokenCommandPort refreshTokenCommandPort;
    private final Clock clock;

    /**
     * 침해 대응에 필요한 포트와 시계를 주입받는다.
     *
     * @param refreshTokenCommandPort 토큰 변경 포트
     * @param clock 무효화 시각을 얻을 시계
     */
    public RefreshTokenBreachHandler(RefreshTokenCommandPort refreshTokenCommandPort, Clock clock) {
        this.refreshTokenCommandPort = refreshTokenCommandPort;
        this.clock = clock;
    }

    /**
     * 해당 사용자의 유효한 Refresh 토큰을 모두 무효화한다.
     *
     * <p>정상 사용자와 공격자 중 누가 먼저 토큰을 썼는지 알 수 없으므로 양쪽 모두
     * 재로그인시킨다. 불편하지만 탈취된 세션을 방치하는 것보다 낫다.
     *
     * @param userId 재사용이 감지된 토큰의 소유자
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeAllOf(Long userId) {
        long revokedCount = refreshTokenCommandPort.revokeAllByUserId(userId, LocalDateTime.now(clock));
        // 보안 이벤트이므로 WARN 으로 남긴다. 운영에서는 알림으로 연결할 지점이다.
        log.warn("Refresh 토큰 재사용 감지 — userId={}, 무효화 {}건", userId, revokedCount);
    }
}
