package com.toby.klass.klass.application.port.out;

import com.toby.klass.common.application.dto.CursorPageResult;
import com.toby.klass.klass.application.dto.KlassQuery;
import com.toby.klass.klass.domain.Klass;
import java.util.Optional;

/**
 * 강의 영속 (읽기).
 *
 * <h2>왜 엔티티를 돌려주는가</h2>
 * 프로젝션 DTO 를 직접 조회하면 빠르지만 <b>도메인 규칙을 우회하게 된다</b> —
 * {@code isVisibleTo}·{@code isOwnedBy} 판정이 엔티티 밖으로 새어나간다. 20건 페이지에서
 * 성능 차이는 측정되지 않으므로 규칙을 지키는 쪽을 택했다 (Design §2.0 Option B 배제 근거).
 *
 * <h2>조회용과 락용이 나뉘어 있다</h2>
 * {@link #findById} 는 개설자를 함께 읽고 락을 잡지 않는다. {@link #findWithLockById} 는
 * 반대다 — 개설자를 읽지 않고 배타 락을 잡는다. 조회 경로까지 락을 잡으면 강의 목록 조회가
 * 신청 트랜잭션과 직렬화되고, 락 조회가 개설자를 조인하면 {@code users} 행까지 잠긴다.
 *
 * <p>klass-management 사이클에서는 락을 <b>일부러 걷어냈다</b> — 직렬화할 상대(수강신청
 * 트랜잭션)가 존재하지 않았기 때문이다. 수강신청 사이클이 그것을 되살렸다 (D-21 해소).
 *
 * <p>Design Ref: §2.4, §9.1 계층 배치, D-21
 */
public interface KlassQueryPort {

    /**
     * 강의 하나를 읽는다. 개설자를 함께 가져온다.
     */
    Optional<Klass> findById(Long klassId);

    /**
     * 강의 행에 <b>배타 락</b>을 걸고 읽는다. 개설자는 함께 읽지 않는다.
     *
     * <p>정원과 관련된 모든 트랜잭션이 이것을 <b>가장 먼저</b> 부른다 (ERD 정본 §4.1).
     * 그 뒤 {@code enrollment} 와 {@code waitlist} 를 어떤 순서로 잡든 데드락이 생기지
     * 않는다 — 모든 경합이 이미 이 한 행에서 직렬화되기 때문이다.
     *
     * <p><b>명령 경로 전용이다.</b> 신청·취소·대기등록은 물론 강의 수정·상태 전이도
     * {@code enrollment_count} 를 읽고 쓰는 read-modify-write 라 이 락 아래에서 해야 한다.
     *
     * <p>Design Ref: ERD 정본 §4.1, Design §4.2, D-21
     */
    Optional<Klass> findWithLockById(Long klassId);

    /**
     * 공개된 강의 목록. {@code DRAFT} 를 제외한다.
     */
    CursorPageResult<Klass> findPublicPage(KlassQuery query);

    /**
     * 특정 개설자의 강의 목록. {@code DRAFT} 를 포함한다.
     */
    CursorPageResult<Klass> findCreatorPage(Long creatorId, KlassQuery query);
}
