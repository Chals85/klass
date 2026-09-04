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
     * <p>원인은 {@code cancelReason}({@code USER}/{@code EXPIRED})이 구분해 갖는다 —
     * ERD 정본 §2 ⑦ 이 열어 두었던 미결이며 만료 회수 배치가 생기면서 닫았다.
     */
    CANCELLED
}
