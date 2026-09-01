package com.toby.klass.auth.adapter.out.persistence;

import com.toby.klass.auth.application.port.out.RevokedAccessTokenCommandPort;
import com.toby.klass.auth.application.port.out.RevokedAccessTokenQueryPort;
import com.toby.klass.auth.domain.RevokedAccessToken;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

/**
 * {@link RevokedAccessTokenQueryPort} 와 {@link RevokedAccessTokenCommandPort} 의 영속 구현.
 *
 * <p>{@code RefreshTokenRepositoryAdapter} 와 같은 이유로 두 포트를 한 어댑터가 구현한다.
 * 인터페이스를 나눈 것은 호출하는 쪽의 의도를 드러내기 위함이고, 같은 테이블을 다루는
 * 구현까지 나눌 실익은 없다.
 *
 * <p>Design Ref: §2.3 Dependencies, §10.1 네이밍 규약
 */
@Component
public class RevokedAccessTokenRepositoryAdapter
        implements RevokedAccessTokenQueryPort, RevokedAccessTokenCommandPort {

    private final RevokedAccessTokenJpaRepository jpaRepository;

    /**
     * Spring Data 리포지토리를 주입받는다.
     *
     * @param jpaRepository 폐기 토큰 영속 접근
     */
    public RevokedAccessTokenRepositoryAdapter(RevokedAccessTokenJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public boolean isRevoked(String jti) {
        return jti != null && jpaRepository.existsByJti(jti);
    }

    /**
     * {@inheritDoc}
     *
     * <p>이미 등록된 {@code jti} 면 저장을 건너뛴다. INSERT 를 시도하고 unique 제약
     * 위반을 잡는 방식이 아니라 <b>먼저 확인</b>하는 이유는, 제약 위반 예외가 발생하면
     * 현재 트랜잭션이 rollback-only 로 마킹되어 이후 커밋이 통째로 실패하기 때문이다.
     * 정상 흐름에서 예외를 발생시키면 안 된다.
     */
    @Override
    public void revoke(RevokedAccessToken token) {
        if (jpaRepository.existsByJti(token.getJti())) {
            return;
        }
        jpaRepository.save(token);
    }

    @Override
    public long deleteExpired(LocalDateTime now) {
        return jpaRepository.deleteByExpiresAtBefore(now);
    }
}
