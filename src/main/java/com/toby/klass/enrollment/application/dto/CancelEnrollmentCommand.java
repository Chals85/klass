package com.toby.klass.enrollment.application.dto;

/**
 * 수강 취소 명령.
 *
 * @param enrollmentId 대상 신청
 * @param requesterId  요청자. {@code enrollment.user_id} 와 일치해야 한다 (ERD 정본 §4.4 5-a)
 */
public record CancelEnrollmentCommand(Long enrollmentId, Long requesterId) {
}
