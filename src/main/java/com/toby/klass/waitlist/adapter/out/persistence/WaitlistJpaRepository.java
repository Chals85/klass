package com.toby.klass.waitlist.adapter.out.persistence;

import com.toby.klass.waitlist.domain.Waitlist;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 대기열 영속 접근.
 *
 * <p>1차는 스키마 확정까지다. 다음 승격 대상 조회는 2차에서 붙인다.
 *
 * <p>Design Ref: §3.1, §11.3 module-4
 */
public interface WaitlistJpaRepository extends JpaRepository<Waitlist, Long> {
}
