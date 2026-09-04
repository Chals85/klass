package com.toby.klass.klass.adapter.in.web.dto;

import com.toby.klass.klass.application.dto.KlassResult;
import com.toby.klass.klass.domain.KlassStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 강의 상세 응답.
 *
 * <p><b>{@code KlassResult} 를 받지 {@code Klass} 엔티티를 받지 않는다.</b> 컨트롤러가
 * 엔티티를 만지면 {@code adapter.in → domain} 직접 노출이 되고, 지연 로딩 프록시가
 * 직렬화 시점에 초기화되는 사고도 생긴다. {@code UserController} 가 {@code UserResult} 를
 * 받는 것과 같은 구조다.
 *
 * <p>Design Ref: §4.3 응답 스펙, §9.1 계층 배치
 *
 * @param id                     강의 PK
 * @param title                  강의 제목
 * @param description            강의 내용. <b>필수값</b>이다 (D-18)
 * @param price                  수강료
 * @param capacity               최대 정원
 * @param enrollmentCount        좌석 점유 인원({@code PENDING} + {@code CONFIRMED}). 서버가 관리하는
 *                               값이다
 * @param status                 강의 상태. {@code DRAFT} / {@code OPEN} / {@code CLOSED}
 * @param startsOn               수강 시작일
 * @param endsOn                 수강 종료일
 * @param cancellationPeriodDays 취소 가능 기간(일). {@code null} 이면 전역 기본값을 따른다
 * @param creator                개설자 요약
 * @param createdAt              등록 시각
 * @param updatedAt              최종 수정 시각. 수정된 적 없으면 {@code createdAt} 과 같다
 */
public record KlassResponse(
        Long id,
        String title,
        String description,
        BigDecimal price,
        int capacity,
        int enrollmentCount,
        KlassStatus status,
        LocalDate startsOn,
        LocalDate endsOn,
        Integer cancellationPeriodDays,
        KlassCreatorResponse creator,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static KlassResponse from(KlassResult result) {
        return new KlassResponse(
                result.id(), result.title(), result.description(), result.price(),
                result.capacity(), result.enrollmentCount(), result.status(),
                result.startsOn(), result.endsOn(), result.cancellationPeriodDays(),
                KlassCreatorResponse.from(result.creator()),
                result.createdAt(), result.updatedAt());
    }
}
