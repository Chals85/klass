package com.toby.klass.auth.application.port.in;

import com.toby.klass.auth.application.dto.ReissueCommand;
import com.toby.klass.auth.application.dto.TokenResult;

/**
 * 토큰 재발급 유즈케이스.
 *
 * <p>Design Ref: §2.2 재발급 흐름
 */
public interface ReissueTokenUseCase {

    /**
     * Refresh 토큰을 회전시키고 새 토큰 쌍을 발급한다.
     *
     * <p>기존 Refresh 토큰은 폐기되므로 <b>한 번만 쓸 수 있다</b>. 폐기된 토큰이 다시
     * 들어오면 탈취로 간주해 해당 사용자의 모든 토큰을 무효화한다.
     *
     * @return 새 Access/Refresh 토큰
     * @throws com.toby.klass.common.domain.error.BusinessException
     *         {@code TOKEN_EXPIRED} / {@code TOKEN_INVALID} / {@code TOKEN_TYPE_MISMATCH} 토큰 자체의 문제 /
     *         {@code REFRESH_TOKEN_NOT_FOUND} DB 에 없음(로그아웃된 토큰 포함) /
     *         {@code REFRESH_TOKEN_REUSED} 이미 회전된 토큰 재사용
     */
    TokenResult reissue(ReissueCommand command);
}
