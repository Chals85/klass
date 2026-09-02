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
 * <h2>락 조회 메서드가 없다 — 2차에서 추가된다</h2>
 * 수정·상태 전이는 원래 {@code SELECT ... FOR UPDATE} 로 {@code klass} 행을 잡도록
 * 설계했으나 <b>지금은 막을 상대가 없다.</b> 그 락이 직렬화하려던 대상은 수강신청
 * 트랜잭션(ERD 정본 §4.2)이고, 그것이 아직 존재하지 않는다.
 *
 * <p><b>수강신청을 붙일 때 여기에 {@code findByIdForUpdate} 를 되살려야 한다</b> —
 * {@code changeCapacity} 가 {@code enrollment_count} 를 읽고 {@code capacity} 를 쓰는
 * read-modify-write 라, 신청 트랜잭션과 겹치면 낡은 값으로 판단하게 된다
 * (Design D-21).
 *
 * <p>Design Ref: §2.4, §9.1 계층 배치, D-21
 */
public interface KlassQueryPort {

    /**
     * 강의 하나를 읽는다. 개설자를 함께 가져온다.
     */
    Optional<Klass> findById(Long klassId);

    /**
     * 공개된 강의 목록. {@code DRAFT} 를 제외한다.
     */
    CursorPageResult<Klass> findPublicPage(KlassQuery query);

    /**
     * 특정 개설자의 강의 목록. {@code DRAFT} 를 포함한다.
     */
    CursorPageResult<Klass> findCreatorPage(Long creatorId, KlassQuery query);
}
