package com.toby.klass.enrollment.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 수강신청 정책 값.
 *
 * <h2>{@code infrastructure} 가 아니라 여기 있는 이유</h2>
 * 이 저장소의 기존 {@code @ConfigurationProperties} 둘({@code JwtProperties},
 * {@code DefaultUserProperties})은 {@code infrastructure/} 아래에 있는데, 그것들은
 * <b>어댑터와 부트스트랩이</b> 소비한다. 이것은 <b>서비스가</b> 소비하므로 그쪽에 두면
 * {@code application.service → infrastructure} 계층 역행이 생긴다.
 *
 * <p>배치 규칙은 <b>소비자가 있는 계층에 둔다</b>로 정한다 (Design D-41).
 * {@code application.service} 는 이미 {@code @Service}·{@code @Transactional} 로 Spring 을
 * 알고 있으므로 새 위반이 아니다.
 *
 * <h2>⚠️ 프로퍼티를 빠뜨리면 기동은 성공하고 첫 신청에서 NPE 다</h2>
 * 중첩 record 는 해당 블록이 {@code application.yml} 에 없으면 <b>예외가 아니라
 * {@code null}</b> 로 바인딩된다. 스파이크에서 실측했다 (Design §4.1.1 ⑤).
 * {@code app.enrollment.pending-expiry} 를 반드시 채워야 한다.
 *
 * <p>{@code @ConfigurationPropertiesScan} 이 {@code KlassApplication} 에 있어 자동
 * 등록된다 — <b>그 어노테이션이 없으면 기동이 통째로 실패한다.</b>
 *
 * <p>Design Ref: enrollment-management §5, D-41
 *
 * @param defaultCancellationPeriodDays 강의가 {@code cancellation_period_days} 를 지정하지
 *                                      않았을 때 쓰는 전역 기본값. 결제일 기준 일수다
 * @param pendingExpiry                 결제 대기 만료 기한. 출처별로 다르다
 */
@ConfigurationProperties(prefix = "app.enrollment")
public record EnrollmentProperties(int defaultCancellationPeriodDays,
                                   PendingExpiry pendingExpiry) {

    /**
     * 출처별 {@code PENDING} 만료 기한 (ERD 정본 §2 ⑥).
     *
     * <p><b>이 사이클은 만료 회수를 구현하지 않는다</b> (Design D-32). 그래도 값을 채우는
     * 이유는 두 가지다 — {@code ck_enrollment_pending} 이 {@code PENDING} 에
     * {@code expires_at} 을 강제하고, 결제 확정이 그 시각을 넘겼는지 검사한다
     * ({@code Enrollment.confirm}). 외부 배치가 붙으면 즉시 동작한다.
     *
     * @param direct   직접 신청. 결제 수단 준비 시간을 고려해 여유를 둔다
     * @param waitlist 대기열 승격. 이미 알림을 받고 기다리던 상태이므로 짧게 잡아 뒷 순번을
     *                 오래 붙잡지 않는다
     */
    public record PendingExpiry(Duration direct, Duration waitlist) {
    }
}
