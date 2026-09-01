package com.toby.klass.enrollment.adapter.out.persistence;

import com.toby.klass.enrollment.domain.Enrollment;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 수강 신청 영속 접근.
 *
 * <p>1차는 스키마 확정까지다. 내 신청 목록(커서 페이지네이션)·만료 스캔은 2차에서 붙인다.
 *
 * <p>Design Ref: §3.1, §11.3 module-4
 */
public interface EnrollmentJpaRepository extends JpaRepository<Enrollment, Long> {
}
