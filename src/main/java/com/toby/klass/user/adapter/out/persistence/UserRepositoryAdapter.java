package com.toby.klass.user.adapter.out.persistence;

import com.toby.klass.user.application.port.out.UserQueryPort;
import com.toby.klass.user.domain.User;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * {@link UserQueryPort} 의 영속 구현.
 *
 * <p>Spring Data 리포지토리에 위임하는 얇은 계층이다. 애플리케이션 계층이 Spring Data 를
 * 직접 알지 않게 하는 것이 이 어댑터의 역할이다.
 *
 * <p>Design Ref: §2.3 Dependencies, §10.1 네이밍 규약 — 어댑터는 {X}RepositoryAdapter
 */
@Component
public class UserRepositoryAdapter implements UserQueryPort {

    private final UserJpaRepository jpaRepository;

    /**
     * 사용자 영속 접근 리포지토리를 주입받는다.
     */
    public UserRepositoryAdapter(UserJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Optional<User> findById(Long id) {
        return jpaRepository.findById(id);
    }

    @Override
    public Optional<User> findByUsername(String username) {
        return jpaRepository.findByUsername(username);
    }
}
