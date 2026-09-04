package com.toby.klass.auth.application.port.out;

import com.toby.klass.auth.application.port.out.dto.GeneratedToken;
import java.util.List;

/**
 * JWT 발급 능력. 구현은 {@code adapter.out.token.NimbusJwtAdapter} 다.
 *
 * <p>이 인터페이스가 이 예제에서 헥사고날을 보여주는 지점이다. 애플리케이션 계층은
 * Nimbus·jjwt·직접 구현 중 무엇이 뒤에 있는지 모른다. 라이브러리를 교체해도
 * 서비스 코드는 그대로다.
 *
 * <p>Design Ref: §2.3 의존성
 */
public interface TokenGeneratorPort {

    /**
     * Access 토큰을 발급한다. 권한 정보를 클레임에 싣는다.
     *
     * @param userId   {@code sub} 로 들어갈 사용자 id
     * @param username {@code username} 클레임
     * @param roles    {@code roles} 클레임. {@code ["ROLE_USER"]} 형태
     * @return 서명된 토큰과 발급·만료 시각
     */
    GeneratedToken generateAccessToken(Long userId, String username, List<String> roles);

    /**
     * Refresh 토큰을 발급한다.
     *
     * <p>권한을 싣지 않는다 — 재발급 시점에 DB 에서 최신 권한을 다시 읽으므로
     * 권한 변경이 즉시 반영되고, 탈취 시 노출되는 정보도 줄어든다.
     *
     * @param userId {@code sub} 로 들어갈 사용자 id
     * @return 서명된 토큰과 발급·만료 시각
     */
    GeneratedToken generateRefreshToken(Long userId);
}
