package com.toby.klass.auth.adapter.out.security;

import com.toby.klass.auth.application.port.out.TokenHasherPort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * Refresh 토큰의 저장·조회 키를 만드는 SHA-256 어댑터.
 *
 * <h2>왜 BCrypt 가 아니라 SHA-256 인가</h2>
 * BCrypt 는 솔트 때문에 같은 입력에도 매번 다른 결과를 내므로 <b>조회 키로 쓸 수 없다</b>.
 * Refresh 토큰은 해시로 DB 를 찾아야 하니 결정적(deterministic) 해시가 필요하다.
 *
 * <p>솔트가 없어도 안전한 이유는 입력의 성질이 다르기 때문이다. 비밀번호는 사람이 만든
 * 저엔트로피 값이라 레인보우 테이블이 통하지만, JWT 는 서명이 포함된 고엔트로피 문자열이라
 * 해시를 거꾸로 되짚는 것이 현실적이지 않다.
 *
 * <p>Design Ref: §2.4 Port Signatures, §7 Security Considerations
 */
@Component
public class Sha256TokenHasherAdapter implements TokenHasherPort {

    private static final String ALGORITHM = "SHA-256";

    /**
     * {@inheritDoc}
     *
     * <p>{@link MessageDigest} 는 스레드 안전하지 않다. 필드로 재사용하지 않고
     * 호출마다 새로 얻는다 — 인스턴스 생성 비용이 동기화 비용보다 저렴하다.
     */
    @Override
    public String sha256Hex(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance(ALGORITHM);
            byte[] hashed = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            // 소문자 16진수 64자. refresh_token.token_hash 의 컬럼 길이와 일치한다.
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 은 모든 JVM 이 제공해야 하는 알고리즘이다(JCA 표준). 여기 오면 런타임이 깨진 것이다.
            throw new IllegalStateException("SHA-256 을 사용할 수 없습니다", e);
        }
    }
}
