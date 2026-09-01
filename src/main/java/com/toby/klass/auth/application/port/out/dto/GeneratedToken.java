package com.toby.klass.auth.application.port.out.dto;

import java.time.Duration;
import java.time.Instant;

/**
 * 토큰 생성 포트의 반환값.
 *
 * <p>토큰 문자열만 돌려주지 않고 발급·만료 시각을 함께 담는 이유는 <b>만료 시각의
 * 단일 출처</b>를 만들기 위함이다. 서비스가 {@code Clock} 과 설정값으로 만료를 다시
 * 계산하면 JWT 의 {@code exp} 와 DB 의 {@code expires_at} 이 어긋날 수 있다.
 * 어댑터가 실제로 서명에 넣은 값을 그대로 돌려주면 그런 불일치가 생기지 않는다.
 *
 * <p>이 record 가 {@code application/dto} 가 아니라 {@code port/out/dto} 에 있는 것은
 * 유즈케이스 경계의 DTO 가 아니라 <b>포트의 입출력</b>이기 때문이다.
 *
 * @param value     서명된 JWT 문자열
 * @param issuedAt  발급 시각. JWT 의 {@code iat} 와 같다
 * @param expiresAt 만료 시각. JWT 의 {@code exp} 와 같다
 *
 * <p>Design Ref: §2.4 Port Signatures
 */
public record GeneratedToken(String value, Instant issuedAt, Instant expiresAt) {

    /**
     * 응답의 {@code expiresIn} 필드에 실을 값.
     *
     * @return 발급 시점 기준 남은 유효 시간(초)
     */
    public long expiresInSeconds() {
        return Duration.between(issuedAt, expiresAt).toSeconds();
    }
}
