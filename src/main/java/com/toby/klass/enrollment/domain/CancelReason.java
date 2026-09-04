package com.toby.klass.enrollment.domain;

/**
 * 취소 원인.
 *
 * <h2>왜 이제야 생겼나</h2>
 * ERD 정본 §2 ⑦ 이 취소 원인 저장을 <b>열린 미결</b>로 두면서 도입 조건을 명시했다 —
 * "만료율 측정이나 환불 정책 분기가 필요하면 {@code enrollment.cancel_reason} ENUM 을
 * 추가하는 것이 감사 테이블(FR-15)보다 싸다". 만료 회수 배치가 생기면서 취소 원인이
 * 둘이 되었으므로 그 조건이 충족됐다.
 *
 * <h2>{@code CANCELLED} 일 때만 값이 있다</h2>
 * {@code ck_enrollment_cancelled} 가 양방향으로 강제한다 — {@code CANCELLED} 이면 반드시
 * 있고, 아니면 반드시 없다. 상태와 원인이 어긋난 행은 DB 에 들어오지 못한다.
 *
 * <p>Design Ref: pending-expiry-reaper §3.1, ERD 정본 §2 ⑦
 */
public enum CancelReason {

    /** 사용자가 직접 취소했다. */
    USER,

    /**
     * 결제 기한이 지나 배치가 회수했다.
     *
     * <p>사용자는 이 취소를 요청한 적이 없다. 응답의 {@code cancelReason} 이 그것을
     * 알려주는 유일한 단서다 — 없으면 "내가 취소하지 않았는데 취소돼 있다"가 된다.
     */
    EXPIRED
}
