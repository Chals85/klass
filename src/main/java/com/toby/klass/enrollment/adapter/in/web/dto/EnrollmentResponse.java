package com.toby.klass.enrollment.adapter.in.web.dto;

import com.toby.klass.enrollment.application.dto.EnrollmentResult;
import com.toby.klass.enrollment.domain.CancelReason;
import com.toby.klass.enrollment.domain.EnrollmentSource;
import com.toby.klass.enrollment.domain.EnrollmentStatus;
import java.time.LocalDateTime;

/**
 * 신청 단건 응답. 신청·확정·취소·상세가 모두 이것을 쓴다.
 *
 * <p><b>{@code isCancellable} 은 boolean 이므로 {@code is} 접두어를 붙인다</b> — DB
 * {@code is_enabled} ↔ 필드 {@code isEnabled} ↔ API {@code data.isEnabled} 와 같은 규칙이다.
 * 판정은 서버가 하고 클라이언트는 받아 쓴다 (Design D-39).
 *
 * <p>Design Ref: enrollment-management §6.3
 */
public record EnrollmentResponse(Long id,
                                 Long klassId,
                                 String klassTitle,
                                 EnrollmentStatus status,
                                 EnrollmentSource source,
                                 LocalDateTime createdAt,
                                 LocalDateTime expiresAt,
                                 LocalDateTime confirmedAt,
                                 LocalDateTime cancelledAt,
                                 CancelReason cancelReason,
                                 boolean isCancellable) {

    public static EnrollmentResponse from(EnrollmentResult result) {
        return new EnrollmentResponse(
                result.id(), result.klassId(), result.klassTitle(),
                result.status(), result.source(), result.createdAt(),
                result.expiresAt(), result.confirmedAt(), result.cancelledAt(),
                result.cancelReason(), result.isCancellable());
    }
}
