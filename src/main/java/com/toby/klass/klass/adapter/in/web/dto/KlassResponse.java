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
 * <p>Design Ref: §4.3 응답 스펙, §9.2 계층 배치
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
