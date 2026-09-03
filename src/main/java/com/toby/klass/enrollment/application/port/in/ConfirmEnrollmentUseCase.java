package com.toby.klass.enrollment.application.port.in;

import com.toby.klass.enrollment.application.dto.ConfirmEnrollmentCommand;
import com.toby.klass.enrollment.application.dto.EnrollmentResult;

/** 결제 완료 처리. ERD 정본 §4.3. */
public interface ConfirmEnrollmentUseCase {

    /**
     * {@code PENDING} 을 {@code CONFIRMED} 로 전이한다.
     *
     * <p>좌석 점유 수는 변하지 않는다 — {@code PENDING} 이 이미 점유하고 있었다.
     * 그래서 이 유스케이스만 {@code klass} 락을 잡지 않는다 (ERD 정본 §4.1 예외).
     *
     * @throws com.toby.klass.common.domain.error.BusinessException 신청이 없거나(404),
     *         타인의 것이거나(403), {@code PENDING} 이 아니거나 결제 기한이 지난 경우(409)
     */
    EnrollmentResult confirm(ConfirmEnrollmentCommand command);
}
