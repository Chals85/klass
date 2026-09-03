package com.toby.klass.enrollment.application.port.out;

import com.toby.klass.common.application.dto.CursorPageResult;
import com.toby.klass.enrollment.application.dto.EnrollmentQuery;
import com.toby.klass.enrollment.domain.Enrollment;
import java.util.Optional;

/**
 * 수강 신청 영속 (읽기).
 *
 * <h2>세 가지 단건 조회가 나뉘어 있다</h2>
 * 같은 행을 읽지만 <b>락 여부와 조인 범위가 다르다.</b> 하나로 합치면 취소 경로가 필요 없는
 * 조인을 끌고 오거나, 조회 경로가 락을 잡아 신청 트랜잭션과 직렬화된다.
 *
 * <table border="1">
 *   <caption>단건 조회의 갈래</caption>
 *   <tr><th>메서드</th><th>락</th><th>조인</th><th>쓰이는 곳</th></tr>
 *   <tr><td>{@link #findById}</td><td>없음</td><td>강의</td><td>상세 조회</td></tr>
 *   <tr><td>{@link #findWithLockById}</td><td>배타</td><td>없음</td><td>결제 확정·취소</td></tr>
 *   <tr><td>{@link #findKlassIdById}</td><td>없음</td><td>없음</td><td>취소의 락 순서 확보</td></tr>
 * </table>
 *
 * <p>Design Ref: enrollment-management §4.3, §10.1
 */
public interface EnrollmentQueryPort {

    /**
     * 신청 하나를 읽는다. 강의를 함께 가져온다 — 상세 응답에 강의 제목과
     * {@code isCancellable} 이 들어가기 때문이다.
     */
    Optional<Enrollment> findById(Long enrollmentId);

    /**
     * 신청 행에 <b>배타 락</b>을 걸고 읽는다. 강의를 조인하지 않는다.
     *
     * <p>결제 확정은 이 락 <b>하나만</b> 잡는다 — {@code PENDING} 이 이미 좌석을 점유하고
     * 있어 카운터가 변하지 않으므로 {@code klass} 락이 불필요하고, 락을 하나만 잡은 뒤
     * 아무것도 더 잡지 않으므로 순환 대기가 성립하지 않는다 (ERD 정본 §4.1 예외).
     *
     * <p>취소는 <b>{@code klass} 락을 먼저 잡은 뒤</b> 이것을 잡는다. 순서를 뒤집으면 §4.1
     * 규약이 깨져 데드락이 생긴다.
     */
    Optional<Enrollment> findWithLockById(Long enrollmentId);

    /**
     * 신청이 속한 강의 id 만 읽는다. <b>락을 잡지 않는다.</b>
     *
     * <h4>왜 이런 메서드가 필요한가</h4>
     * 취소는 {@code klass} 를 먼저 잠가야 하는데(§4.1), 어느 강의인지는 {@code enrollment}
     * 를 봐야 안다. 순서를 지키려면 <b>락 없이 소속만 먼저 알아내는</b> 단계가 필요하다
     * (ERD 정본 §4.4 0번).
     *
     * <p>이 조회와 락 획득 사이에 강의가 바뀔 수는 없다 — {@code enrollment.klass_id} 는
     * 생성 후 변경되지 않는다.
     */
    Optional<Long> findKlassIdById(Long enrollmentId);

    /**
     * 같은 강의에 <b>활성</b> 신청이 있는지 판별한다. {@code PENDING} 과 {@code CONFIRMED} 만 센다.
     *
     * <p>{@code CANCELLED} 를 세지 않으므로 취소 후 재신청이 가능하다 —
     * {@code uq_enrollment_active} 의 생성 컬럼과 <b>같은 정의</b>여야 한다. 어긋나면 앱은
     * 통과시키고 DB 가 거부하거나 그 반대가 된다.
     *
     * <p>신청(§4.2 3번)과 대기열 등록(§4.5 3번) 양쪽에서 부른다. 후자가 없으면 이미
     * {@code CONFIRMED} 인 사용자가 대기열에 등록되어 순번을 차지한다.
     */
    boolean existsActive(Long klassId, Long userId);

    /** 내 신청 목록. 강의를 fetch join 한다. */
    CursorPageResult<Enrollment> findUserPage(Long userId, EnrollmentQuery query);

    /** 강의별 수강생 목록 (크리에이터 전용). 수강생을 fetch join 한다. */
    CursorPageResult<Enrollment> findKlassPage(Long klassId, EnrollmentQuery query);
}
