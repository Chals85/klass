package com.toby.klass.enrollment.application.dto;

/**
 * 수강 신청 명령.
 *
 * <p><b>{@code userId} 는 항상 JWT {@code sub} 에서 온다.</b> 요청 본문이나 경로에서 받지
 * 않는다 — 받으면 남의 이름으로 신청할 수 있다 (ERD 정본 §7).
 *
 * @param klassId 대상 강의
 * @param userId  신청자. 인증된 사용자 id
 */
public record ApplyEnrollmentCommand(Long klassId, Long userId) {
}
