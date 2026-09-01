package com.toby.klass.user.application.port.in;

import com.toby.klass.user.application.dto.UserResult;

/**
 * 사용자 조회 유즈케이스.
 *
 * <p>Design Ref: §4.2 GET /v1/users/me
 */
public interface FindUserUseCase {

    /**
     * id 로 사용자를 조회한다.
     *
     * @param id 사용자 PK
     * @return 사용자 정보
     * @throws com.toby.klass.common.domain.error.BusinessException
     *         {@code USER_NOT_FOUND} 토큰은 유효하나 사용자가 삭제된 경우
     */
    UserResult findById(Long id);
}
