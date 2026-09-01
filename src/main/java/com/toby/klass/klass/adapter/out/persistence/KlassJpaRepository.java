package com.toby.klass.klass.adapter.out.persistence;

import com.toby.klass.klass.domain.Klass;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 강의 영속 접근.
 *
 * <p>1차는 스키마 확정이 목적이라 파생 쿼리를 두지 않는다. 목록·상세 조회와 비관적 락
 * ({@code SELECT ... FOR UPDATE})은 동시성 규약과 함께 2차에서 붙인다 (ERD 정본 §4).
 *
 * <p>Design Ref: §3.1, §11.3 module-4
 */
public interface KlassJpaRepository extends JpaRepository<Klass, Long> {
}
