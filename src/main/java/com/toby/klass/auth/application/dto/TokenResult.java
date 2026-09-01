package com.toby.klass.auth.application.dto;

import java.time.LocalDateTime;

/**
 * 토큰 발급 결과. 로그인과 재발급이 같은 형태를 돌려준다.
 *
 * <h2>만료를 두 가지로 표현하는 이유</h2>
 * <ul>
 *   <li>{@code expiresIn}(남은 초) — <b>클라이언트 시계가 서버와 어긋나도 안전하다.</b>
 *       클라이언트는 자기 시계 기준으로 갱신 타이머를 걸면 된다. OAuth 2.0 의
 *       {@code expires_in} 관례이기도 하다</li>
 *   <li>{@code expiresAt}(만료 일시) — <b>사람이 읽고 비교하기 쉽다.</b> 로그나 디버깅에서
 *       "몇 시에 만료되는지"를 계산 없이 바로 볼 수 있고, 클라이언트가 저장해 두기에도 편하다</li>
 * </ul>
 *
 * <p>둘은 같은 시점을 가리킨다. 어느 쪽을 쓸지는 클라이언트가 정한다 — 갱신 로직에는
 * {@code expiresIn}, 표시·저장에는 {@code expiresAt} 이 적합하다.
 *
 * <p>{@code expiresAt} 은 서버 시간대 기준 {@link LocalDateTime} 이다. 시간대 정보가 없으므로
 * 클라이언트와 서버가 다른 시간대에 있다면 {@code expiresIn} 을 써야 한다.
 *
 * @param tokenType              항상 {@code "Bearer"}. Authorization 헤더 접두어와 맞춘다
 * @param accessToken            Access 토큰
 * @param accessTokenExpiresIn   Access 유효 시간(초)
 * @param accessTokenExpiresAt   Access 만료 일시 (서버 시간대)
 * @param refreshToken           Refresh 토큰
 * @param refreshTokenExpiresIn  Refresh 유효 시간(초)
 * @param refreshTokenExpiresAt  Refresh 만료 일시 (서버 시간대)
 *
 * <p>Design Ref: §4.2 POST /v1/auth/login
 */
public record TokenResult(String tokenType,
                          String accessToken, long accessTokenExpiresIn, LocalDateTime accessTokenExpiresAt,
                          String refreshToken, long refreshTokenExpiresIn, LocalDateTime refreshTokenExpiresAt) {

    /** Authorization 헤더에 쓰이는 인증 스킴. */
    public static final String BEARER = "Bearer";
}
