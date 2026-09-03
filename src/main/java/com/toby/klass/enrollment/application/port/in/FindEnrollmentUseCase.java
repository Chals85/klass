package com.toby.klass.enrollment.application.port.in;

import com.toby.klass.enrollment.application.dto.EnrollmentResult;

/** 신청 상세 조회. 본인 것만 볼 수 있다. */
public interface FindEnrollmentUseCase {

    /**
     * @param requesterId 요청자. {@code enrollment.user_id} 와 일치해야 한다
     * @throws com.toby.klass.common.domain.error.BusinessException 없거나(404) 타인의 것인 경우(403)
     */
    EnrollmentResult findById(Long enrollmentId, Long requesterId);
}
