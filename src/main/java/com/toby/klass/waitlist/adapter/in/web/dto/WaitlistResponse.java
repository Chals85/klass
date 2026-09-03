package com.toby.klass.waitlist.adapter.in.web.dto;

import com.toby.klass.enrollment.application.dto.WaitlistResult;
import com.toby.klass.waitlist.domain.WaitlistStatus;
import java.time.LocalDateTime;

/**
 * 대기 응답. <b>등록(201)과 목록 항목이 같은 형태다.</b>
 *
 * <p>필드가 동일하므로 나누면 이름만 다른 것이 둘 생기고, 한쪽만 고치는 실수가 열린다.
 *
 * <p>Design Ref: enrollment-management §6.3
 *
 * @param id       <b>대기 포기 API 의 경로 변수</b>다. 응답에서 빠지면 사용자가 포기할 방법이
 *                 없어진다
 * @param position 대기 순번. 취소된 앞 순번은 gap 으로 남으므로 <b>실제 대기 인원수와 다를 수
 *                 있다</b> — "내 앞에 N명"으로 읽으면 안 된다
 */
public record WaitlistResponse(Long id,
                               Long klassId,
                               String klassTitle,
                               int position,
                               WaitlistStatus status,
                               LocalDateTime createdAt,
                               LocalDateTime promotedAt) {

    public static WaitlistResponse from(WaitlistResult result) {
        return new WaitlistResponse(
                result.id(), result.klassId(), result.klassTitle(), result.position(),
                result.status(), result.createdAt(), result.promotedAt());
    }
}
