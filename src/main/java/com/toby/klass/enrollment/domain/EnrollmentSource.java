package com.toby.klass.enrollment.domain;

/**
 * 신청 출처.
 *
 * <p>PENDING 만료 기한이 출처에 따라 다르기 때문에 존재한다. 승격으로 생긴 신청은 뒷 순번
 * 대기자를 붙잡아 두므로 일반 신청보다 짧게 잡는다 (ERD 정본 §2 ⑥ — DIRECT 30분 /
 * WAITLIST 10분). {@code boolean isFromWaitlist} 대신 ENUM 을 쓴 것은 향후 출처가
 * 늘어날 때 분기를 넓히기 쉬워서다.
 *
 * <p>Design Ref: §3.2 ENUM, ERD 정본 §3.3
 */
public enum EnrollmentSource {

    /** 사용자가 직접 신청. */
    DIRECT,

    /** 대기열 승격으로 생성. */
    WAITLIST
}
