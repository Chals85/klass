package com.toby.klass.enrollment.application.port.in;

import com.toby.klass.enrollment.application.dto.CancelEnrollmentCommand;
import com.toby.klass.enrollment.application.dto.EnrollmentResult;

/** 수강 취소. 좌석 반납과 대기열 승격을 한 트랜잭션으로 끝낸다. ERD 정본 §4.4. */
public interface CancelEnrollmentUseCase {

    /**
     * 신청을 취소하고 좌석을 반납한다. 강의가 {@code OPEN} 이고 대기자가 있으면
     * <b>같은 트랜잭션에서 1건을 승격</b>한다.
     *
     * <p>승격이 일어나면 좌석 점유 수의 <b>순변화가 0</b> 이다 — 반납된 자리가 일반
     * 신청자에게 노출되는 틈 없이 대기자에게 이전된다.
     *
     * @throws com.toby.klass.common.domain.error.BusinessException 신청이 없거나(404),
     *         타인의 것이거나(403), 이미 종착이거나 강의가 끝났거나 취소 기간이 지난 경우(409)
     */
    EnrollmentResult cancel(CancelEnrollmentCommand command);
}
