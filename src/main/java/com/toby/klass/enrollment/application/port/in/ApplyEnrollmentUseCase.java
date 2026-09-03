package com.toby.klass.enrollment.application.port.in;

import com.toby.klass.enrollment.application.dto.ApplyEnrollmentCommand;
import com.toby.klass.enrollment.application.dto.EnrollmentResult;

/** 수강 신청. ERD 정본 §4.2. */
public interface ApplyEnrollmentUseCase {

    /**
     * 강의에 신청한다. 상태는 {@code PENDING} 에서 시작하고 좌석을 즉시 점유한다.
     *
     * <p><b>정원이 찼으면 대기열로 자동 분기하지 않는다</b> — 사용자가 원하면
     * {@link RegisterWaitlistUseCase} 를 별도로 호출한다 (ERD 정본 §4.2 4번).
     *
     * @throws com.toby.klass.common.domain.error.BusinessException 강의가 없거나(404),
     *         본인이 개설한 강의이거나(403), 모집 중이 아니거나 중복 신청이거나 정원이 찬 경우(409)
     */
    EnrollmentResult apply(ApplyEnrollmentCommand command);
}
