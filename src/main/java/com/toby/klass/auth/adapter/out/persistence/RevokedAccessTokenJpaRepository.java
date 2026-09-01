package com.toby.klass.auth.adapter.out.persistence;

import com.toby.klass.auth.domain.RevokedAccessToken;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 폐기된 Access 토큰 영속 접근을 담당하는 Spring Data 리포지토리.
 *
 * <p>Design Ref: §10.1 네이밍 규약 — Spring Data 인터페이스는 {X}JpaRepository
 */
public interface RevokedAccessTokenJpaRepository extends JpaRepository<RevokedAccessToken, Long> {

    /**
     * 해당 {@code jti} 가 폐기 목록에 있는지 확인한다.
     *
     * <p>보호된 API 요청마다 실행되는 쿼리다. {@code jti} 의 unique 인덱스가
     * 이 조회를 받쳐준다.
     *
     * @param jti 토큰의 {@code jti}
     * @return 있으면 {@code true}
     */
    boolean existsByJti(String jti);

    /**
     * 만료된 폐기 기록을 삭제한다.
     *
     * <p>벌크 DELETE 이므로 영속성 컨텍스트를 우회한다. 정리 작업은 자체 트랜잭션에서
     * 단독으로 도는 것이 전제라 문제되지 않는다.
     *
     * @param now 이 시각 이하로 만료된 행이 대상
     * @return 삭제된 행 수
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from RevokedAccessToken r where r.expiresAt <= :now")
    int deleteByExpiresAtBefore(@Param("now") LocalDateTime now);
}
