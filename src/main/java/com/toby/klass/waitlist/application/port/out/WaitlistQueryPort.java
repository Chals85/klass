package com.toby.klass.waitlist.application.port.out;

import com.toby.klass.common.application.dto.CursorPageResult;
import com.toby.klass.waitlist.application.dto.WaitlistQuery;
import com.toby.klass.waitlist.domain.Waitlist;
import java.util.List;
import java.util.Optional;

/**
 * 대기열 영속 (읽기).
 *
 * <h2>이 포트를 {@code EnrollmentService} 가 쓴다</h2>
 * {@code waitlist} 패키지에는 서비스가 없다. 좌석을 건드리는 유스케이스가 전부 한 서비스에
 * 모여 있기 때문이다(D-29) — ERD 정본 §4.1 이 {@code klass} 행을 트랜잭션 경계의 루트로
 * 지정했으므로 세 테이블은 논리적으로 하나의 애그리거트다.
 *
 * <p>조회 조건은 <b>자기 패키지의 {@code WaitlistQuery}</b> 를 쓴다. 처음에는
 * {@code EnrollmentQuery} 를 재사용했는데, 대기 목록이 쓰지도 않는 {@code status} 필드
 * 하나 때문에 이 포트가 {@code enrollment} 패키지를 경유하게 됐다 (D-46).
 *
 * <p>Design Ref: enrollment-management §4.3, §10.1, D-29
 */
public interface WaitlistQueryPort {

    /**
     * 대기 하나를 읽는다. 강의를 함께 가져온다 — 응답에 강의 제목이 들어간다.
     */
    Optional<Waitlist> findById(Long waitlistId);

    /**
     * 대기 행에 <b>배타 락</b>을 걸고 읽는다.
     *
     * <p>대기 포기는 이 락 <b>하나만</b> 잡는다 — {@code enrollment_count} 를 건드리지 않고
     * 그 뒤 아무것도 더 잡지 않으므로 순환 대기가 성립하지 않는다 (ERD 정본 §4.1 예외).
     * 인기 강의에서 대기 포기가 신청 트랜잭션과 직렬화되는 비용을 피한다.
     */
    Optional<Waitlist> findWithLockById(Long waitlistId);

    /**
     * 승격할 다음 대기자를 <b>락을 걸고</b> 하나 꺼낸다. {@code position} 오름차순이다.
     *
     * <h4>{@code afterPosition} 이 있는 이유</h4>
     * 승격 루프는 부적격 대기자(비활성 계정·이미 신청함·개설자 본인)를 만나면 그 행을
     * {@code CANCELLED} 로 정리하고 <b>다음 순번을 다시 찾는다.</b> 방금 본 순번을 넘겨
     * 그 뒤부터 찾게 한다. 0 을 넘기면 처음부터다.
     *
     * <h4>{@code FOR UPDATE} + 1건 제한의 함정</h4>
     * 이 조합은 대상 행이 다른 트랜잭션에 잠겨 있을 때 대기했다가 <b>낡은 행을 돌려줄 수
     * 있다.</b> 큐 패턴에서 보통 {@code SKIP LOCKED} 를 권하는 이유다. <b>여기서 안전한
     * 것은 호출자가 {@code klass} 락을 이미 잡고 있어 같은 강의에 대해 두 트랜잭션이 동시에
     * 승격 대상을 고를 수 없기 때문뿐이다.</b> 최적화를 이유로 {@code klass} 락을 걷어내면
     * 이 함정이 즉시 열린다.
     *
     * <p>실제로 나가는 SQL 을 스파이크에서 확인했다 (Design §4.1.1 ③).
     *
     * <pre>
     * ... where klass_id=? and status=? and position&gt;?
     * order by position fetch first ? rows only for update
     * </pre>
     */
    Optional<Waitlist> findNextWaitingWithLock(Long klassId, int afterPosition);

    /** 같은 강의에 {@code WAITING} 인 대기가 있는지 판별한다. 중복 등록을 막는다. */
    boolean existsWaiting(Long klassId, Long userId);

    /**
     * 강의의 가장 큰 순번. 대기가 없으면 0 이다.
     *
     * <p>다음 순번은 이 값 + 1 이다. <b>{@code klass} 락 하위에서만 안전하다</b> — 락 없이
     * 부르면 두 요청이 같은 값을 읽어 순번이 충돌하고, {@code uq_waitlist_position} 이
     * 최종 거부한다.
     *
     * <p>취소된 대기의 순번은 재사용하지 않고 <b>gap 으로 남긴다</b>. 재배열은 여러 행을
     * 갱신해 락 범위를 넓히고, 순번의 절대값이 사용자에게 의미를 갖지 않는다.
     */
    int maxPosition(Long klassId);

    /**
     * 강의의 잔여 {@code WAITING} 전부. 강의 마감 시 일괄 정리에 쓴다.
     *
     * <p>벌크 UPDATE 대신 이것을 쓰는 근거는 {@link WaitlistCommandPort} 참조.
     */
    List<Waitlist> findAllWaiting(Long klassId);

    /** 내 대기 목록. 강의를 fetch join 한다. */
    CursorPageResult<Waitlist> findUserPage(Long userId, WaitlistQuery query);
}
