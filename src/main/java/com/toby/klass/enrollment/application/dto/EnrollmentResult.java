package com.toby.klass.enrollment.application.dto;

import com.toby.klass.enrollment.domain.Enrollment;
import com.toby.klass.enrollment.domain.EnrollmentSource;
import com.toby.klass.enrollment.domain.EnrollmentStatus;
import com.toby.klass.klass.domain.CancellationPolicy;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 신청 단건 결과. 서비스가 어댑터로 넘기는 형태다.
 *
 * <h2>{@code isCancellable} 을 여기서 계산하는 이유</h2>
 * 클라이언트가 취소 버튼을 보일지 판단하려면 취소 가능 기간과 강의 종료일을 스스로 계산해야
 * 하는데, 그러면 <b>판정 로직이 서버와 클라이언트 양쪽에 복제</b>된다. 서버가 이미 아는
 * 답을 실어 보낸다 (Design D-39).
 *
 * <p>계산은 {@link Enrollment#isCancellableAt} 이 하고 이 record 는 옮기기만 한다 —
 * 여기서 조건을 다시 쓰면 {@code cancel()} 과 갈라져, <b>사용자가 버튼을 눌렀는데 실패하는</b>
 * 상황이 생긴다.
 *
 * <h2>{@code klass} 프록시가 초기화돼 있어야 한다</h2>
 * {@code klassTitle} 과 {@code isCancellable} 이 둘 다 강의를 읽는다. 목록 조회에서
 * fetch join 을 빠뜨리면 N+1 이 나고, {@code open-in-view: false} 라 컨트롤러 직렬화
 * 시점에 터진다.
 *
 * <p>Design Ref: enrollment-management §6.3, D-39
 */
public record EnrollmentResult(Long id,
                               Long klassId,
                               String klassTitle,
                               EnrollmentStatus status,
                               EnrollmentSource source,
                               LocalDateTime createdAt,
                               LocalDateTime expiresAt,
                               LocalDateTime confirmedAt,
                               LocalDateTime cancelledAt,
                               boolean isCancellable) {

    /**
     * @param now                현재 시각 ({@code LocalDateTime.now(clock)})
     * @param today              오늘 날짜 ({@code LocalDate.now(clock)})
     * @param defaultPeriodDays  전역 기본 취소 가능 기간. 강의가 지정하지 않았을 때 쓰인다
     */
    public static EnrollmentResult from(Enrollment enrollment, LocalDateTime now,
                                        LocalDate today, int defaultPeriodDays) {
        CancellationPolicy policy =
                enrollment.getKlass().cancellationPolicy(defaultPeriodDays);

        return new EnrollmentResult(
                enrollment.getId(),
                enrollment.getKlass().getId(),
                enrollment.getKlass().getTitle(),
                enrollment.getStatus(),
                enrollment.getSource(),
                enrollment.getCreatedAt(),
                enrollment.getExpiresAt(),
                enrollment.getConfirmedAt(),
                enrollment.getCancelledAt(),
                enrollment.isCancellableAt(now, today, policy));
    }
}
