package com.toby.klass.auth.application.port.out;

import com.toby.klass.auth.domain.RevokedAccessToken;
import java.time.LocalDateTime;

/**
 * 폐기된 Access 토큰 변경 능력.
 *
 * <p>Design Ref: §2.4 Port Signatures
 */
public interface RevokedAccessTokenCommandPort {

    /**
     * 폐기 기록을 저장한다.
     *
     * <p><b>멱등해야 한다.</b> 이미 등록된 {@code jti} 를 다시 넘겨도 예외를 던지지 않는다.
     * 로그아웃은 멱등한 연산이고, 여기서 unique 제약 위반이 새어나가면 그 계약이 깨진다.
     *
     * @param token {@code RevokedAccessToken.revoke(...)} 로 만든 기록
     */
    void revoke(RevokedAccessToken token);

    /**
     * 만료된 폐기 기록을 정리한다.
     *
     * <h4>지워도 안전한 이유</h4>
     * 원 토큰이 만료됐다면 토큰 파싱이 {@code TOKEN_EXPIRED} 로 먼저 거부하므로,
     * 블랙리스트에서 사라져도 그 토큰이 되살아나지 않는다.
     *
     * <p>이 정리가 없으면 테이블은 로그아웃 횟수만큼 무한히 자란다.
     *
     * @param now 현재 시각. 만료 시각이 이 값 이하인 행이 대상이다
     * @return 삭제된 행 수
     */
    long deleteExpired(LocalDateTime now);
}
