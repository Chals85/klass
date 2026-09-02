package com.toby.klass.klass.application.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 강의 등록 명령.
 *
 * <p>상태는 담지 않는다 — 새 강의는 항상 {@code DRAFT} 로 시작하므로 호출자가 정할 여지가
 * 없다 (ERD 정본 §3.3).
 *
 * <p>Design Ref: §4.3 POST /v1/klasses
 *
 * @param creatorId              개설자 id. 토큰의 {@code sub} 에서 온다
 * @param title                  강의 제목
 * @param description            내용. <b>필수값</b> (D-18)
 * @param price                  수강료
 * @param capacity               최대 정원
 * @param startsOn               수강 시작일
 * @param endsOn                 수강 종료일. {@code startsOn} 과 쌍으로 도메인에 넘어간다
 * @param cancellationPeriodDays 취소 가능 기간(일). {@code null} 이면 전역 기본값
 */
public record RegisterKlassCommand(
        Long creatorId,
        String title,
        String description,
        BigDecimal price,
        int capacity,
        LocalDate startsOn,
        LocalDate endsOn,
        Integer cancellationPeriodDays) {
}
