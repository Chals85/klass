package com.toby.klass.enrollment.application.dto;

import com.toby.klass.waitlist.domain.Waitlist;
import com.toby.klass.waitlist.domain.WaitlistStatus;
import java.time.LocalDateTime;

/**
 * 대기 단건 결과.
 *
 * <h2>왜 {@code enrollment} 패키지에 있는가</h2>
 * 좌석 관련 유스케이스가 전부 {@code EnrollmentService} 에 모여 있고(D-29) 이 record 는
 * 그 서비스의 반환 타입이다. {@code waitlist} 패키지에 두면 서비스가 없는 패키지에 DTO 만
 * 남아, 그 패키지를 여는 사람이 "여기 서비스가 있어야 하나"를 매번 다시 판단하게 된다.
 *
 * <h2>등록 응답과 목록 항목이 같은 record 다</h2>
 * 필드가 동일하다. 나누면 이름만 다른 것이 둘 생기고, 한쪽만 고치는 실수가 열린다.
 *
 * <p>Design Ref: enrollment-management §6.3, D-29
 *
 * @param id         대기 행 id. <b>대기 포기 API 의 경로 변수</b>이므로 응답에 반드시 실린다
 * @param position   대기 순번. 취소된 앞 순번은 gap 으로 남으므로 <b>실제 대기 인원수와
 *                   다를 수 있다</b>
 * @param promotedAt 승격 시각. {@code PROMOTED} 가 아니면 {@code null}
 */
public record WaitlistResult(Long id,
                             Long klassId,
                             String klassTitle,
                             int position,
                             WaitlistStatus status,
                             LocalDateTime createdAt,
                             LocalDateTime promotedAt) {

    public static WaitlistResult from(Waitlist waitlist) {
        return new WaitlistResult(
                waitlist.getId(),
                waitlist.getKlass().getId(),
                waitlist.getKlass().getTitle(),
                waitlist.getPosition(),
                waitlist.getStatus(),
                waitlist.getCreatedAt(),
                waitlist.getPromotedAt());
    }
}
