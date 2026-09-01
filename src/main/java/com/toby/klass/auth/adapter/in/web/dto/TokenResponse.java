package com.toby.klass.auth.adapter.in.web.dto;

import com.toby.klass.auth.application.dto.TokenResult;
import java.time.LocalDateTime;

/**
 * 토큰 발급 응답 본문. 로그인과 재발급이 같은 형태를 쓴다.
 *
 * <p>만료를 남은 초({@code expiresIn})와 만료 일시({@code expiresAt}) 두 가지로 준다.
 * 갱신 타이머에는 전자가, 표시·저장에는 후자가 적합하다. 근거는 {@link TokenResult} 참조.
 *
 * @param tokenType              {@code "Bearer"}. Authorization 헤더에 그대로 붙인다
 * @param accessToken            Access 토큰
 * @param accessTokenExpiresIn   Access 유효 시간(초)
 * @param accessTokenExpiresAt   Access 만료 일시 (서버 시간대)
 * @param refreshToken           Refresh 토큰
 * @param refreshTokenExpiresIn  Refresh 유효 시간(초)
 * @param refreshTokenExpiresAt  Refresh 만료 일시 (서버 시간대)
 *
 * <p>Design Ref: §4.2 POST /v1/auth/login
 */
public record TokenResponse(String tokenType,
                            String accessToken, long accessTokenExpiresIn, LocalDateTime accessTokenExpiresAt,
                            String refreshToken, long refreshTokenExpiresIn, LocalDateTime refreshTokenExpiresAt) {

    /**
     * 유즈케이스 결과를 응답 본문으로 변환한다.
     *
     * @param result 유즈케이스 결과
     * @return 응답 본문
     */
    public static TokenResponse from(TokenResult result) {
        return new TokenResponse(
                result.tokenType(),
                result.accessToken(), result.accessTokenExpiresIn(), result.accessTokenExpiresAt(),
                result.refreshToken(), result.refreshTokenExpiresIn(), result.refreshTokenExpiresAt());
    }
}
