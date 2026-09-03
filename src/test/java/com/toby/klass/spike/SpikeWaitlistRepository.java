package com.toby.klass.spike;

import com.toby.klass.waitlist.domain.Waitlist;
import com.toby.klass.waitlist.domain.WaitlistStatus;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

/**
 * 스파이크 ② — 설계서 §13 R-04 를 판정한다.
 *
 * <p>승격 루프(§4.3 ④)는 "{@code WAITING} 중 {@code position} 이 가장 앞선 1건을 락 걸고
 * 꺼낸다"를 요구한다. 이것이 파생 쿼리로 가능한지, 그리고 그 결과인
 * {@code ORDER BY … LIMIT 1 FOR UPDATE} 를 <b>H2 2.4.240 이 거부하지 않는지</b> 확인한다.
 * 거부하면 설계서가 적어 둔 대체안(전건 조회 후 개별 락)으로 간다.
 *
 * <p><b>{@code position} 은 SQL 예약어이자 함수명이다.</b> {@code Waitlist} javadoc 이
 * "H2 에서 큰따옴표 없이 쓸 수 있음을 module-4 에서 확인했다"고 적었지만, 그때는 DDL
 * 생성만 확인했고 <b>파생 쿼리가 그것을 컬럼으로 인용하는 경로</b>는 통과한 적이 없다.
 *
 * <p><b>판정용이며 module-2 에서 실제 리포지토리로 옮긴 뒤 삭제한다.</b>
 */
public interface SpikeWaitlistRepository extends JpaRepository<Waitlist, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Waitlist> findFirstWithLockByKlassIdAndStatusAndPositionGreaterThanOrderByPositionAsc(
            Long klassId, WaitlistStatus status, int position);
}
