package com.toby.klass.auth.adapter.in.web.dto;

import com.toby.klass.auth.application.dto.ReissueCommand;
import jakarta.validation.constraints.NotBlank;

/**
 * 토큰 재발급 요청 본문.
 *
 * <p>Design Ref: §4.2 POST /v1/auth/reissue
 *
 * @param refreshToken Refresh 토큰 원문
 */
public record ReissueRequest(
        @NotBlank(message = "리프레시 토큰은 필수입니다")
        String refreshToken) {

    /**
     * 애플리케이션 계층으로 넘길 커맨드
     */
    public ReissueCommand toCommand() {
        return new ReissueCommand(refreshToken);
    }
}
