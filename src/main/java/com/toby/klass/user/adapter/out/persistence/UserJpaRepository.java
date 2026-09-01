package com.toby.klass.user.adapter.out.persistence;

import com.toby.klass.user.domain.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 사용자 단건 조회·저장을 담당하는 Spring Data 리포지토리.
 *
 * <p>애플리케이션 계층은 이 인터페이스를 직접 쓰지 않는다. {@code UserQueryPort} 를
 * 통해서만 접근하며, {@code UserRepositoryAdapter} 가 둘을 잇는다.
 *
 * <p>Design Ref: §10.1 네이밍 규약 — Spring Data 인터페이스는 {X}JpaRepository
 */
public interface UserJpaRepository extends JpaRepository<User, Long> {

    /**
     * 로그인 아이디로 조회한다.
     *
     * @param username 로그인 아이디
     * @return 있으면 사용자
     */
    Optional<User> findByUsername(String username);

    /**
     * 아이디 존재 여부. 초기 사용자 시딩의 멱등성 판단에 쓴다.
     *
     * @param username 로그인 아이디
     * @return 존재하면 true
     */
    boolean existsByUsername(String username);
}
