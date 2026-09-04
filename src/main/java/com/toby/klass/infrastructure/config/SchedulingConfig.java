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
 * <p>등록된 작업은 둘이다.
 *
 * <ul>
 *   <li>{@code RevokedAccessTokenCleaner} — 만료된 Access 토큰 폐기 기록 정리
 *       ({@code application/service/})</li>
 *   <li>{@code ExpiredEnrollmentScheduler} — 만료된 결제 대기 신청 회수
 *       ({@code adapter/in/scheduler/})</li>
 * </ul>
 *
 * <p><b>둘의 위치가 다르다.</b> 스케줄러는 시스템을 바깥에서 구동하는 driving adapter 이므로
 * {@code adapter/in/} 이 맞다는 판단이 {@code pending-expiry-reaper} 사이클에서 확정됐다
 * (pending-expiry-reaper D-48 · D-52). auth 쪽은 동작이 같고 변경 자체가 비용이라 옮기지
 * 않았다 — 다음에 그쪽을 손볼 때 함께 정리한다.
 *
 * <p>Design Ref: §2.2 로그아웃 흐름
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {

}
