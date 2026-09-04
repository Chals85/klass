package com.toby.klass.auth.application.port.out;

import com.toby.klass.auth.domain.RefreshToken;
import java.time.LocalDateTime;

/**
 * Refresh 토큰 변경 능력.
 *
 * <p>Design Ref: §2.3 의존성
 */
public interface RefreshTokenCommandPort {

    /**
     * 새 토큰 기록을 저장한다.
     *
     * @param token {@code RefreshToken.issue(...)} 로 만든 기록
     * @return id 가 채워진 영속 상태의 기록
     */
    RefreshToken save(RefreshToken token);

    /**
     * 로그아웃 — 해당 토큰 기록을 <b>삭제</b>한다.
     *
     * <h4>왜 폐기가 아니라 삭제인가</h4>
     * {@code isRevoked = true} 로 남겨두면 그 토큰으로 재발급을 시도했을 때
     * {@code RefreshToken.rotate()} 가 <b>재사용으로 오판</b>해 정상 사용자의 모든 토큰을
     * 무효화한다. 로그아웃은 사용자의 명시적 의사이므로 탈취 신호로 취급하면 안 된다.
     * 행을 지우면 이후 시도는 {@code REFRESH_TOKEN_NOT_FOUND} 로 끝난다.
     *
     * @param userId    소유자 확인용. 남의 토큰을 지우지 못하게 한다
     * @return 삭제된 행 수. <b>0 이어도 정상</b>이다 — 로그아웃은 멱등이어야 하고,
     *         토큰의 존재 여부를 응답으로 알려주지 않는다
     */
    long deleteByTokenHashAndUserId(String tokenHash, Long userId);

    /**
     * 침해 대응 — 해당 사용자의 유효한 모든 Refresh 토큰을 무효화한다.
     *
     * <p>재사용이 감지됐을 때 호출한다. <b>반드시 별도 트랜잭션
     * ({@code REQUIRES_NEW})에서 실행해야 한다</b> — 감지 예외를 재전파하면 원래
     * 트랜잭션이 롤백되면서 이 UPDATE 까지 함께 사라지기 때문이다.
     *
     * <p>벌크 UPDATE 이므로 영속성 컨텍스트를 우회한다. 같은 트랜잭션에서 로딩해 둔
     * 엔티티가 있다면 그 상태는 갱신되지 않는다.
     */
    long revokeAllByUserId(Long userId, LocalDateTime revokedAt);
}
