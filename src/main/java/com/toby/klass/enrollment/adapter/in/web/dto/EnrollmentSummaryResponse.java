package com.toby.klass.enrollment.adapter.in.web.dto;

import com.toby.klass.enrollment.application.dto.EnrollmentSummaryResult;
import com.toby.klass.enrollment.domain.EnrollmentSource;
import com.toby.klass.enrollment.domain.EnrollmentStatus;
import java.time.LocalDateTime;

/**
 * 내 신청 목록의 항목.
 *
 * <p>확정·취소 시각을 빼서 상세와 구분한다 — 목록에서는 상태 배지 하나면 충분하다.
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
                                        boolean isCancellable) {

    public static EnrollmentSummaryResponse from(EnrollmentSummaryResult result) {
        return new EnrollmentSummaryResponse(
                result.id(), result.klassId(), result.klassTitle(),
                result.status(), result.source(), result.createdAt(),
                result.expiresAt(), result.isCancellable());
    }
}
