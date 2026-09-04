package com.toby.klass.enrollment.adapter.in.scheduler;

import com.toby.klass.enrollment.application.port.in.ReapExpiredEnrollmentUseCase;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 만료된 결제 대기 신청을 주기적으로 회수한다.
 *
 * <h2>왜 인바운드 어댑터인가</h2>
 * 스케줄러는 시스템을 <b>바깥에서 구동하는 주체</b>이므로 헥사고날의 driving adapter 다.
 * {@code adapter.in → port.in} 규칙이 그대로 적용되며, 그래서 이 클래스는
 * {@link ReapExpiredEnrollmentUseCase} <b>하나만</b> 안다 — 저장소 포트도, {@code Clock} 도,
 * {@code EnrollmentService} 도 모른다.
 *
 * <p>auth 의 {@code RevokedAccessTokenCleaner} 는 {@code application/service/} 에 있어
 * 위치가 다르다. {@code pending-expiry-reaper} 사이클이 선례를 바꾼 것이며 그쪽은 옮기지
 * 않았다 (Design D-48 · D-52).
 *
 * <h2>왜 트랜잭션이 없는가</h2>
 * 회수는 <b>건별 독립 트랜잭션</b>이다. 이 메서드에 {@code @Transactional} 을 걸면 사이클
 * 전체가 한 트랜잭션이 되어 한 건의 실패가 전부를 롤백하고, 여러 {@code klass} 행을 동시에
 * 오래 잠근다 (Design FR-07).
 *
 * <h2>왜 별도 빈인가 — 조용히 깨지는 자리</h2>
 * {@code @Scheduled} 메서드에서 <b>같은 클래스</b>의 {@code @Transactional} 메서드를 부르면
 * 프록시를 타지 않아 <b>트랜잭션이 걸리지 않는다.</b> 컴파일도 테스트도 통과하고 배치도
 * 도는데 롤백만 안 된다. 진입점과 처리 메서드를 다른 빈에 두면 그 실수가 애초에 불가능하다.
 *
 * <h2>{@code Clock} 을 주입받지 않는다</h2>
 * 만료 판정의 기준 시각은 <b>도메인 규칙</b>이므로 유스케이스가 소유한다. 어댑터가 시각을
 * 만들어 넘기면 "언제부터 만료인가"를 바깥 계층이 정하게 된다.
 *
 * <h2>단일 인스턴스를 전제로 한다</h2>
 * 여러 대로 확장하면 같은 대상을 동시에 집는다. {@code klass} 행 락이 직렬화하고 회수 직전
 * 재확인이 중복 처리를 막으므로 <b>정합성은 깨지지 않지만</b> 불필요한 경합이 생긴다.
 * 실서비스에서는 ShedLock 같은 분산 락이 필요하다 ({@code RevokedAccessTokenCleaner} 와
 * 같은 전제다).
 *
 * <p>Design Ref: pending-expiry-reaper §2.1 · §5.1, D-48 · D-52
 */
@Component
public class ExpiredEnrollmentScheduler {

    private static final Logger log = LoggerFactory.getLogger(ExpiredEnrollmentScheduler.class);

    private final ReapExpiredEnrollmentUseCase reapExpiredEnrollmentUseCase;

    public ExpiredEnrollmentScheduler(ReapExpiredEnrollmentUseCase reapExpiredEnrollmentUseCase) {
        this.reapExpiredEnrollmentUseCase = reapExpiredEnrollmentUseCase;
    }

    /**
     * 만료 후보를 훑고 건별로 회수한다.
     *
     * <p><b>{@code fixedDelay} 를 쓴다</b>({@code fixedRate} 가 아니라). 이전 실행이 끝난
     * 뒤부터 간격을 재므로 회수가 느려져도 실행이 겹쳐 쌓이지 않는다.
     *
     * <p><b>{@code initialDelay} 를 주기와 같게 두는 것이 테스트 격리 장치다.</b>
     * {@code @SpringBootTest} 가 컨텍스트를 띄워도 10분 안에는 배치가 돌지 않으므로 통합
     * 테스트에 끼어들지 않는다. 테스트는 회수 메서드를 직접 호출해 검증한다.
     *
     * <p><b>{@code catch (Exception)} 으로 넓게 잡는다.</b> 좁게 잡으면 예상 못 한 예외
     * 하나가 남은 대상 전부를 미처리로 만든다. 배치 루프는 어떤 예외에도 다음 건으로
     * 넘어가야 한다.
     *
     * <p>회수 0건이면 로그를 남기지 않는다 — 회수할 것이 없는 것은 정상이며, 남기면 정상
     * 동작이 로그로 쌓인다 (선례와 동일).
     */
    @Scheduled(
            initialDelayString = "${app.enrollment.reap-interval:PT10M}",
            fixedDelayString = "${app.enrollment.reap-interval:PT10M}")
    public void reap() {
        List<Long> targets = reapExpiredEnrollmentUseCase.findExpiredTargets();

        int reaped = 0;
        for (Long enrollmentId : targets) {
            try {
                if (reapExpiredEnrollmentUseCase.reapExpired(enrollmentId)) {
                    reaped++;
                }
            } catch (Exception e) {
                log.warn("만료 신청 회수 실패. enrollmentId={}", enrollmentId, e);
            }
        }

        if (reaped > 0) {
            log.info("만료된 수강신청 {}건을 회수했습니다 (후보 {}건)", reaped, targets.size());
        }
    }
}
