package com.toby.klass.auth.application.dto;

/**
 * 토큰 재발급 요청.
 *
 * @param refreshToken 재발급에 쓸 Refresh 토큰 원문(JWT 문자열)
 *
 * <p>Design Ref: §2.2 재발급 흐름
 */
public record ReissueCommand(String refreshToken) {
}
