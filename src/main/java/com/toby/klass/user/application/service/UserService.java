package com.toby.klass.user.application.service;

import com.toby.klass.user.application.dto.UserResult;
import com.toby.klass.user.application.port.in.FindUserUseCase;
import com.toby.klass.user.application.port.out.UserQueryPort;
import com.toby.klass.user.domain.error.UserError;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자 조회 유즈케이스 구현.
 *
 * <p>조회 전용이므로 클래스 수준에 {@code readOnly = true} 를 둔다. 쓰기 의도가 없음을
 * 드러내고, JPA 가 더티 체킹용 스냅샷을 만들지 않아 약간의 이득도 있다.
 *
 * <p>Design Ref: §2.2 인증된 요청 흐름, §4.2 GET /v1/users
 */
@Service
@Transactional(readOnly = true)
public class UserService implements FindUserUseCase {

    private final UserQueryPort userQueryPort;

    /**
     * 사용자 조회 포트를 주입받는다.
     *
     * @param userQueryPort 사용자 조회 포트
     */
    public UserService(UserQueryPort userQueryPort) {
        this.userQueryPort = userQueryPort;
    }

    /**
     * {@inheritDoc}
     *
     * <p>토큰 클레임만으로 응답하지 않고 DB 를 다시 읽는다. 응답에 {@code isEnabled}·
     * {@code createdAt} 이 포함되고, 권한 변경이 즉시 반영돼야 하기 때문이다.
     */
    @Override
    public UserResult findById(Long id) {
        return userQueryPort.findById(id)
                .map(UserResult::from)
                .orElseThrow(UserError.USER_NOT_FOUND::toException);
    }

}
