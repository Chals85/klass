package com.toby.klass.enrollment.application.port.in;

import com.toby.klass.enrollment.application.dto.RegisterWaitlistCommand;
import com.toby.klass.enrollment.application.dto.WaitlistResult;

/** 대기열 등록. 신청의 하위 분기가 아니라 독립 유스케이스다. ERD 정본 §4.5. */
public interface RegisterWaitlistUseCase {

    /**
     * 대기열에 등록한다. <b>자리가 남아 있으면 거부한다</b> — 승격은 좌석 반납 경로에서만
     * 트리거되므로, 빈자리가 있는 강의의 대기자는 누군가 취소할 때까지 기다리게 된다.
     *
     * @throws com.toby.klass.common.domain.error.BusinessException 강의가 없거나(404),
     *         본인이 개설한 강의이거나(403), 모집 중이 아니거나 이미 신청·대기했거나
     *         자리가 남은 경우(409)
     */
    WaitlistResult register(RegisterWaitlistCommand command);
}
