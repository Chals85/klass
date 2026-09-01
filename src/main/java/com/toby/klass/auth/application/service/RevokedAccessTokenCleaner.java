package com.toby.klass.auth.application.service;

import com.toby.klass.auth.application.port.out.RevokedAccessTokenCommandPort;
import java.time.Clock;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 만료된 Access 토큰 폐기 기록을 주기적으로 정리한다.
 *
 * <h2>왜 필요한가</h2>
 * 폐기 목록은 로그아웃할 때마다 한 행씩 늘어난다. 정리하지 않으면 무한히 자라고,
 * 이 테이블은 <b>보호된 API 요청마다 조회되는</b> 곳이라 커질수록 서비스 전체가 느려진다.
 * 블랙리스트 방식을 도입했다면 정리 작업은 선택이 아니라 필수다.
 *
 * <h2>지워도 안전한 이유</h2>
 * 원 토큰이 만료됐다면 토큰 파싱이 {@code TOKEN_EXPIRED} 로 먼저 거부한다. 폐기 기록이
 * 사라져도 그 토큰이 되살아나지 않는다 — 두 방어선이 <b>서로 다른 근거</b>로 막기 때문이다.
 * 만료 전에 지우면 토큰이 되살아나므로, 기준은 반드시 원 토큰의 {@code exp} 여야 한다.
 *
 * <h2>단일 인스턴스를 전제로 한다</h2>
 * {@code @Scheduled} 는 인스턴스마다 독립적으로 돈다. 여러 대로 확장하면 같은 작업이
 * 동시에 실행되는데, 이 작업은 멱등한 DELETE 라 결과는 같지만 불필요한 경합이 생긴다.
 * 실서비스에서는 ShedLock 같은 분산 락이나 DB TTL 에 맡기는 것이 보통이다. 이 예제는
 * 단일 인스턴스가 전제다.
 *
 * <p>Design Ref: §2.2 로그아웃 흐름, §3.1 Entity Definition
 */
@Component
public class RevokedAccessTokenCleaner {

    private static final Logger log = LoggerFactory.getLogger(RevokedAccessTokenCleaner.class);

    private final RevokedAccessTokenCommandPort revokedAccessTokenCommandPort;
    private final Clock clock;

    /**
     * 정리에 필요한 포트와 시계를 주입받는다.
     *
     * @param revokedAccessTokenCommandPort 폐기 목록 변경 포트
     * @param clock                         주입된 시계
     */
    public RevokedAccessTokenCleaner(RevokedAccessTokenCommandPort revokedAccessTokenCommandPort,
                                     Clock clock) {
        this.revokedAccessTokenCommandPort = revokedAccessTokenCommandPort;
        this.clock = clock;
    }

    /**
     * 만료된 폐기 기록을 삭제한다.
     *
     * <p>{@code fixedDelay} 를 쓴다({@code fixedRate} 가 아니라). 이전 실행이 끝난 뒤부터
     * 간격을 재므로 정리가 느려져도 작업이 겹쳐 쌓이지 않는다.
     *
     * <p>주기는 Access 토큰 유효 기간보다 짧게 잡는 것이 자연스럽다. 길게 잡으면 이미
     * 무의미해진 행이 오래 남을 뿐이며, 짧게 잡아도 지울 것이 없으면 DELETE 가 0건으로
     * 끝나 비용이 거의 없다.
     *
     * @return 삭제된 행 수. 테스트가 결과를 확인할 수 있도록 돌려준다
     */
    @Scheduled(
            initialDelayString = "${jwt.revoked-token-cleanup-interval:PT10M}",
            fixedDelayString = "${jwt.revoked-token-cleanup-interval:PT10M}")
    @Transactional
    public long purgeExpired() {
        long purged = revokedAccessTokenCommandPort.deleteExpired(LocalDateTime.now(clock));
        if (purged > 0) {
            log.info("만료된 Access 토큰 폐기 기록 {}건을 정리했습니다", purged);
        }
        return purged;
    }
}
