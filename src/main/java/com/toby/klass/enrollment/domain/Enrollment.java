package com.toby.klass.enrollment.domain;

import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import com.toby.klass.klass.domain.Klass;
import com.toby.klass.user.domain.User;
import java.time.LocalDateTime;
import lombok.Getter;

/**
 * 수강 신청 애그리거트 루트.
 *
 * <h2>좌석 점유의 단일 소재지</h2>
 * 좌석을 점유하는 행은 <b>이 테이블에만</b> 존재한다. 대기열은 좌석을 점유하지 않으며,
 * 승격되면 여기에 {@code PENDING} 행이 새로 생긴다. 점유가 두 테이블에 흩어지면 정합성
 * 검증과 락 대상이 함께 늘어난다 (ERD 정본 §1.1).
 *
 * <p><b>1차 범위는 스키마 확정까지다.</b> 신청·확정·취소 메서드는 §4 동시성 규약(비관적 락,
 * 카운터 갱신)과 함께 2차에서 붙인다.
 *
 * <p>Design Ref: §3.1 엔티티 목록, §3.6 제약, ERD 정본 §3.2.6 · §3.7
 */
@Entity
@Table(
        name = "enrollment",
        // 활성 중복 신청 차단. 생성 컬럼이 NULL 을 만들어 취소 후 재신청은 허용된다 (§3.6.1)
        uniqueConstraints = @UniqueConstraint(
                name = "uq_enrollment_active",
                columnNames = {"klass_id", "active_user_key"}),
        indexes = {
            @Index(name = "idx_enrollment_user", columnList = "user_id, id DESC"),
            @Index(name = "idx_enrollment_klass_status", columnList = "klass_id, status, id DESC"),
            @Index(name = "idx_enrollment_expiry", columnList = "expires_at")
        },
        // 상태별 타임스탬프 정합성. 상태와 시각이 어긋난 행이 DB 에 들어올 수 없게 한다
        check = {
            @CheckConstraint(name = "ck_enrollment_pending",
                    constraint = "(status = 'PENDING' AND expires_at IS NOT NULL) "
                            + "OR (status <> 'PENDING' AND expires_at IS NULL)"),
            @CheckConstraint(name = "ck_enrollment_confirmed",
                    constraint = "status <> 'CONFIRMED' OR confirmed_at IS NOT NULL"),
            @CheckConstraint(name = "ck_enrollment_cancelled",
                    constraint = "status <> 'CANCELLED' OR cancelled_at IS NOT NULL")
        })
@Getter
public class Enrollment {

    /** PK. 내 신청 목록의 커서 페이지네이션 정렬 키다. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 대상 강의.
     *
     * <p>{@code LAZY} 다. 신청 목록을 읽을 때 강의까지 매번 로딩할 이유가 없다 —
     * 필요하면 fetch join 으로 명시한다.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "klass_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_enrollment_klass"))
    private Klass klass;

    /**
     * 신청자. 사용자 <b>자신의 행위</b> 기록이므로 {@code user_id} 다 (ERD 정본 §3.1.2).
     *
     * <p>이 컬럼은 {@code active_user_key} 생성 컬럼의 원천이기도 하다 — 컬럼명이 바뀌면
     * 그쪽 {@code columnDefinition} 도 함께 고쳐야 한다.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_enrollment_user"))
    private User user;

    /**
     * 신청 상태. <b>{@code PENDING} 과 {@code CONFIRMED} 가 좌석을 점유한다.</b>
     *
     * <p>이 값이 {@code active_user_key} 와 세 개의 CHECK 제약을 함께 좌우하므로,
     * 상태를 바꿀 때는 대응하는 타임스탬프도 같은 트랜잭션에서 맞춰야 한다.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EnrollmentStatus status;

    /** 신청 출처. PENDING 만료 기한을 가르는 값이다 (DIRECT 30분 / WAITLIST 10분). */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EnrollmentSource source;

    /** 신청 시각. 주입된 {@code Clock} 으로 채운다 ({@code updatable = false}). */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * PENDING 만료 예정 시각. {@code PENDING} 일 때만 값이 있다 ({@code ck_enrollment_pending}).
     *
     * <p>이 값이 없으면 결제하지 않은 신청이 좌석을 영구히 붙잡는다. 기한은 출처별로 다르다
     * — {@code DIRECT} 30분 / {@code WAITLIST} 10분 (ERD 정본 §2 ⑥).
     */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    /** 결제 확정 시각. {@code CONFIRMED} 이면 반드시 값이 있다 ({@code ck_enrollment_confirmed}). */
    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    /**
     * 취소 시각. {@code CANCELLED} 이면 반드시 값이 있다 ({@code ck_enrollment_cancelled}).
     *
     * <p>사용자 취소인지 만료인지는 구분해 저장하지 않는다 — ERD 정본 §2 ⑦ 의 열린 미결이다.
     */
    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    /**
     * 부분 유니크 대체 컬럼. <b>DB 가 계산한다.</b>
     *
     * <p>활성({@code PENDING}/{@code CONFIRMED})이면 {@code user_id}, 취소면 NULL 이 된다.
     * UNIQUE 인덱스에서 NULL 은 서로 충돌하지 않으므로, 취소 후 재신청을 허용하면서 활성
     * 중복만 DB 가 막는다.
     *
     * <p><b>{@code STORED} 를 붙이지 않는다</b> — H2 2.x 가 그 키워드를 거부한다
     * (module-1 스파이크에서 확인, Design §12 D-9). 실 DB 전환 시에는 되붙여야 한다.
     *
     * <p>DB 가 값을 채우므로 {@code insertable}/{@code updatable} 을 꺼야 한다. 그렇지 않으면
     * Hibernate 가 INSERT 문에 이 컬럼을 넣어 DB 가 거부한다.
     */
    @Column(
            name = "active_user_key",
            insertable = false,
            updatable = false,
            columnDefinition =
                    "BIGINT GENERATED ALWAYS AS (CASE WHEN status <> 'CANCELLED' THEN user_id END)")
    private Long activeUserKey;

    /** JPA 가 리플렉션으로 인스턴스를 만들 때 쓴다. 직접 호출하지 말 것. */
    protected Enrollment() {
    }

    private Enrollment(Klass klass, User user, EnrollmentSource source,
                       LocalDateTime createdAt, LocalDateTime expiresAt) {
        this.klass = klass;
        this.user = user;
        this.status = EnrollmentStatus.PENDING;
        this.source = source;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    /**
     * 신청을 만든다. 상태는 항상 {@link EnrollmentStatus#PENDING} 에서 시작한다.
     *
     * @param klass     대상 강의
     * @param user      신청자
     * @param source    출처. 만료 기한을 가르는 값이다
     * @param createdAt 신청 시각. {@code LocalDateTime.now(clock)} 으로 얻은 값
     * @param expiresAt 만료 예정 시각. {@code null} 이면 {@code ck_enrollment_pending} 이 거부한다
     * @return 아직 영속화되지 않은 새 신청
     */
    public static Enrollment apply(Klass klass, User user, EnrollmentSource source,
                                   LocalDateTime createdAt, LocalDateTime expiresAt) {
        return new Enrollment(klass, user, source, createdAt, expiresAt);
    }

}
