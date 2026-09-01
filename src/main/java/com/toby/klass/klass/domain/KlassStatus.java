package com.toby.klass.klass.domain;

/**
 * 강의 상태.
 *
 * <p>Design Ref: §3.2 ENUM, ERD 정본 §3.3
 */
public enum KlassStatus {

    /** 초안. 신청 불가. 목록·상세는 개설자에게만 노출된다. */
    DRAFT,

    /** 모집 중. <b>신청 가능한 유일한 상태</b>다. */
    OPEN,

    /**
     * 모집 마감.
     *
     * <p>신규 신청과 대기열 승격이 모두 중단되고, 기존 PENDING 의 결제 확정만 허용된다.
     * 두 사안을 나눠 정한 근거는 ERD 정본 §2.1 에 있다 — 요약하면, 마감이 막으려는 것은
     * <b>명단에 새로 들어오는 일</b>이지 이미 들어온 사람의 후속 처리가 아니다.
     */
    CLOSED
}
