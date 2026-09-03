package com.toby.klass.spike;

import com.toby.klass.enrollment.domain.Enrollment;
import com.toby.klass.enrollment.domain.EnrollmentStatus;
import java.util.Collection;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 스파이크 ③ — 파생 쿼리 속성 경로가 부트스트랩을 통과하는지 판정한다.
 *
 * <p>{@code Enrollment} 는 {@code klass}/{@code user} 를 {@code @ManyToOne} 으로 들고 있다.
 * 중첩 속성 {@code klass.id} 를 메서드명에서 {@code KlassId} 로 쓰는 것이 통하는지 확인한다.
 * 어긋나면 <b>Hibernate 부트스트랩에서 앱이 통째로 안 뜬다</b> — CLAUDE.md 가 "컴파일러가
 * 잡지 못하는 지점" 2번으로 지목한 자리다.
 *
 * <p>설계서 §4.3 의 {@code enrollmentQueryPort.existsActive(klassId, userId)} 가 이 쿼리로
 * 구현된다. 활성 판정은 {@code PENDING}/{@code CONFIRMED} 두 상태이므로 {@code StatusIn} 이다.
 *
 * <p><b>판정용이며 module-2 에서 실제 리포지토리로 옮긴 뒤 삭제한다.</b>
 */
public interface SpikeEnrollmentRepository extends JpaRepository<Enrollment, Long> {

    boolean existsByKlassIdAndUserIdAndStatusIn(
            Long klassId, Long userId, Collection<EnrollmentStatus> statuses);
}
