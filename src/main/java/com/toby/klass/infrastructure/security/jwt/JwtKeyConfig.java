package com.toby.klass.infrastructure.security.jwt;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;

/**
 * HS256 대칭키와 Nimbus 기반 인코더·디코더를 등록한다.
 *
 * <p>이 클래스와 {@code NimbusJwtAdapter} 만이 JWT 라이브러리를 안다. 애플리케이션
 * 계층은 {@code TokenGeneratorPort}/{@code TokenParserPort} 만 보므로, 여기를 갈아끼우면
 * 서명 방식을 바꿀 수 있다(예: HS256 → RS256).
 *
 * <p>Design Ref: §2.4 Port Signatures, §11.1 File Structure
 */
@Configuration
public class JwtKeyConfig {

    /** HS256 이 요구하는 최소 키 길이(바이트). RFC 7518 §3.2 */
    private static final int MIN_KEY_LENGTH_BYTES = 32;

    /**
     * 서명·검증에 쓸 대칭키.
     *
     * @param properties {@code jwt.secret} 을 담은 설정
     * @return HMAC-SHA256 키
     * @throws IllegalStateException Base64 디코딩 결과가 32바이트 미만인 경우.
     *         짧은 키는 HS256 의 보안 가정을 깨뜨리므로 기동을 막는다
     */
    @Bean
    public SecretKey jwtSecretKey(JwtProperties properties) {
        byte[] keyBytes = Base64.getDecoder().decode(properties.secret());
        if (keyBytes.length < MIN_KEY_LENGTH_BYTES) {
            throw new IllegalStateException(
                    "jwt.secret 은 Base64 디코딩 후 %d바이트 이상이어야 합니다 (현재 %d바이트)"
                            .formatted(MIN_KEY_LENGTH_BYTES, keyBytes.length));
        }
        return new SecretKeySpec(keyBytes, "HmacSHA256");
    }

    /**
     * JWT 서명 인코더를 만든다.
     *
     * @return HS256 서명 인코더
     */
    @Bean
    public JwtEncoder jwtEncoder(SecretKey secretKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey));
    }

    /**
     * 디코더. <b>서명과 형식만</b> 검증하고 시간·클레임 검증은 하지 않는다.
     *
     * <h4>왜 기본 검증기를 끄는가</h4>
     * {@link NimbusJwtDecoder} 는 기본으로 {@code JwtTimestampValidator} 를 달아 만료를
     * 검사하는데, 실패하면 {@code JwtValidationException} 하나로 뭉뚱그려 던진다.
     * 그러면 "만료"와 "그 밖의 검증 실패"를 구분하려고 예외 메시지 문자열을 뒤져야 하고,
     * 검증기가 자체 시계를 쓰기 때문에 주입한 {@code Clock} 으로 시간을 고정할 수도 없다.
     *
     * <p>대신 만료·타입 검증은 {@code NimbusJwtAdapter} 가 주입받은 {@code Clock} 으로
     * 직접 수행한다. 무엇을 검증하는지가 코드에 드러나고, 에러 코드를
     * {@code TOKEN_EXPIRED} / {@code TOKEN_TYPE_MISMATCH} 로 정확히 나눌 수 있다.
     * 검증을 생략하는 것이 아니라 <b>옮기는</b> 것이다.
     *
     * @return 서명만 검증하는 디코더
     */
    @Bean
    public JwtDecoder jwtDecoder(SecretKey secretKey) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(token -> OAuth2TokenValidatorResult.success());
        return decoder;
    }
}
