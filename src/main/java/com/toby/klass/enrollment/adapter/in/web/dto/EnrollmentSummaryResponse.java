package com.toby.klass.enrollment.adapter.in.web.dto;

import com.toby.klass.enrollment.application.dto.EnrollmentSummaryResult;
import com.toby.klass.enrollment.domain.CancelReason;
import com.toby.klass.enrollment.domain.EnrollmentSource;
import com.toby.klass.enrollment.domain.EnrollmentStatus;
import java.time.LocalDateTime;

/**
 * 내 신청 목록의 항목.
 *
 * <p>확정·취소 <b>시각</b>을 빼서 상세와 구분한다 — 목록에서는 상태 배지 하나면 충분하다.
 *
 * <p><b>취소 원인({@code cancelReason})은 예외로 넣는다.</b> 만료 취소는 사용자가 요청한 적이
 * 없으므로, 목록에서 이유를 알 수 없으면 "내가 취소하지 않았는데 취소돼 있다"가 된다.
 * 시각은 상세에서 확인하면 되지만 <b>원인은 목록에서 바로 보여야 한다</b>
 * (Design pending-expiry-reaper §4.2).
 *
 * <p>Design Ref: enrollment-management §6.3
 */
public record EnrollmentSummaryResponse(Long id,
                                        Long klassId,
                                        String klassTitle,
                                        EnrollmentStatus status,
                                        EnrollmentSource source,
                                        LocalDateTime createdAt,
                                        LocalDateTime expiresAt,
                                        CancelReason cancelReason,
                                        boolean isCancellable) {

    public static EnrollmentSummaryResponse from(EnrollmentSummaryResult result) {
        return new EnrollmentSummaryResponse(
                result.id(), result.klassId(), result.klassTitle(),
                result.status(), result.source(), result.createdAt(),
                result.expiresAt(), result.cancelReason(), result.isCancellable());
    }
}
