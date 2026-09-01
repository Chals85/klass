package com.toby.klass.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;

/**
 * 폐기된 Access 토큰 애그리거트 루트. 로그아웃한 Access 토큰의 즉시 무효화를 담당한다.
 *
 * <h2>왜 이 테이블이 필요한가</h2>
 * JWT 는 무상태(stateless)라서 서버 조회 없이 검증된다. 그 대가로 <b>한번 발급한 토큰을
 * 회수할 수단이 없다</b>. Refresh 토큰만 지우는 로그아웃은 "갱신 차단"일 뿐이어서,
 * 이미 발급된 Access 토큰은 남은 유효 기간(기본 30분) 동안 그대로 통과한다.
 *
 * <p>이 엔티티는 그 구멍을 막는다. 로그아웃 시 Access 토큰의 {@code jti} 를 여기에 남기고,
 * 검증 단계에서 대조해 폐기된 토큰을 거부한다.
 *
 * <h2>대가: 무상태가 아니게 된다</h2>
 * <b>이 설계는 공짜가 아니다.</b> 보호된 API 요청마다 조회가 한 번 추가되고, JWT 의
 * 핵심 장점인 "서버 상태 없이 검증"이 사라진다. 실서비스라면 이 조회는 Redis 같은
 * 인메모리 저장소로 빼는 것이 보통이다 — RDB 를 매 요청 때리는 구조는 트래픽이
 * 늘면 병목이 된다. 이 예제는 외부 인프라 없이 도는 것이 목표라 H2 에 둔다.
 *
 * <h2>왜 토큰 원문이 아니라 {@code jti} 인가</h2>
 * {@link RefreshToken} 은 원문의 SHA-256 해시를 저장하지만 이쪽은 {@code jti}(UUID)를
 * 그대로 쓴다. {@code jti} 는 <b>토큰을 식별할 뿐 그것만으로는 API 를 호출할 수 없는</b>
 * 값이라 유출돼도 위험하지 않고, 원문 없이 클레임만으로 대조할 수 있어 검증 경로가 짧다.
 *
 * <h2>왜 만료 시각을 함께 저장하는가</h2>
 * 이 행은 <b>영원히 보관할 필요가 없다</b>. 원 토큰이 만료되면 어차피 파싱 단계에서
 * {@code TOKEN_EXPIRED} 로 걸리므로 블랙리스트에 남겨둘 이유가 사라진다.
 * {@code expiresAt} 은 "언제부터 이 행을 지워도 되는가"를 알려주는 값이며,
 * {@code RevokedAccessTokenCleaner} 가 이 값을 기준으로 정리한다.
 * 이 정리가 없으면 테이블은 로그아웃 횟수만큼 무한히 자란다.
 *
 * <p>Design Ref: §3.1 Entity Definition, §2.2 로그아웃 흐름
 */
@Entity
@Table(
        name = "revoked_access_token",
        indexes = {
            @Index(name = "idx_revoked_access_token_jti", columnList = "jti", unique = true),
            @Index(name = "idx_revoked_access_token_expires_at", columnList = "expires_at")
        })
@Getter
public class RevokedAccessToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 원 토큰의 {@code jti} 클레임(UUID 문자열).
     *
     * <p>검증 경로에서 매 요청 조회되는 키다. unique 인덱스가 없으면 블랙리스트가
     * 커질수록 모든 API 가 함께 느려진다.
     */
    @Column(name = "jti", nullable = false, unique = true, length = 36)
    private String jti;

    /** 소유자. 객체 참조가 아닌 값 참조 — 애그리거트 경계를 지키기 위함이다. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * 원 토큰의 만료 시각(JWT 의 {@code exp} 와 같은 시점).
     *
     * <p>이 시각이 지나면 행은 불필요해진다. 정리 기준이지 검증 기준이 아니다 —
     * 만료 판정은 토큰 파싱이 이미 하고 있다.
     */
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    /** 폐기(로그아웃) 시각. 감사 추적용이다. */
    @Column(name = "revoked_at", nullable = false)
    private LocalDateTime revokedAt;

    /** JPA 가 리플렉션으로 인스턴스를 만들 때 쓴다. 직접 호출하지 말 것. */
    protected RevokedAccessToken() {
    }

    private RevokedAccessToken(String jti, Long userId,
                               LocalDateTime expiresAt, LocalDateTime revokedAt) {
        this.jti = jti;
        this.userId = userId;
        this.expiresAt = expiresAt;
        this.revokedAt = revokedAt;
    }

    /**
     * Access 토큰을 폐기 목록에 올린다.
     *
     * @param jti   원 토큰의 {@code jti}. 공백이면 안 된다
     * @param expiresAt 원 토큰의 만료 시각. 정리 기준이 되므로 반드시 실제 {@code exp} 와
     *                  같은 시점이어야 한다. 앞당겨 잡으면 아직 유효한 토큰이 블랙리스트에서
     *                  먼저 사라져 다시 통과한다
     * @return 아직 영속화되지 않은 폐기 기록
     * @throws IllegalArgumentException {@code jti} 가 비어 있는 경우
     */
    public static RevokedAccessToken revoke(String jti, Long userId,
                                            LocalDateTime expiresAt, LocalDateTime revokedAt) {
        if (jti == null || jti.isBlank()) {
            throw new IllegalArgumentException("토큰 식별자(jti)는 필수입니다");
        }
        return new RevokedAccessToken(jti, userId, expiresAt, revokedAt);
    }

    /**
     * 이 기록을 지워도 되는지 판단한다.
     *
     * <p>원 토큰이 이미 만료됐다면 파싱 단계에서 걸리므로 블랙리스트에 남길 이유가 없다.
     */
    public boolean isPurgeableAt(LocalDateTime now) {
        return !expiresAt.isAfter(now);
    }

}
