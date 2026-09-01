package com.toby.klass.waitlist.domain;

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
import org.hibernate.annotations.Check;

/**
 * 대기열 애그리거트 루트.
 *
 * <h2>왜 {@code Enrollment} 의 상태가 아니라 별도 테이블인가</h2>
 * 대기는 신청과 성격이 다르다. 좌석을 점유하지 않고, 순번({@code position})이라는 고유
 * 개념을 갖는다. 신청 상태 머신에 {@code WAITING} 을 섞으면 좌석 점유 판정이 상태값마다
 * 갈라져 복잡해진다 (ERD 정본 §7.2).
 *
 * <p><b>1차 범위는 스키마 확정까지다.</b> 등록·승격·포기 메서드는 §4 동시성 규약과 함께
 * 2차에서 붙인다.
 *
 * <p>Design Ref: §3.1 엔티티 목록, §3.6 제약, ERD 정본 §3.2.7 · §3.7
 */
@Entity
@Table(
        name = "waitlist",
        uniqueConstraints = {
            // 같은 강의 안에서 순번은 유일하다
            @UniqueConstraint(name = "uq_waitlist_position", columnNames = {"klass_id", "position"}),
            // 활성 대기 중복 차단 (생성 컬럼 기반, §3.6.1)
            @UniqueConstraint(name = "uq_waitlist_waiting", columnNames = {"klass_id", "waiting_user_key"})
        },
        indexes = @Index(name = "idx_waitlist_next", columnList = "klass_id, status, position"))
@Check(name = "ck_waitlist_position", constraints = "position > 0")
@Check(name = "ck_waitlist_promoted", constraints = "status <> 'PROMOTED' OR promoted_at IS NOT NULL")
@Getter
public class Waitlist {

    /** PK. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 대상 강의. {@code LAZY} — 승격 대상을 찾을 때 강의까지 로딩할 필요가 없다. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "klass_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_waitlist_klass"))
    private Klass klass;

    /**
     * 대기자. 사용자 <b>자신의 상태</b> 기록이므로 {@code user_id} 다 (ERD 정본 §3.1.2).
     *
     * <p>{@code waiting_user_key} 생성 컬럼의 원천이다.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_waitlist_user"))
    private User user;

    /**
     * 대기 순번. 1 부터 시작한다 ({@code ck_waitlist_position}).
     *
     * <p>{@code position} 은 SQL:2016 예약어이자 함수명과 겹친다. H2 에서는 큰따옴표 없이
     * 쓸 수 있음을 module-4 에서 확인했다 (ERD 정본 §3.7 예약어 점검). 실 DB 전환 시
     * 다시 확인해야 한다.
     */
    @Column(nullable = false)
    private int position;

    /**
     * 대기 상태. <b>{@code WAITING} 만 활성</b>이며, 그 값이 {@code waiting_user_key} 를 좌우한다.
     *
     * <p>{@code PROMOTED} 는 이미 {@code enrollment} 로 넘어간 종착 상태라 대기로 세지 않는다.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WaitlistStatus status;

    /** 등록 시각. 승격 순서의 근거가 아니다 — 순서는 {@link #position} 이 정한다. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 승격 시각. {@code PROMOTED} 이면 반드시 값이 있다 ({@code ck_waitlist_promoted}). */
    @Column(name = "promoted_at")
    private LocalDateTime promotedAt;

    /**
     * 부분 유니크 대체 컬럼. <b>DB 가 계산한다.</b>
     *
     * <p>{@code WAITING} 이면 {@code user_id}, 그 외에는 NULL 이다. 대기를 포기했다가 다시
     * 등록하는 것은 허용하면서 활성 중복만 막는다.
     *
     * <p>{@code Enrollment.activeUserKey} 와 조건이 다르다 — 여기는 <b>WAITING 만</b> 활성이다
     * (PROMOTED 도 종착 상태이므로 제외).
     *
     * <p>{@code STORED} 미사용 근거는 Design §12 D-9 참조.
     */
    @Column(
            name = "waiting_user_key",
            insertable = false,
            updatable = false,
            columnDefinition =
                    "BIGINT GENERATED ALWAYS AS (CASE WHEN status = 'WAITING' THEN user_id END)")
    private Long waitingUserKey;

    /** JPA 가 리플렉션으로 인스턴스를 만들 때 쓴다. 직접 호출하지 말 것. */
    protected Waitlist() {
    }

    private Waitlist(Klass klass, User user, int position, LocalDateTime createdAt) {
        this.klass = klass;
        this.user = user;
        this.position = position;
        this.status = WaitlistStatus.WAITING;
        this.createdAt = createdAt;
    }

    /**
     * 대기열에 등록한다. 상태는 항상 {@link WaitlistStatus#WAITING} 에서 시작한다.
     *
     * @param position  대기 순번. 1 이상이어야 한다
     * @param createdAt 등록 시각. {@code LocalDateTime.now(clock)} 으로 얻은 값
     * @return 아직 영속화되지 않은 새 대기 행
     */
    public static Waitlist enqueue(Klass klass, User user, int position, LocalDateTime createdAt) {
        return new Waitlist(klass, user, position, createdAt);
    }

}
