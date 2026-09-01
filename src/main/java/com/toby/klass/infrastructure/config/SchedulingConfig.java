package com.toby.klass.infrastructure.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 스케줄링 활성화.
 *
 * <p>{@code @EnableScheduling} 을 메인 클래스가 아니라 별도 설정에 둔다. 테스트에서
 * 이 설정만 빼면 스케줄러 없이 컨텍스트를 띄울 수 있어, 백그라운드 작업이 테스트
 * 실행 중에 끼어드는 것을 막을 수 있다.
 *
 * <p>현재 등록된 작업은 {@code RevokedAccessTokenCleaner} 하나다.
 *
 * <p>Design Ref: §2.2 로그아웃 흐름
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {

    /** Spring 이 인스턴스를 만든다. 직접 호출하지 않는다. */
    public SchedulingConfig() {
    }
}
