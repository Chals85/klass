package com.toby.klass.auth.adapter.out.persistence;

import com.toby.klass.auth.domain.RefreshToken;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Refresh 토큰 영속 접근을 담당하는 Spring Data 리포지토리.
 *
 * <p>Design Ref: §10.1 네이밍 규약 — Spring Data 인터페이스는 {X}JpaRepository
 */
public interface RefreshTokenJpaRepository extends JpaRepository<RefreshToken, Long> {

    /**
     * 해시로 토큰 기록을 찾는다. <b>폐기된 것도 포함</b>해서 돌려준다 —
     * 재사용 감지를 하려면 폐기된 기록이 필요하다.
     *
     * @param tokenHash SHA-256 hex 64자
     * @return 있으면 토큰 기록
     */
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * 로그아웃 — 소유자가 일치하는 토큰 기록을 삭제한다.
     *
     * @param tokenHash 삭제할 토큰의 해시
     * @param userId    소유자 확인용
     * @return 삭제된 행 수 (0 또는 1)
     */
    long deleteByTokenHashAndUserId(String tokenHash, Long userId);

    /**
     * 침해 대응 — 해당 사용자의 <b>아직 유효한</b> 토큰을 모두 무효화한다.
     *
     * <p>이미 폐기된 행은 건드리지 않는다({@code isRevoked = false} 조건). 최초 폐기
     * 시각을 덮어쓰면 언제 탈취가 시작됐는지 추적할 수 없기 때문이다.
     *
     * <p>{@code @Modifying} 벌크 UPDATE 는 영속성 컨텍스트를 우회한다. 같은 트랜잭션에
     * 로딩된 엔티티가 있어도 그 상태는 갱신되지 않으므로 주의한다.
     *
     * @param userId    대상 사용자
     * @param revokedAt 무효화 시각
     * @return 무효화된 행 수
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update RefreshToken r
               set r.isRevoked = true, r.revokedAt = :revokedAt
             where r.userId = :userId
               and r.isRevoked = false
            """)
    int revokeAllByUserId(@Param("userId") Long userId, @Param("revokedAt") LocalDateTime revokedAt);
}
