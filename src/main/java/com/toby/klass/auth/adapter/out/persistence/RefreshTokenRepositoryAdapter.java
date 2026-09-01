package com.toby.klass.auth.adapter.out.persistence;

import com.toby.klass.auth.application.port.out.RefreshTokenCommandPort;
import com.toby.klass.auth.application.port.out.RefreshTokenQueryPort;
import com.toby.klass.auth.domain.RefreshToken;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * {@link RefreshTokenQueryPort} 와 {@link RefreshTokenCommandPort} 의 영속 구현.
 *
 * <p>두 포트를 한 어댑터가 구현한다. 인터페이스를 나눈 것은 <b>호출하는 쪽</b>의 의도를
 * 드러내기 위함이고, 구현까지 나눌 실익은 없다. 같은 테이블을 다루므로 한곳에 두는 편이
 * 변경 시 놓칠 위험이 적다.
 *
 * <p>Design Ref: §2.3 Dependencies, §10.1 네이밍 규약
 */
@Component
public class RefreshTokenRepositoryAdapter implements RefreshTokenQueryPort, RefreshTokenCommandPort {

    private final RefreshTokenJpaRepository jpaRepository;

    /**
     * Spring Data 리포지토리를 주입받는다.
     *
     * @param jpaRepository 토큰 영속 접근
     */
    public RefreshTokenRepositoryAdapter(RefreshTokenJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Optional<RefreshToken> findByTokenHash(String tokenHash) {
        return jpaRepository.findByTokenHash(tokenHash);
    }

    @Override
    public RefreshToken save(RefreshToken token) {
        return jpaRepository.save(token);
    }

    @Override
    public long deleteByTokenHashAndUserId(String tokenHash, Long userId) {
        return jpaRepository.deleteByTokenHashAndUserId(tokenHash, userId);
    }

    @Override
    public long revokeAllByUserId(Long userId, LocalDateTime revokedAt) {
        return jpaRepository.revokeAllByUserId(userId, revokedAt);
    }
}
