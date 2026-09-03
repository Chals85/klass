package com.toby.klass.enrollment.adapter.out.persistence;

import com.toby.klass.enrollment.domain.Enrollment;
import com.toby.klass.enrollment.domain.EnrollmentStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/**
 * 수강 신청 영속 접근 (단건).
 *
 * <h2>파생 쿼리로 쓴다 — 이름이 곧 쿼리다</h2>
 * {@code @Query} 문자열은 CLAUDE.md 가 지목한 "컴파일러가 잡지 못하는 지점" 1번이고, 틀리면
 * <b>Hibernate 부트스트랩에서 앱이 통째로 안 뜬다.</b> 파생 쿼리는 속성 경로가 틀려도 같은
 * 시점에 깨지지만 <b>이름 자체가 명세</b>라 읽으면 검증된다.
 *
 * <p>예외가 하나 있다 — {@link #findKlassIdById} 는 컬럼 하나만 뽑아야 해서 파생 쿼리로
 * 표현할 수 없다. 그쪽은 짧은 JPQL 을 쓰되 속성 경로 두 개뿐이라 위험이 작다.
 *
 * <h2>락 조회에 이름표를 붙이는 규칙</h2>
 * Spring Data 는 {@code find} 와 {@code By} 사이를 무시하므로 {@code WithLock} 이 사람에게
 * 락을 알린다. {@code ForUpdate} 처럼 {@code By} <b>뒤</b>에 두면 속성 경로로 해석돼 깨진다
 * (Design §4.1.1 ④ 에서 실측).
 *
 * <p>Design Ref: enrollment-management §10.1, §4.1.1
 */
public interface EnrollmentJpaRepository extends JpaRepository<Enrollment, Long> {

    /**
     * 강의를 함께 읽는다. 상세 응답에 강의 제목과 {@code isCancellable} 이 들어가는데
     * {@code Enrollment.klass} 는 {@code LAZY} 라 그대로 두면 조회가 두 번 나간다.
     */
    @EntityGraph(attributePaths = "klass")
    Optional<Enrollment> findWithKlassById(Long id);

    /**
     * 신청 행에 배타 락을 걸고 읽는다.
     *
     * <p><b>{@code @EntityGraph} 를 붙이지 않는다</b> — 조인된 {@code klass} 행까지 잠기면
     * 락 획득 순서(§4.1)가 뒤집힌다. 취소 경로는 {@code klass} 를 <b>먼저</b> 잠그는데,
     * 여기서 다시 잠그려 들면 순서가 꼬인다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Enrollment> findWithLockById(Long id);

    /**
     * 소속 강의 id 만 읽는다. 락도 조인도 없다.
     *
     * <p>취소가 {@code klass} 를 먼저 잠그려면 어느 강의인지 알아야 하는데, 그것 하나 때문에
     * 엔티티 전체를 로딩하거나 락을 잡을 이유가 없다 (ERD 정본 §4.4 0번).
     *
     * <p><b>속성 경로 두 개가 문자열이다</b> — {@code e.klass.id} 와 {@code e.id}. 필드명을
     * 바꾸면 여기가 함께 깨지되 부트스트랩에서 드러난다.
     */
    @Query("select e.klass.id from Enrollment e where e.id = :id")
    Optional<Long> findKlassIdById(Long id);

    /**
     * 같은 강의에 특정 상태의 신청이 있는지 본다.
     *
     * <p>호출자가 활성 상태 집합({@code PENDING}, {@code CONFIRMED})을 넘긴다 —
     * {@code uq_enrollment_active} 의 생성 컬럼과 같은 정의를 유지하기 위해서다.
     *
     * <p>메서드명의 {@code KlassId}·{@code UserId} 는 {@code @ManyToOne} 연관의 중첩 속성
     * ({@code klass.id}, {@code user.id})으로 해석된다. 스파이크에서 실측했다.
     */
    boolean existsByKlassIdAndUserIdAndStatusIn(
            Long klassId, Long userId, Collection<EnrollmentStatus> statuses);
}
