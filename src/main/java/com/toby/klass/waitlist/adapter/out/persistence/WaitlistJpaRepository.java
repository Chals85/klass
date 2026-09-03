package com.toby.klass.waitlist.adapter.out.persistence;

import com.toby.klass.waitlist.domain.Waitlist;
import com.toby.klass.waitlist.domain.WaitlistStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/**
 * 대기열 영속 접근.
 *
 * <h2>{@code position} 이 SQL 예약어인데 파생 쿼리로 다룬다</h2>
 * {@code position} 은 SQL:2016 예약어이자 함수명이다. 그런데 Hibernate 가 별칭을 붙여
 * ({@code w1_0.position}) 인용 없이도 안전하다 — 스파이크에서 실측했다 (Design §4.1.1 ②).
 * {@code Waitlist} javadoc 은 DDL 생성만 확인했다고 적었는데, 조회 경로도 통과한다.
 *
 * <p>Design Ref: enrollment-management §10.1, §4.1.1
 */
public interface WaitlistJpaRepository extends JpaRepository<Waitlist, Long> {

    /** 강의를 함께 읽는다. 응답에 강의 제목이 들어간다. */
    @EntityGraph(attributePaths = "klass")
    Optional<Waitlist> findWithKlassById(Long id);

    /**
     * 대기 행에 배타 락을 걸고 읽는다. 조인하지 않는다 — 포기는 {@code waitlist} 단독 락만
     * 잡는 §4.1 의 예외이므로, 조인으로 {@code klass} 행까지 잠기면 그 예외가 성립하지 않는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Waitlist> findWithLockById(Long id);

    /**
     * 승격할 다음 대기자 1건을 락을 걸고 꺼낸다.
     *
     * <h4>이름의 각 조각이 하는 일</h4>
     * <ul>
     *   <li>{@code First} — {@code find}~{@code By} 사이지만 <b>무시되지 않는다.</b>
     *       Spring Data 가 limit 키워드로 인식해 1건으로 자른다 (스파이크에서
     *       {@code PartTree.isLimiting()} 으로 확인)</li>
     *   <li>{@code WithLock} — 무시된다. 사람에게 보내는 이름표다</li>
     *   <li>{@code OrderByPositionAsc} — 정렬. <b>승격 순서가 이것에 달려 있다</b></li>
     * </ul>
     *
     * <p>{@code idx_waitlist_next(klass_id, status, position)} 가 이 조회를 받는다.
     *
     * @param position 이 값보다 <b>큰</b> 순번부터 찾는다. 부적격자를 건너뛸 때 쓴다
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Waitlist> findFirstWithLockByKlassIdAndStatusAndPositionGreaterThanOrderByPositionAsc(
            Long klassId, WaitlistStatus status, int position);

    /** 같은 강의에 해당 상태의 대기가 있는지 본다. 호출자가 {@code WAITING} 을 넘긴다. */
    boolean existsByKlassIdAndUserIdAndStatus(Long klassId, Long userId, WaitlistStatus status);

    /**
     * 강의의 가장 큰 순번. 대기 행이 없으면 {@code null} 이 나오므로 어댑터가 0 으로 바꾼다.
     *
     * <p>집계는 파생 쿼리로 표현할 수 없어 JPQL 을 쓴다. 속성 경로 두 개
     * ({@code w.position}, {@code w.klass.id})뿐이라 위험이 작고, 틀리면 부트스트랩에서
     * 드러난다.
     */
    @Query("select max(w.position) from Waitlist w where w.klass.id = :klassId")
    Integer findMaxPosition(Long klassId);

    /** 강의의 잔여 대기 전부. 순번 순으로 준다 — 정리 순서가 로그에서 읽히도록. */
    List<Waitlist> findByKlassIdAndStatusOrderByPositionAsc(Long klassId, WaitlistStatus status);
}
