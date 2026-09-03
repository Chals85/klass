package com.toby.klass.waitlist.domain.error;

import com.toby.klass.common.domain.error.ErrorCode;

/**
 * 대기열 컨텍스트의 에러 코드.
 *
 * <h2>전 상수에 {@code WAITLIST_} 계열 접두어가 붙는 이유</h2>
 * 상수명이 그대로 API 응답의 {@code error.code} 가 되고 응답에는 enum 타입 정보가 실리지
 * 않는다. 접두어 없이 {@code SEAT_AVAILABLE} · {@code NOT_FOUND} 같은 이름을 두면 다른
 * 컨텍스트의 코드와 구분되지 않는다. {@code NOT_WAITLIST_OWNER} 는 어순이 다르지만
 * {@code NOT_KLASS_OWNER} · {@code NOT_ENROLLMENT_OWNER} 와 같은 계열이다.
 *
 * <p>Design Ref: enrollment-management §7.2
 */
public enum WaitlistError implements ErrorCode {

    /** 대기 내역을 찾을 수 없다. */
    WAITLIST_NOT_FOUND(404, "대기 내역을 찾을 수 없습니다"),

    /** 타인의 대기를 포기시키려 했다. */
    NOT_WAITLIST_OWNER(403, "본인의 대기 내역만 관리할 수 있습니다"),

    /**
     * 이미 같은 강의에 {@code WAITING} 상태로 등록돼 있다.
     *
     * <p>{@code uq_waitlist_waiting} 이 최종 방어한다. 포기했다가 다시 등록하는 것은
     * 허용되므로({@code waiting_user_key} 가 NULL 이 된다) 활성 중복만 걸린다.
     */
    DUPLICATE_WAITLIST(409, "이미 대기 중인 강의입니다"),

    /**
     * 자리가 남아 있는데 대기열에 등록하려 했다.
     *
     * <p><b>막지 않으면 사용자가 영구히 기다린다.</b> 승격은 좌석 반납 경로에서만 일어나므로,
     * 빈자리가 있는 강의의 대기자는 누군가 취소할 때까지 승격되지 않는다. 신청으로 안내하는
     * 편이 맞다.
     *
     * <p>Design Ref: ERD 정본 §4.5 5번
     */
    WAITLIST_SEAT_AVAILABLE(409, "자리가 있습니다. 바로 신청하세요"),

    /**
     * {@code WAITING} 이 아닌 대기를 포기하려 했다. 이미 승격됐거나 이미 포기한 것이다.
     *
     * <p>이 검사가 <b>승격 트랜잭션과의 경합을 막는다.</b> 승격이 먼저 커밋되면 포기 요청은
     * {@code PROMOTED} 를 보고 거부되며, 사용자는 "이미 자리가 배정되었다"를 안내받아야 한다
     * — 포기시켜 버리면 배정된 좌석이 주인 없이 남는다.
     *
     * <p>Design Ref: ERD 정본 §4.9 3번
     */
    WAITLIST_NOT_WAITING(409, "이미 자리가 배정되었거나 포기한 대기입니다");

    private final int httpStatus;
    private final String message;

    WaitlistError(int httpStatus, String message) {
        this.httpStatus = httpStatus;
        this.message = message;
    }

    @Override
    public String message() {
        return message;
    }

    @Override
    public int httpStatus() {
        return httpStatus;
    }
}
