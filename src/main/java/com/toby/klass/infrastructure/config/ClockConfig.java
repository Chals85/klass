package com.toby.klass.infrastructure.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 시간 소스를 빈으로 등록한다.
 *
 * <p>토큰 만료·회전 시각은 전부 이 {@link Clock} 을 거친다. {@code LocalDateTime.now()} 나
 * {@code Instant.now()} 를 직접 호출하면 "만료된 토큰" 같은 상태를 테스트에서 만들 수 없다.
 * {@code Clock.fixed(...)} 를 주입하면 시간을 원하는 지점에 고정할 수 있다.
 *
 * <h2>왜 시스템 기본 시간대인가</h2>
 * 도메인 엔티티가 {@code LocalDateTime} 을 쓰기 때문이다. {@code LocalDateTime} 은 시간대
 * 정보를 갖지 않으므로 <b>어느 시간대의 벽시계인지</b>를 이 {@code Clock} 이 결정한다.
 * {@code systemUTC()} 로 두면 DB 에 UTC 벽시계가 저장되어, 한국에서 콘솔로 조회할 때
 * 9시간 어긋난 값이 보인다 — {@code LocalDateTime} 을 택한 이유(사람이 읽기 편함)가 사라진다.
 *
 * <p><b>대신 서버 시간대에 의존하게 된다.</b> 배포 환경의 시간대가 바뀌면 이후 저장되는
 * 값의 기준도 바뀌고, 기존 데이터와 해석이 어긋난다. 다국가 서비스라면
 * {@code Instant} + {@code TIMESTAMP WITH TIME ZONE} 이 옳은 선택이다.
 * 이 예제는 단일 시간대를 가정한다.
 *
 * <p>JWT 의 {@code iat}/{@code exp} 는 epoch 초라 절대 시각이 필요하므로
 * {@code NimbusJwtAdapter} 는 이 {@code Clock} 에서 {@code instant()} 를 그대로 쓴다.
 *
 * <p>Design Ref: §10.4 시간 규약, §3.1 Entity Definition
 */
@Configuration
public class ClockConfig {

    /** Spring 이 인스턴스를 만든다. 직접 호출하지 않는다. */
    public ClockConfig() {
    }

    /**
     * 시스템 기본 시간대의 시계를 만든다.
     *
     * @return 시스템 기본 시간대의 시계. {@code LocalDateTime.now(clock)} 의 기준이 된다
     */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
