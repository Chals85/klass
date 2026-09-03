package com.toby.klass.enrollment.application.dto;

/**
 * 대기열 등록 명령.
 *
 * <p><b>신청이 정원 초과로 거부됐을 때 자동으로 실행되지 않는다.</b> 요청하지 않은 사용자를
 * 대기열에 넣는 것은 월권이고, 자동 분기하면 동시 100건 중 99건이 대기 행이 되어 "99건
 * 거부"라는 성공 기준 자체가 성립하지 않는다 (ERD 정본 §4.2 4번).
 *
 * @param klassId 대상 강의
 * @param userId  대기자. 인증된 사용자 id
 */
public record RegisterWaitlistCommand(Long klassId, Long userId) {
}
