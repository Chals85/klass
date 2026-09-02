package com.toby.klass.infrastructure.security.jwt;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * JWT 발급 설정. {@code application.yml} 의 {@code jwt.*} 를 바인딩한다.
 *
 * <p>만료 시간을 {@link Duration} 으로 받으므로 설정에는 ISO-8601 표기를 쓴다
 * ({@code PT30M}, {@code P14D}). 숫자와 단위를 따로 두는 방식보다 오해가 적다.
 *
 * <p>{@code @Validated} 를 붙여 <b>기동 시점에</b> 검증한다. 시크릿이 비어 있는 채로
 * 떠서 첫 로그인 요청에서야 실패하는 것보다, 아예 뜨지 않는 편이 낫다.
 *
 * <p>Design Ref: §10.3 Properties
 *
 * @param issuer               토큰의 {@code iss} 클레임
 * @param secret               HS256 서명 키. <b>Base64 인코딩된</b> 값이며 디코딩 후 32바이트
 *                             이상이어야 한다({@link JwtKeyConfig} 가 검사)
 * @param accessTokenValidity  Access 유효 기간. ISO-8601 표기로 설정한다 ({@code PT30M})
 * @param refreshTokenValidity Refresh 유효 기간. ISO-8601 표기로 설정한다 ({@code P14D})
 */
@ConfigurationProperties(prefix = "jwt")
@Validated
public record JwtProperties(
        @NotBlank String issuer,
        @NotBlank String secret,
        @NotNull Duration accessTokenValidity,
        @NotNull Duration refreshTokenValidity) {
}
