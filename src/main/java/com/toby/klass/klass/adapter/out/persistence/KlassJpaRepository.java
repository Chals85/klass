package com.toby.klass.klass.adapter.out.persistence;

import com.toby.klass.klass.domain.Klass;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 강의 영속 접근 (단건).
 *
 * <h2>{@code @Query} 를 쓰지 않는다 — 이름만 제대로 지으면 파생 쿼리로 충분하다</h2>
 * Spring Data 는 <b>{@code find} 와 {@code By} 사이</b>의 텍스트를 설명용으로 보고 무시한다.
 * 따라서 {@code findWithLockById} 는 {@code findById} 와 같은 파생 쿼리로 해석되고,
 * {@code WithLock} 은 <b>사람에게 락이 걸린다는 사실을 알리는 이름표</b>로만 남는다.
 *
 * <p>이름을 {@code findByIdForUpdate} 로 지으면 {@code ForUpdate} 가 {@code By} <b>뒤</b>,
 * 즉 속성 경로 자리에 놓여 {@code id.forUpdate} 를 찾다가 부트스트랩에서 깨진다. 같은 뜻을
 * 담은 두 이름의 운명이 갈리는 지점이라 <b>어느 쪽에 수식어를 두는지가 규칙</b>이다.
 *
 * <p>JPQL 문자열을 쓰지 않으면 CLAUDE.md 가 지목한 "컴파일러가 잡지 못하는 지점" 중
 * 첫 번째(JPQL 문자열)를 통째로 피할 수 있다. 남는 문자열은 {@code @EntityGraph} 의
 * 속성명 하나뿐이다.
 *
 * <h2>락 조회 메서드가 없다 — 2차에서 추가된다</h2>
 * 수강신청이 붙으면 {@code @Lock(PESSIMISTIC_WRITE)} 를 붙인 {@code findWithLockById} 가
 * 여기 들어온다. <b>그때 {@code @EntityGraph} 를 함께 붙이면 안 된다</b> — 조인된
 * {@code users} 행까지 잠겨 ERD 정본 §4.1 의 "락 대상은 {@code klass} 단일 행" 규약이
 * 깨진다 (Design D-21).
 *
 * <p>목록 조회의 동적 조건은 {@link KlassQueryDslRepository} 가 담당한다 — 조합이 여섯
 * 갈래로 늘어나 파생 쿼리로는 감당되지 않기 때문이다 (Design §2.0).
 *
 * <p>Design Ref: §2.4, ERD 정본 §4.1 락 획득 순서, D-21
 */
public interface KlassJpaRepository extends JpaRepository<Klass, Long> {

    /**
     * 개설자를 함께 읽는다.
     *
     * <p>상세 응답에 개설자명이 들어가는데 {@code Klass.creator} 는 {@code LAZY} 라 그대로
     * 두면 조회가 두 번 나간다. {@code @EntityGraph} 가 fetch join 을 만들어 한 번으로 줄인다.
     *
     * <p><b>{@code findById} 를 오버라이드하지 않은 이유</b>: 그러면 <b>모든</b> 단건 조회가
     * 개설자를 끌고 온다. 2차에서 들어올 락 조회는 개설자가 필요 없으므로(위 참조)
     * 조인 여부를 메서드로 나눠 둔다.
     */
    @EntityGraph(attributePaths = "creator")
    Optional<Klass> findWithCreatorById(Long id);

}
