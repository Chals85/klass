package com.toby.klass.enrollment.adapter.out.persistence;

import com.toby.klass.common.application.dto.CursorPageResult;
import com.toby.klass.enrollment.application.dto.EnrollmentQuery;
import com.toby.klass.enrollment.application.port.out.EnrollmentCommandPort;
import com.toby.klass.enrollment.application.port.out.EnrollmentQueryPort;
import com.toby.klass.enrollment.domain.Enrollment;
import com.toby.klass.enrollment.domain.EnrollmentStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Repository;

/**
 * 수강 신청 포트 구현.
 *
 * <p>두 리포지토리를 조립한다 — 단건·락은 {@link EnrollmentJpaRepository}, 목록의 동적
 * 조건은 {@link EnrollmentQueryDslRepository}. 서비스는 이 둘의 존재를 모른 채 포트만 본다.
 * {@code KlassRepositoryAdapter} 와 같은 구조다.
 *
 * <p>Design Ref: enrollment-management §2.1, §10.1
 */
@Repository
public class EnrollmentRepositoryAdapter implements EnrollmentCommandPort, EnrollmentQueryPort {

    /**
     * 좌석을 점유하는 상태. <b>{@code uq_enrollment_active} 의 생성 컬럼과 같은 정의여야 한다.</b>
     *
     * <p>생성 컬럼은 {@code status <> 'CANCELLED'} 로 표현돼 있어, 상태가 셋뿐인 지금은 이
     * 집합과 결과가 같다. <b>넷째 상태가 생기면 둘이 갈라진다</b> — 그때 여기와
     * {@code Enrollment.activeUserKey} 의 {@code columnDefinition} 을 함께 고쳐야 한다.
     */
    private static final Set<EnrollmentStatus> ACTIVE_STATUSES =
            Set.of(EnrollmentStatus.PENDING, EnrollmentStatus.CONFIRMED);

    private final EnrollmentJpaRepository jpaRepository;
    private final EnrollmentQueryDslRepository queryDslRepository;

    public EnrollmentRepositoryAdapter(EnrollmentJpaRepository jpaRepository,
                                       EnrollmentQueryDslRepository queryDslRepository) {
        this.jpaRepository = jpaRepository;
        this.queryDslRepository = queryDslRepository;
    }

    @Override
    public Enrollment save(Enrollment enrollment) {
        return jpaRepository.save(enrollment);
    }

    @Override
    public Optional<Enrollment> findById(Long enrollmentId) {
        return jpaRepository.findWithKlassById(enrollmentId);
    }

    @Override
    public Optional<Enrollment> findWithLockById(Long enrollmentId) {
        // 강의를 조인하지 않는 쪽을 부른다. 조인하면 klass 행까지 잠겨 락 순서가 꼬인다
        return jpaRepository.findWithLockById(enrollmentId);
    }

    @Override
    public Optional<Long> findKlassIdById(Long enrollmentId) {
        return jpaRepository.findKlassIdById(enrollmentId);
    }

    @Override
    public boolean existsActive(Long klassId, Long userId) {
        return jpaRepository.existsByKlassIdAndUserIdAndStatusIn(klassId, userId, ACTIVE_STATUSES);
    }

    @Override
    public CursorPageResult<Enrollment> findUserPage(Long userId, EnrollmentQuery query) {
        return CursorPageResult.of(
                queryDslRepository.findUserSlice(
                        userId, query.status(), query.cursor(), query.fetchLimit()),
                query.size(), Enrollment::getId);
    }

    @Override
    public CursorPageResult<Enrollment> findKlassPage(Long klassId, EnrollmentQuery query) {
        return CursorPageResult.of(
                queryDslRepository.findKlassSlice(
                        klassId, query.status(), query.cursor(), query.fetchLimit()),
                query.size(), Enrollment::getId);
    }

    /**
     * {@inheritDoc}
     *
     * <p>커서 페이지가 아니라 <b>단순 상한</b>이다. 배치는 페이지를 넘기지 않고, 남은 것은
     * 다음 실행이 처음부터 다시 집는다 — 그 사이 처리된 건은 어차피 후보에서 빠진다.
     */
    @Override
    public List<Long> findExpiredIds(LocalDateTime now, int limit) {
        return queryDslRepository.findExpiredIds(now, limit);
    }
}
