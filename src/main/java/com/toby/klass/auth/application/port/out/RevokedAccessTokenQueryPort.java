package com.toby.klass.auth.application.port.out;

/**
 * 폐기된 Access 토큰 조회 능력.
 *
 * <p><b>이 포트는 보호된 API 요청마다 호출된다.</b> 다른 포트와 성격이 다르므로
 * 구현체는 조회 비용을 특히 신경 써야 한다 — 인덱스 없는 구현을 끼우면 모든 API 가
 * 함께 느려진다. 실서비스라면 이 자리에 Redis 어댑터가 들어간다.
 *
 * <p>Design Ref: §2.3 의존성
 */
public interface RevokedAccessTokenQueryPort {

    /**
     * 해당 토큰이 폐기(로그아웃)됐는지 확인한다.
     *
     * <p>만료 여부는 보지 않는다. 만료 판정은 토큰 파싱이 이미 끝낸 뒤이므로
     * 여기까지 온 토큰은 아직 유효한 토큰이다.
     */
    boolean isRevoked(String jti);
}
