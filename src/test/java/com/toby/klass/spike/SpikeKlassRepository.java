package com.toby.klass.spike;

import com.toby.klass.klass.domain.Klass;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

/**
 * 스파이크 ① — 설계서 §4.2 1번의 주장을 판정한다.
 *
 * <p>Spring Data 는 {@code find} 와 {@code By} 사이를 설명용으로 보고 무시하므로
 * {@code WithLock} 이 <b>사람에게 보내는 이름표</b>로만 남는다는 것이 설계서의 주장이다.
 * 그 이름이 파생 쿼리로 해석되고 {@code @Lock} 이 {@code FOR UPDATE} 를 만드는지 확인한다.
 *
 * <p><b>{@code @EntityGraph} 를 붙이지 않는다.</b> 조인된 {@code users} 행까지 잠기면
 * ERD 정본 §4.1 의 "락 대상은 {@code klass} 단일 행" 규약이 깨진다 (설계 §4.2 2번).
 * 그것도 함께 판정한다.
 *
 * <p><b>판정용이며 module-2 에서 실제 리포지토리로 옮긴 뒤 삭제한다.</b>
 */
public interface SpikeKlassRepository extends JpaRepository<Klass, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Klass> findWithLockById(Long id);
}
