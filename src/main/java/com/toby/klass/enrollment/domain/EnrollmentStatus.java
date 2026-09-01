package com.toby.klass.enrollment.domain;

/**
 * 수강 신청 상태.
 *
 * <p><b>좌석 점유 여부가 이 값으로 결정된다.</b> {@code PENDING} 과 {@code CONFIRMED} 가
 * 좌석을 점유하며, 그 합이 {@code klass.enrollment_count} 다 (ERD 정본 §2 ①).
 *
 * <p>Design Ref: §3.2 ENUM, ERD 정본 §3.3
 */
public enum EnrollmentStatus {

    /** 신청 완료, 결제 대기. <b>좌석 점유.</b> {@code expires_at} 이 반드시 있다. */
    PENDING,

    /** 결제 완료, 수강 확정. <b>좌석 점유.</b> */
    CONFIRMED,

    /**
     * 취소됨. 좌석 미점유. 종착 상태.
     *
     * <p>사용자 취소와 만료를 구분해 저장하지 않는다 — ERD 정본 §2 ⑦ 의 열린 미결이다.
     */
    CANCELLED
}
