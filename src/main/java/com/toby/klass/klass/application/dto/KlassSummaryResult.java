package com.toby.klass.klass.application.dto;

import com.toby.klass.klass.domain.Klass;
import com.toby.klass.klass.domain.KlassStatus;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 목록용 강의 요약.
 *
 * <h2>{@code description} 을 담지 않는 이유</h2>
 * {@code TEXT} 컬럼이라 20건이면 응답이 크게 부푼다. 목록에서 전문을 보여주는 화면도 없다.
 * 내용이 필요하면 상세를 부른다.
 *
 * <p>{@code updatedAt}·{@code cancellationPeriodDays} 도 같은 이유로 뺀다 — 목록에서
 * 판단에 쓰이지 않는다. 필요해지면 그때 넣는다.
 *
 * <p>Design Ref: §4.3 GET /v1/klasses 응답
 *
 * @param id              강의 PK
 * @param title           강의 제목
 * @param price           수강료
 * @param capacity        최대 정원
 * @param enrollmentCount 좌석 점유 인원({@code PENDING} + {@code CONFIRMED}). 서버가 관리하는 값이다
 * @param status          강의 상태. {@code DRAFT} / {@code OPEN} / {@code CLOSED}
 * @param startsOn        수강 시작일
 * @param endsOn          수강 종료일
 * @param creator         개설자 요약
 */
public record KlassSummaryResult(
        Long id,
        String title,
        BigDecimal price,
        int capacity,
        int enrollmentCount,
        KlassStatus status,
        LocalDate startsOn,
        LocalDate endsOn,
        KlassCreatorResult creator) {

    public static KlassSummaryResult from(Klass klass) {
        return new KlassSummaryResult(
                klass.getId(), klass.getTitle(), klass.getPrice(), klass.getCapacity(),
                klass.getEnrollmentCount(), klass.getStatus(),
                klass.getStartsOn(), klass.getEndsOn(),
                KlassCreatorResult.from(klass.getCreator()));
    }
}
