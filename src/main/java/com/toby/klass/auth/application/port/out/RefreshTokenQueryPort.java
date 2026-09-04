package com.toby.klass.auth.application.port.out;

import com.toby.klass.auth.domain.RefreshToken;
import java.util.Optional;

/**
 * Refresh 토큰 조회 능력.
 * 구현은 {@code adapter.out.persistence.RefreshTokenRepositoryAdapter} 가
 * {@link RefreshTokenCommandPort} 와 함께 담당한다.
 *
 * <p>조회와 변경을 두 인터페이스로 나눈 것은 CQRS 의 가벼운 적용이다. 호출하는 쪽의
 * 의도가 시그니처에 드러나고, 읽기 전용 트랜잭션 경계를 잡기 쉬워진다.
 * 구현은 하나로 합쳐도 무방하다.
 *
 * <p>Design Ref: §2.3 의존성, §10.1 네이밍 규약
 */
public interface RefreshTokenQueryPort {

    /**
     * 해시로 토큰 기록을 찾는다.
     *
     * <p>토큰 <b>원문</b>이 아니라 {@code TokenHasherPort} 로 만든 해시를 넘겨야 한다.
     * DB 에는 원문이 저장되지 않는다.
     *
     * @param tokenHash SHA-256 hex 64자
     * @return 있으면 토큰 기록. 폐기된 것도 포함해서 돌려준다 — 재사용 감지를 하려면
     *         폐기된 기록이 필요하기 때문이다
     */
    Optional<RefreshToken> findByTokenHash(String tokenHash);
}
