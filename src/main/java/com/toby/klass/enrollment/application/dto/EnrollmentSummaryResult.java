package com.toby.klass.enrollment.application.dto;

import com.toby.klass.enrollment.domain.CancelReason;
import com.toby.klass.enrollment.domain.Enrollment;
import com.toby.klass.enrollment.domain.EnrollmentSource;
import com.toby.klass.enrollment.domain.EnrollmentStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 내 신청 목록의 항목.
 *
 * <p>{@link EnrollmentResult} 와 필드가 거의 같지만 {@code confirmedAt}·{@code cancelledAt}
 * 을 뺐다 — 목록에서는 상태 배지 하나면 충분하고, 정확한 시각이 필요하면 상세를 연다.
 *
 * <p><b>{@code klassTitle} 과 {@code isCancellable} 이 강의를 읽는다.</b> 목록 조회에서
 * {@code klass} fetch join 을 빠뜨리면 N+1 이 나고, {@code open-in-view: false} 라
 * 컨트롤러 직렬화 시점에 터진다.
 *
 * <p>Design Ref: enrollment-management §6.3
 */
public record EnrollmentSummaryResult(Long id,
                                      Long klassId,
                                      String klassTitle,
                                      EnrollmentStatus status,
                                      EnrollmentSource source,
                                      LocalDateTime createdAt,
                                      LocalDateTime expiresAt,
                                      CancelReason cancelReason,
                                      boolean isCancellable) {

    public static EnrollmentSummaryResult from(Enrollment enrollment, LocalDateTime now,
                                               LocalDate today, int defaultPeriodDays) {
        return new EnrollmentSummaryResult(
                enrollment.getId(),
                enrollment.getKlass().getId(),
                enrollment.getKlass().getTitle(),
                enrollment.getStatus(),
                enrollment.getSource(),
                enrollment.getCreatedAt(),
                enrollment.getExpiresAt(),
                enrollment.getCancelReason(),
                enrollment.isCancellableAt(now, today,
                        enrollment.getKlass().cancellationPolicy(defaultPeriodDays)));
    }
}
