package com.toby.klass.klass.application.dto;

import com.toby.klass.klass.domain.Klass;
import com.toby.klass.klass.domain.KlassStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 강의 상세 결과.
 *
 * <p><b>엔티티를 컨트롤러까지 올리지 않기 위한 경계다.</b> {@code adapter.in} 이
 * {@code domain} 엔티티를 직접 다루면 의존 규칙 위반이고, 지연 로딩 프록시가 직렬화 시점에
 * 초기화되는 사고도 생긴다.
 *
 * <p>{@code from} 이 {@code creator.getUsername()} 을 읽으므로 <b>이 시점에 프록시가
 * 초기화된다</b> — 목록 조회에서 fetch join 이 없으면 여기서 N+1 이 난다 (Design §8.3 #7).
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
public record KlassResult(
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
        KlassCreatorResult creator,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static KlassResult from(Klass klass) {
        return new KlassResult(
                klass.getId(), klass.getTitle(), klass.getDescription(), klass.getPrice(),
                klass.getCapacity(), klass.getEnrollmentCount(), klass.getStatus(),
                klass.getStartsOn(), klass.getEndsOn(), klass.getCancellationPeriodDays(),
                KlassCreatorResult.from(klass.getCreator()),
                klass.getCreatedAt(), klass.getUpdatedAt());
    }
}
