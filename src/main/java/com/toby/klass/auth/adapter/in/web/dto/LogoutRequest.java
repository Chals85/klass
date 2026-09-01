package com.toby.klass.auth.adapter.in.web.dto;

import com.toby.klass.auth.application.dto.LogoutCommand;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;

/**
 * 로그아웃 요청 본문.
 *
 * <p>{@code userId} 는 요청 본문에 없다. 인증된 principal 에서 꺼내 채운다 —
 * 클라이언트가 보낸 값을 믿으면 남의 토큰을 지울 수 있기 때문이다.
 *
 * <p>Access 토큰도 본문에 없다. 이미 {@code Authorization} 헤더로 왔고 필터가 파싱해
 * principal 에 담아뒀으므로, 클라이언트가 같은 값을 두 번 보낼 이유가 없다.
 *
 * @param refreshToken 폐기할 Refresh 토큰 원문
 *
 * <p>Design Ref: §4.2 POST /v1/auth/logout
 */
public record LogoutRequest(
        @NotBlank(message = "리프레시 토큰은 필수입니다")
        String refreshToken) {

    /**
     * 애플리케이션 계층으로 넘길 커맨드로 변환한다.
     *
     * <p>본문에 없는 세 값은 모두 <b>인증된 principal</b>에서 온다. 클라이언트가 보낸
     * 값이 아니므로 위조할 수 없다.
     *
     * @param userId               인증된 사용자 id
     * @param accessTokenId        현재 Access 토큰의 {@code jti}
     * @param accessTokenExpiresAt 현재 Access 토큰의 만료 시각
     * @return 커맨드
     */
    public LogoutCommand toCommand(Long userId, String accessTokenId, Instant accessTokenExpiresAt) {
        return new LogoutCommand(userId, refreshToken, accessTokenId, accessTokenExpiresAt);
    }
}
