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
 * <p>Design Ref: §4.3 응답 스펙, §9.2 계층 배치
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
