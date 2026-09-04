package com.toby.klass.enrollment.application.dto;

import com.toby.klass.enrollment.domain.CancelReason;
import com.toby.klass.enrollment.domain.Enrollment;
import com.toby.klass.enrollment.domain.EnrollmentSource;
import com.toby.klass.enrollment.domain.EnrollmentStatus;
import java.time.LocalDateTime;

/**
 * 강의별 수강생 목록의 항목. <b>크리에이터만 본다.</b>
 *
 * <h2>{@code EnrollmentSummaryResult} 와 담는 것이 반대다</h2>
 * 그쪽은 <b>어느 강의인지</b>({@code klassTitle})를 담고 여기는 <b>누구인지</b>
 * ({@code username})를 담는다. 강의는 경로에 이미 있어 넣을 이유가 없고, 수강생 정보는
 * 남의 개인정보라 내 목록에 들어갈 이유가 없다.
 *
 * <p><b>{@code isCancellable} 을 넣지 않는다.</b> 취소 권한은 수강생에게 있지 크리에이터에게
 * 없으므로, 그 값을 보여주면 누를 수 없는 버튼의 근거가 된다.
 *
 * <p>fetch join 대상도 반대다 — 여기는 {@code user} 를 조인해야 한다.
 *
 * <p>Design Ref: enrollment-management §6.3
 */
public record KlassEnrollmentResult(Long id,
                                    Long userId,
                                    String username,
                                    EnrollmentStatus status,
                                    EnrollmentSource source,
                                    LocalDateTime createdAt,
                                    LocalDateTime confirmedAt,
                                    CancelReason cancelReason) {

    public static KlassEnrollmentResult from(Enrollment enrollment) {
        return new KlassEnrollmentResult(
                enrollment.getId(),
                enrollment.getUser().getId(),
                enrollment.getUser().getUsername(),
                enrollment.getStatus(),
                enrollment.getSource(),
                enrollment.getCreatedAt(),
                enrollment.getConfirmedAt(),
                enrollment.getCancelReason());
    }
}
