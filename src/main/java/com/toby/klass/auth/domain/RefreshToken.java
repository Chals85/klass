package com.toby.klass.auth.domain;

import com.toby.klass.auth.domain.error.AuthError;
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
 * Refresh 토큰 애그리거트 루트.
 *
 * <h2>왜 {@code User} 를 객체로 참조하지 않는가</h2>
 * {@code @ManyToOne User} 대신 {@code userId} 값만 들고 있다. 서로 다른 애그리거트를
 * 객체 참조로 묶으면 트랜잭션 경계가 흐려지고, 토큰 하나를 읽을 때 사용자까지 로딩된다.
 * DDL 에도 FK 제약을 걸지 않는다.
 *
 * <h2>왜 토큰 원문이 아니라 해시를 저장하는가</h2>
 * DB 가 유출돼도 저장된 값으로는 API 를 호출할 수 없다. 조회는 원문을 SHA-256 으로
 * 해싱해 {@code token_hash} 로 찾는다.
 *
 * <h2>회전과 재사용 감지가 이 클래스에 있는 이유</h2>
 * {@link #rotate(LocalDateTime)} 가 이 설계의 핵심이다. 서비스가
 * {@code if (token.isRevoked()) throw ...} 를 쓰기 시작하면 규칙이 도메인 밖으로
 * 새어나간 신호이므로 되돌려야 한다.
 *
 * <p>Design Ref: §3.1 Entity Definition, §2.2 재발급 흐름
 */
@Entity
@Table(
        name = "refresh_token",
        indexes = {
            @Index(name = "idx_refresh_token_user_id", columnList = "user_id"),
            @Index(name = "idx_refresh_token_hash", columnList = "token_hash", unique = true)
        })
@Getter
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 소유자. 객체 참조가 아닌 값 참조 — 애그리거트 경계를 지키기 위함이다. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 토큰 원문의 SHA-256 hex(64자). 원문은 저장하지 않는다. */
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(nullable = false)
    private LocalDateTime issuedAt;

    /**
     * 만료 시각.
     *
     * <p>JWT 의 {@code exp} 와 같은 값이지만 별도로 들고 있다. 파싱 단계에서 이미
     * 만료를 검사하므로 중복처럼 보이나, 두 값이 어긋나는 상황(수동 DB 편집, 시계 오차)을
     * {@link #rotate(LocalDateTime)} 가 막아준다.
     */
    @Column(nullable = false)
    private LocalDateTime expiresAt;

    /** 회전되어 폐기됐는지 여부. 폐기된 토큰의 재사용이 곧 탈취 신호다. */
    @Column(name = "is_revoked", nullable = false)
    private boolean isRevoked = false;

    /** 폐기 시각. 아직 유효하면 {@code null}. */
    private LocalDateTime revokedAt;

    /** JPA 가 리플렉션으로 인스턴스를 만들 때 쓴다. 직접 호출하지 말 것. */
    protected RefreshToken() {
    }

    private RefreshToken(Long userId, String tokenHash, LocalDateTime issuedAt, LocalDateTime expiresAt) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
        this.isRevoked = false;
        this.revokedAt = null;
    }

    /**
     * 새 Refresh 토큰 기록을 만든다.
     *
     * @param tokenHash 토큰 원문의 SHA-256 hex. <b>원문을 넘기면 안 된다</b>
     * @param issuedAt  발급 시각. 토큰 생성 포트가 돌려준 {@code Instant} 를 변환한 값이다
     * @param expiresAt 만료 시각. 마찬가지로 변환한 값이며, JWT 의 {@code exp} 와 같은 시점을 가리킨다
     * @return 아직 영속화되지 않은 토큰 기록
     * @throws IllegalArgumentException 만료가 발급보다 이른 경우
     */
    public static RefreshToken issue(Long userId, String tokenHash, LocalDateTime issuedAt, LocalDateTime expiresAt) {
        if (!expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("만료 시각은 발급 시각보다 뒤여야 합니다");
        }
        return new RefreshToken(userId, tokenHash, issuedAt, expiresAt);
    }

    /**
     * 이 토큰을 소비하고 폐기한다. 재발급(회전)의 첫 단계다.
     *
     * <p>성공하면 {@code isRevoked} 가 {@code true} 가 되고, 영속 상태라면 더티 체킹으로
     * UPDATE 된다. 호출자는 이후 새 토큰 쌍을 발급한다.
     *
     * <p><b>재사용 감지</b>: 이미 폐기된 토큰에 다시 호출하면
     * {@link AuthError#REFRESH_TOKEN_REUSED} 를 던진다. 호출자는 이 예외를 잡아
     * 해당 사용자의 모든 토큰을 무효화해야 하는데, <b>같은 트랜잭션에서 하면 안 된다</b>
     * — 예외를 재전파하는 순간 무효화까지 롤백된다. 별도 트랜잭션
     * ({@code REQUIRES_NEW})으로 처리한다.
     *
     * @param now 현재 시각. {@code LocalDateTime.now(clock)} 으로 얻는다
     * @throws com.toby.klass.common.domain.error.BusinessException
     *         {@link AuthError#REFRESH_TOKEN_REUSED} 이미 폐기된 토큰인 경우 /
     *         {@link AuthError#REFRESH_TOKEN_EXPIRED} 만료된 경우
     */
    public void rotate(LocalDateTime now) {
        if (isRevoked) {
            throw AuthError.REFRESH_TOKEN_REUSED.toException();
        }
        // 정상 경로에서는 토큰 파싱이 exp 를 먼저 검사해 TOKEN_EXPIRED 로 끝난다.
        // 여기 걸린다면 DB 의 expires_at 과 JWT 의 exp 가 어긋났다는 뜻이다.
        if (expiresAt.isBefore(now)) {
            throw AuthError.REFRESH_TOKEN_EXPIRED.toException();
        }
        this.isRevoked = true;
        this.revokedAt = now;
    }

}
