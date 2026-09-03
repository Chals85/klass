package com.toby.klass.enrollment.application.dto;

/**
 * 대기 포기 명령.
 *
 * @param waitlistId  대상 대기 행
 * @param requesterId 요청자. {@code waitlist.user_id} 와 일치해야 한다 (ERD 정본 §4.9 2번)
 */
public record GiveUpWaitlistCommand(Long waitlistId, Long requesterId) {
}
