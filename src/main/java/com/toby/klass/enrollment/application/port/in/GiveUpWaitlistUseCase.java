package com.toby.klass.enrollment.application.port.in;

import com.toby.klass.enrollment.application.dto.GiveUpWaitlistCommand;
import com.toby.klass.enrollment.application.dto.WaitlistResult;

/** 대기 포기. ERD 정본 §4.9. */
public interface GiveUpWaitlistUseCase {

    /**
     * 대기를 포기한다. {@code waitlist} 단독 행 락만 잡는다 (ERD 정본 §4.1 예외) —
     * 좌석 점유 수를 건드리지 않으므로 인기 강의에서 신청 트랜잭션과 직렬화되지 않는다.
     *
     * @throws com.toby.klass.common.domain.error.BusinessException 대기가 없거나(404),
     *         타인의 것이거나(403), 이미 승격·포기된 경우(409)
     */
    WaitlistResult giveUp(GiveUpWaitlistCommand command);
}
