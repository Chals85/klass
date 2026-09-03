package com.toby.klass.enrollment.application.port.in;

import com.toby.klass.common.application.dto.CursorPageResult;
import com.toby.klass.enrollment.application.dto.EnrollmentQuery;
import com.toby.klass.enrollment.application.dto.EnrollmentSummaryResult;
import com.toby.klass.enrollment.application.dto.KlassEnrollmentResult;

/** 신청 목록 조회 2종. 보는 사람에 따라 담기는 것이 다르다. */
public interface ListEnrollmentUseCase {

    /**
     * 내 신청 목록. 취소한 것까지 전부 — 내 기록이므로 가리지 않는다.
     *
     * @param userId 인증된 사용자. <b>경로나 본문에서 받지 않는다</b>
     */
    CursorPageResult<EnrollmentSummaryResult> listMine(Long userId, EnrollmentQuery query);

    /**
     * 강의별 수강생 목록. <b>크리에이터 전용이며 소유권을 검사한다.</b>
     *
     * <p>{@code SecurityConfig} 의 {@code hasRole("CREATOR")} 만으로는 부족하다 —
     * 크리에이터끼리 서로의 수강생 명단을 볼 수 있게 된다.
     *
     * @param requesterId 요청자. {@code klass.creator_id} 와 일치해야 한다
     * @throws com.toby.klass.common.domain.error.BusinessException 강의가 없거나(404)
     *                                                             남의 강의인 경우(403)
     */
    CursorPageResult<KlassEnrollmentResult> listByKlass(Long klassId, Long requesterId,
                                                        EnrollmentQuery query);
}
