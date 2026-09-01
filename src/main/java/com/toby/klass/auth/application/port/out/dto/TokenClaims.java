package com.toby.klass.auth.application.port.out.dto;

import com.toby.klass.auth.domain.TokenType;
import java.time.Instant;
import java.util.List;

/**
 * 토큰 파싱 포트의 반환값. 서명·만료·타입 검증을 <b>모두 통과한</b> 토큰의 내용이다.
 *
 * <p>검증 실패는 이 타입으로 표현하지 않는다. 포트가 {@code BusinessException} 을
 * 던지므로, 이 객체를 손에 넣었다는 것 자체가 토큰이 유효하다는 뜻이다.
 * "유효 여부 플래그"를 두면 호출자가 검사를 잊을 수 있다.
 *
 * <p><b>단, 폐기(로그아웃) 여부는 여기서 알 수 없다.</b> 그것은 서명만으로 판정할 수 없고
 * 저장소 조회가 필요하므로 {@code VerifyAccessTokenUseCase} 가 담당한다.
 *
 * <p>Nimbus 나 Spring Security 의 {@code Jwt} 타입을 그대로 노출하지 않는 것이
 * 이 설계의 핵심이다. 애플리케이션 계층은 어떤 JWT 라이브러리를 쓰는지 모른다.
 *
 * <p>Design Ref: §2.4 Port Signatures, §3.4 JWT 클레임 구조
 *
 * @param jti   {@code jti} 클레임. 토큰 하나를 유일하게 가리키는 값이며,
 *                  로그아웃 시 폐기 목록에 올릴 키다
 * @param userId    {@code sub} 클레임에서 파싱한 사용자 id
 * @param username  {@code username} 클레임. Refresh 토큰에는 없으므로 {@code null} 일 수 있다
 * @param roles     {@code roles} 클레임. Refresh 토큰에는 없으므로 빈 목록일 수 있다
 * @param type      {@code typ} 클레임
 * @param issuedAt  {@code iat}
 * @param expiresAt {@code exp}
 */
public record TokenClaims(String jti, Long userId, String username, List<String> roles,
                          TokenType type, Instant issuedAt, Instant expiresAt) {

    /** 방어적 복사로 불변을 보장한다. */
    public TokenClaims {
        roles = roles == null ? List.of() : List.copyOf(roles);
    }
}
