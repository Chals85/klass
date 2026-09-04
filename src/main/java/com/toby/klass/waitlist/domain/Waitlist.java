package com.toby.klass.waitlist.domain;

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
import com.toby.klass.waitlist.domain.error.WaitlistError;
import java.time.LocalDateTime;
import lombok.Getter;

/**
 * 대기열 애그리거트 루트.
 *
 * <h2>왜 {@code Enrollment} 의 상태가 아니라 별도 테이블인가</h2>
 * 대기는 신청과 성격이 다르다. 좌석을 점유하지 않고, 순번({@code position})이라는 고유
 * 개념을 갖는다. 신청 상태 머신에 {@code WAITING} 을 섞으면 좌석 점유 판정이 상태값마다
 * 갈라져 복잡해진다 (ERD plan §7.2).
 *
 * <h2>좌석을 점유하지 않는다</h2>
 * {@code klass.enrollment_count} 는 이 테이블을 세지 않는다. 대기자가 좌석을 얻는 것은
 * {@link #promote} 로 {@code enrollment} 행이 새로 생기는 순간이며, 그 반납·취소는 전부
 * 그쪽에서 다룬다. 그래서 이 엔티티에는 카운터를 건드리는 메서드가 없다.
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
        indexes = @Index(name = "idx_waitlist_next", columnList = "klass_id, status, position"),
        check = {
            @CheckConstraint(name = "ck_waitlist_position", constraint = "position > 0"),
            @CheckConstraint(name = "ck_waitlist_promoted",
                    constraint = "status <> 'PROMOTED' OR promoted_at IS NOT NULL")
        })
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

    // ── 상태 전이 ────────────────────────────────────────────────────────────

    /**
     * 승격한다. {@code WAITING → PROMOTED}.
     *
     * <p>이 행은 여기서 <b>종착</b>한다. 실제 좌석은 새로 만들어지는 {@code enrollment}
     * {@code PENDING} 행이 갖고, 이후의 취소는 그쪽에서 다룬다 (ERD 정본 §3.4).
     *
     * <p>호출자는 반드시 <b>{@code klass} 배타 락 아래</b>에서 이 메서드와 {@code enrollment}
     * INSERT · 카운터 증가를 <b>한 트랜잭션으로</b> 끝내야 한다. 락을 놓고 승격하면 그 틈에
     * 일반 신청자가 좌석을 채간다 (ERD 정본 §4.4 핵심 성질 2번).
     *
     * @param now 승격 시각. {@code ck_waitlist_promoted} 가 값을 강제한다
     * @throws com.toby.klass.common.domain.error.BusinessException {@code WAITING} 이 아닌 경우
     */
    public void promote(LocalDateTime now) {
        verifyWaiting();
        this.status = WaitlistStatus.PROMOTED;
        this.promotedAt = now;
    }

    /**
     * 대기를 종료한다. {@code WAITING → CANCELLED}.
     *
     * <h4>세 가지 원인이 한 메서드를 쓴다</h4>
     * 사용자의 자발적 포기, 승격 시 부적격 판정, 강의 마감 시 일괄 정리 — <b>ERD 정본 §3.3 이
     * "세 원인은 구분해 저장하지 않는다"고 확정</b>했으므로 메서드를 나눌 근거가 없다.
     * 의미는 호출부가 갖는다.
     *
     * <p><b>시각을 받지 않는다.</b> {@code waitlist} 에 {@code cancelled_at} 컬럼이 없기
     * 때문이다 (ERD 정본 §3.2.7). {@link #promote} 만 시각을 기록한다.
     *
     * <p>취소된 행의 {@code position} 은 <b>재사용하지 않고 gap 으로 남긴다</b> — 순번 재배열은
     * 여러 행을 갱신해 락 범위를 넓히고, 순번의 절대값이 사용자에게 의미를 갖지 않는다.
     *
     * @throws com.toby.klass.common.domain.error.BusinessException {@code WAITING} 이 아닌 경우
     */
    public void cancel() {
        verifyWaiting();
        this.status = WaitlistStatus.CANCELLED;
    }

    // ── 판별 ─────────────────────────────────────────────────────────────────

    /**
     * 이 대기의 주인인지 판별한다.
     *
     * <p>{@code user} 는 {@code LAZY} 프록시지만 {@code getId()} 는 초기화를 유발하지 않는다.
     *
     * @param userId 판별 대상 사용자 id. {@code null} 이면 항상 {@code false}
     */
    public boolean isOwnedBy(Long userId) {
        return userId != null && this.user.getId().equals(userId);
    }

    /** 아직 대기 중인지 판별한다. {@code PROMOTED} 와 {@code CANCELLED} 는 종착이다. */
    public boolean isWaiting() {
        return this.status == WaitlistStatus.WAITING;
    }

    // ── 불변식 ───────────────────────────────────────────────────────────────

    /**
     * <b>승격과 포기가 같은 검사를 공유한다.</b> 둘은 {@code WAITING} 행을 두고 경합하는
     * 관계라, 먼저 커밋된 쪽이 상태를 바꾸면 나머지는 반드시 여기서 걸려야 한다. 검사를
     * 한쪽에만 두면 그 경합이 조용히 통과한다 (ERD 정본 §4.9 3번).
     */
    private void verifyWaiting() {
        if (!isWaiting()) {
            throw WaitlistError.WAITLIST_NOT_WAITING.toException();
        }
    }

}
