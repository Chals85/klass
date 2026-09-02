package com.toby.klass.klass.adapter.in.web.dto;

import com.toby.klass.klass.application.dto.ChangeKlassStatusCommand;
import com.toby.klass.klass.domain.KlassStatus;
import jakarta.validation.constraints.NotNull;

/**
 * 강의 상태 변경 요청.
 *
 * <p>정의되지 않은 값({@code "OPENED"})은 JSON 역직렬화에서 걸려
 * {@code HttpMessageNotReadableException} → 400 {@code MALFORMED_REQUEST} 가 된다.
 *
 * <p>Design Ref: §4.3 PATCH /v1/klasses/{id}/status
 */
public record ChangeKlassStatusRequest(

        @NotNull(message = "변경할 상태는 필수입니다")
        KlassStatus status) {

    public ChangeKlassStatusCommand toCommand(Long klassId, Long requesterId) {
        return new ChangeKlassStatusCommand(klassId, requesterId, status);
    }
}
