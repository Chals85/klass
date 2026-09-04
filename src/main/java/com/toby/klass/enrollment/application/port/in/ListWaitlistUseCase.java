package com.toby.klass.enrollment.application.port.in;

import com.toby.klass.common.application.dto.CursorPageResult;
import com.toby.klass.waitlist.application.dto.WaitlistQuery;
import com.toby.klass.enrollment.application.dto.WaitlistResult;

/** 내 대기 목록 조회. */
public interface ListWaitlistUseCase {

    /**
     * 내 대기 목록. 승격·포기한 기록도 포함한다 — 내 이력이다.
     *
     * <p><b>대기 포기 API 의 경로 변수({@code waitlistId})를 얻는 유일한 경로다.</b>
     * 등록 응답을 놓치면 여기 말고는 알아낼 방법이 없다.
     */
    CursorPageResult<WaitlistResult> listMineWaitlist(Long userId, WaitlistQuery query);
}
