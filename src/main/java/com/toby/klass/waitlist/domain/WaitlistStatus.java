package com.toby.klass.waitlist.domain;

/**
 * 대기열 상태.
 *
 * <p>대기 행은 <b>좌석을 점유하지 않는다.</b> 승격되면 {@code enrollment(PENDING)} 이
 * 새로 생기고 이 행은 {@code PROMOTED} 로 끝난다 — 좌석 점유가 한 테이블에만 존재하도록
 * 맞춘 설계다 (ERD 정본 §7.2 "대기열 승격 방식").
 *
 * <p>Design Ref: §3.2 ENUM, ERD 정본 §3.3
 */
public enum WaitlistStatus {

    /** 대기 중. */
    WAITING,

    /** 승격되어 {@code enrollment} 로 전환됨. 종착 상태. */
    PROMOTED,

    /**
     * 대기 종료. 종착 상태.
     *
     * <p>자발적 포기 · 승격 시 부적격 판정 · 강의 마감 시 일괄 정리 세 원인이 있으나
     * 구분해 저장하지 않는다 (ERD 정본 §3.3 의 기록된 한계).
     */
    CANCELLED
}
