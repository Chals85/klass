package com.toby.klass.user.application.port.out;

import com.toby.klass.user.domain.User;
import java.util.Optional;

/**
 * 사용자 조회 능력.
 * 구현은 {@code adapter.out.persistence.UserRepositoryAdapter} 다.
 *
 * <p>Design Ref: §2.3 의존성
 */
public interface UserQueryPort {

    /**
     * id 로 사용자를 찾는다.
     *
     * <p>{@code GET /v1/users/me} 가 쓴다. 토큰 클레임만으로 응답하지 않고 DB 를
     * 다시 읽는 이유는 응답에 {@code isEnabled}·{@code createdAt} 이 포함되고,
     * 권한 변경이 즉시 반영돼야 하기 때문이다.
     */
    Optional<User> findById(Long id);

    /**
     * 아이디로 사용자를 찾는다. 로그인 시 {@code DomainUserDetailsService} 가 쓴다.
     */
    Optional<User> findByUsername(String username);
}
