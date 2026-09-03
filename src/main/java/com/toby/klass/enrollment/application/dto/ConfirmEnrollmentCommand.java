package com.toby.klass.enrollment.application.dto;

/**
 * 결제 완료 처리 명령.
 *
 * <p>결제 게이트웨이 연동은 이 프로젝트의 범위가 아니다 (ERD 정본 §1.3). 외부에서 결제가
 * 끝났다는 신호로 상태만 전이한다.
 *
 * @param enrollmentId 대상 신청
 * @param requesterId  요청자. {@code enrollment.user_id} 와 일치해야 한다 — 타인의
 *                     {@code PENDING} 을 확정할 수 없다 (ERD 정본 §4.3 2번)
 */
public record ConfirmEnrollmentCommand(Long enrollmentId, Long requesterId) {
}
