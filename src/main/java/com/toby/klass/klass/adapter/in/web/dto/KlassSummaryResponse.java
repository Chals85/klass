package com.toby.klass.klass.adapter.in.web.dto;

import com.toby.klass.klass.application.dto.KlassSummaryResult;
import com.toby.klass.klass.domain.KlassStatus;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 목록용 강의 요약.
 *
 * <p><b>{@code description} 이 없다.</b> {@code TEXT} 컬럼이라 20건이면 응답이 크게 부풀고,
 * 목록에서 전문을 보여주는 화면도 없다. 내용이 필요하면 상세를 부른다.
 * {@code updatedAt}·{@code cancellationPeriodDays} 도 목록에서 판단에 쓰이지 않아 뺐다.
 *
 * <p>Design Ref: §4.3 GET /v1/klasses 응답
 */
public record KlassSummaryResponse(
        Long id,
        String title,
        BigDecimal price,
        int capacity,
        int enrollmentCount,
        KlassStatus status,
        LocalDate startsOn,
        LocalDate endsOn,
        KlassCreatorResponse creator) {

    public static KlassSummaryResponse from(KlassSummaryResult result) {
        return new KlassSummaryResponse(
                result.id(), result.title(), result.price(), result.capacity(),
                result.enrollmentCount(), result.status(),
                result.startsOn(), result.endsOn(),
                KlassCreatorResponse.from(result.creator()));
    }
}
