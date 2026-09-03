package com.toby.klass.enrollment.adapter.in.web.dto;

import com.toby.klass.enrollment.application.dto.KlassEnrollmentResult;
import com.toby.klass.enrollment.domain.EnrollmentSource;
import com.toby.klass.enrollment.domain.EnrollmentStatus;
import java.time.LocalDateTime;

/**
 * 강의별 수강생 목록의 항목. <b>크리에이터만 받는다.</b>
 *
 * <p>수강생 정보({@code userId}·{@code username})가 실리므로 소유권 검사를 통과한 요청만
 * 여기까지 온다. 권한({@code ROLE_CREATOR})만으로는 부족하다 — 그것만 보면 크리에이터끼리
 * 서로의 명단을 볼 수 있다.
 *
 * <p>Design Ref: enrollment-management §6.3
 */
public record KlassEnrollmentResponse(Long id,
                                      Long userId,
                                      String username,
                                      EnrollmentStatus status,
                                      EnrollmentSource source,
                                      LocalDateTime createdAt,
                                      LocalDateTime confirmedAt) {

    public static KlassEnrollmentResponse from(KlassEnrollmentResult result) {
        return new KlassEnrollmentResponse(
                result.id(), result.userId(), result.username(),
                result.status(), result.source(), result.createdAt(), result.confirmedAt());
    }
}
