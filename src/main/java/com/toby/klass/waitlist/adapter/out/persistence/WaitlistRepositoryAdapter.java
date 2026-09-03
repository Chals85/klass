package com.toby.klass.waitlist.adapter.out.persistence;

import com.toby.klass.common.application.dto.CursorPageResult;
import com.toby.klass.enrollment.application.dto.EnrollmentQuery;
import com.toby.klass.waitlist.application.port.out.WaitlistCommandPort;
import com.toby.klass.waitlist.application.port.out.WaitlistQueryPort;
import com.toby.klass.waitlist.domain.Waitlist;
import com.toby.klass.waitlist.domain.WaitlistStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 대기열 포트 구현.
 *
 * <p>{@code WAITING} 이라는 상수를 여기서만 다룬다 — 포트 시그니처에 상태를 노출하면
 * 호출자가 "{@code PROMOTED} 를 넘기면 어떻게 되나"를 물을 수 있게 된다. 활성 대기의
 * 정의는 어댑터 안에 가둔다.
 *
 * <p>Design Ref: enrollment-management §2.1, §10.1
 */
@Repository
public class WaitlistRepositoryAdapter implements WaitlistCommandPort, WaitlistQueryPort {

    private final WaitlistJpaRepository jpaRepository;
    private final WaitlistQueryDslRepository queryDslRepository;

    public WaitlistRepositoryAdapter(WaitlistJpaRepository jpaRepository,
                                     WaitlistQueryDslRepository queryDslRepository) {
        this.jpaRepository = jpaRepository;
        this.queryDslRepository = queryDslRepository;
    }

    @Override
    public Waitlist save(Waitlist waitlist) {
        return jpaRepository.save(waitlist);
    }

    @Override
    public Optional<Waitlist> findById(Long waitlistId) {
        return jpaRepository.findWithKlassById(waitlistId);
    }

    @Override
    public Optional<Waitlist> findWithLockById(Long waitlistId) {
        return jpaRepository.findWithLockById(waitlistId);
    }

    @Override
    public Optional<Waitlist> findNextWaitingWithLock(Long klassId, int afterPosition) {
        return jpaRepository
                .findFirstWithLockByKlassIdAndStatusAndPositionGreaterThanOrderByPositionAsc(
                        klassId, WaitlistStatus.WAITING, afterPosition);
    }

    @Override
    public boolean existsWaiting(Long klassId, Long userId) {
        return jpaRepository.existsByKlassIdAndUserIdAndStatus(
                klassId, userId, WaitlistStatus.WAITING);
    }

    @Override
    public int maxPosition(Long klassId) {
        // 대기 행이 없으면 max() 가 null 을 돌려준다. 다음 순번을 1 로 만들려면 0 이어야 한다
        Integer max = jpaRepository.findMaxPosition(klassId);
        return max == null ? 0 : max;
    }

    @Override
    public List<Waitlist> findAllWaiting(Long klassId) {
        return jpaRepository.findByKlassIdAndStatusOrderByPositionAsc(
                klassId, WaitlistStatus.WAITING);
    }

    @Override
    public CursorPageResult<Waitlist> findUserPage(Long userId, EnrollmentQuery query) {
        return CursorPageResult.of(
                queryDslRepository.findUserSlice(userId, query.cursor(), query.fetchLimit()),
                query.size(), Waitlist::getId);
    }
}
