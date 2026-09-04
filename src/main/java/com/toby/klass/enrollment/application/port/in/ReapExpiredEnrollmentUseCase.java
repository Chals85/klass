package com.toby.klass.enrollment.application.port.in;

import java.util.List;

/**
 * 만료된 결제 대기 신청 회수.
 *
 * <h2>메서드가 둘인 이유</h2>
 * 후보 조회는 <b>락 없이</b> 읽어야 하고 회수는 <b>건별 트랜잭션</b>이어야 한다. 하나로
 * 합치면 한 번의 실행 전체가 한 트랜잭션이 되어 한 건의 실패가 전부를 롤백하고, 여러
 * {@code klass} 행을 동시에 오래 잠근다 (Design FR-07).
 *
 * <h2>스케줄러가 이 포트만 본다</h2>
 * {@code adapter.in} 은 {@code port.in} 만 의존할 수 있다. 후보 조회를 여기 두지 않으면
 * 스케줄러가 {@code EnrollmentQueryPort}(out 포트)를 직접 주입해야 해서 계층 규칙이
 * 깨진다 (Design D-48).
 *
 * <p>Design Ref: pending-expiry-reaper §5.2
 */
public interface ReapExpiredEnrollmentUseCase {

    /**
     * 회수 대상 id 를 읽는다. <b>락을 잡지 않으며 엔티티를 로딩하지 않는다.</b>
     *
     * <p>id 만 돌려주는 이유: 실제 처리는 건별 트랜잭션에서 <b>락을 걸고 다시 읽어야</b>
     * 하므로, 여기서 엔티티를 들고 가봐야 그 사이 낡은 값이 된다.
     *
     * <p>한 번의 실행에 상한이 있다({@code app.enrollment.reap-batch-size}). 남은 것은 다음
     * 실행이 가져가므로 만료가 폭증해도 한 번의 실행이 길어지지 않는다 (Design D-50).
     *
     * @return 만료가 오래된 순서의 신청 id. 없으면 빈 목록
     */
    List<Long> findExpiredTargets();

    /**
     * 한 건을 회수한다. 호출마다 <b>독립 트랜잭션</b>이다.
     *
     * <p>락을 잡은 뒤 상태를 <b>재확인</b>한다 — 후보 조회 시점과 이 시점 사이에 사용자가
     * 결제를 마쳤거나 스스로 취소했을 수 있다. 그 경우 아무것도 하지 않고 {@code false} 를
     * 돌려준다. <b>예외가 아니다</b> — 정상적인 경합 결과이므로 로그도 남기지 않는다.
     *
     * @return 실제로 회수했으면 {@code true}
     */
    boolean reapExpired(Long enrollmentId);
}
