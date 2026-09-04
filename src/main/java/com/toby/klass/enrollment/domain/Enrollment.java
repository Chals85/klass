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
import com.toby.klass.enrollment.domain.error.EnrollmentError;
import com.toby.klass.klass.domain.CancellationPolicy;
import com.toby.klass.klass.domain.Klass;
import com.toby.klass.user.domain.User;
import java.time.LocalDate;
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
 * <h2>카운터는 여기서 건드리지 않는다</h2>
 * {@link #confirm}·{@link #cancel} 은 <b>자기 상태만</b> 바꾼다. {@code klass.enrollment_count}
 * 는 다른 애그리거트 행이고, 그 갱신은 호출자가 {@code klass} 배타 락 아래에서
 * {@code Klass.occupySeat()}/{@code releaseSeat()} 로 한다 (ERD 정본 §4.1).
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
            // 양방향이다 (D-49). CANCELLED 이면 원인이 반드시 있고, 아니면 반드시 없다.
            // ck_enrollment_pending 이 이미 양방향이라 형태를 맞췄다
            @CheckConstraint(name = "ck_enrollment_cancelled",
                    constraint = "(status = 'CANCELLED' AND cancelled_at IS NOT NULL "
                            + "AND cancel_reason IS NOT NULL) "
                            + "OR (status <> 'CANCELLED' AND cancel_reason IS NULL)")
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
     * <p>원인은 {@link #cancelReason} 이 따로 갖는다. ERD 정본 §2 ⑦ 이 열어 두었던 미결이며,
     * 만료 회수 배치가 생기면서 원인이 둘이 되어 닫았다.
     */
    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    /**
     * 취소 원인. {@code CANCELLED} 일 때만 값이 있다 ({@code ck_enrollment_cancelled}).
     *
     * <p><b>사용자에게 보이는 값이다.</b> 만료 취소는 사용자가 요청한 적이 없으므로, 이 값이
     * 없으면 "내가 취소하지 않았는데 취소돼 있다"가 된다.
     *
     * <p>Design Ref: pending-expiry-reaper §3.2, ERD 정본 §2 ⑦
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "cancel_reason", length = 20)
    private CancelReason cancelReason;

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

    // ── 상태 전이 ────────────────────────────────────────────────────────────
    // 두 메서드 모두 expires_at 을 NULL 로 만든다. ck_enrollment_pending 이
    // "PENDING 이 아니면 expires_at IS NULL" 을 강제하므로, 빠뜨리면 CHECK 위반으로
    // 500 이 난다 — 사용자에게 설명할 수 없는 실패다.

    /**
     * 결제를 확정한다. {@code PENDING → CONFIRMED}.
     *
     * <p><b>만료 검사를 여기 두는 이유</b>: ERD 정본 §4.3 4번은 "만료 배치가 아직 처리하지
     * 않은 {@code PENDING}" 을 거부하라고 한다. 배치가 붙은 지금도 <b>사이클 사이에 만료된
     * 행이 남으므로</b>(최대 {@code app.enrollment.reap-interval}) 이 검사가 <b>첫째 방어선</b>
     * 이고 배치가 둘째다. 서비스에 두면 다른 호출 경로가 생길 때 빠뜨릴 수 있다.
     *
     * <p>좌석 점유 수는 <b>변하지 않는다</b> — {@code PENDING} 이 이미 점유하고 있었다.
     * 그래서 이 전이는 {@code klass} 락 없이 안전하다 (ERD 정본 §4.1 예외).
     *
     * @param now 확정 시각. {@code LocalDateTime.now(clock)} 으로 얻은 값
     * @throws com.toby.klass.common.domain.error.BusinessException {@code PENDING} 이 아니거나
     *                                                              결제 기한이 지난 경우
     */
    public void confirm(LocalDateTime now) {
        if (this.status != EnrollmentStatus.PENDING) {
            throw EnrollmentError.INVALID_ENROLLMENT_STATUS_TRANSITION.toException();
        }
        // 판정을 isExpiredAt 에 위임한다. expire 와 정확히 반대 조건이므로 두 벌이 되면
        // 경계에서 갈라져 확정도 회수도 되지 않는 행이 생긴다 (Design §3.2)
        if (isExpiredAt(now)) {
            throw EnrollmentError.ENROLLMENT_EXPIRED.toException();
        }
        this.status = EnrollmentStatus.CONFIRMED;
        this.confirmedAt = now;
        this.expiresAt = null;
    }

    /**
     * 신청을 취소한다. {@code PENDING → CANCELLED} 또는 {@code CONFIRMED → CANCELLED}.
     *
     * <h4>{@code PENDING} 은 두 관문을 모두 면제받는다</h4>
     * 결제 전이라 환불할 돈이 없고, 무엇보다 <b>기간 기산점인 {@code confirmedAt} 이 아직
     * {@code null}</b> 이다. {@code createdAt} 을 기산점으로 삼으면 ERD 정본 §4.4 5-b 에서
     * 이탈한다.
     *
     * <h4>{@code CONFIRMED} 의 두 관문 순서</h4>
     * <b>강의 종료를 먼저 본다.</b> 둘 다 걸리는 상황에서 "기간이 지났다"라고 답하면 사용자가
     * 다음엔 더 빨리 요청하면 된다고 오해한다. 강의가 끝났다면 아무리 빨리 요청해도 성립하지
     * 않으므로 그쪽을 알려야 한다.
     *
     * <p>이 메서드는 카운터를 건드리지 않는다 — {@code klass} 는 다른 애그리거트 행이고,
     * 반납은 호출자가 {@code klass} 락 아래에서 {@code releaseSeat()} 로 한다.
     *
     * @param now    취소 시각
     * @param today  오늘 날짜. 서비스가 {@code LocalDate.now(clock)} 으로 얻은 값을 넘긴다 —
     *               도메인이 시간대를 결정하면 안 되기 때문이다 (ERD 정본 §2.2)
     * @param policy 강의가 정한 취소 조건. {@code Klass.cancellationPolicy(전역기본)} 이 만든다
     * @throws com.toby.klass.common.domain.error.BusinessException 이미 종착 상태이거나,
     *                                                              강의가 끝났거나, 기간이 지난 경우
     */
    public void cancel(LocalDateTime now, LocalDate today, CancellationPolicy policy) {
        if (!isSeatOccupying()) {
            throw EnrollmentError.INVALID_ENROLLMENT_STATUS_TRANSITION.toException();
        }
        if (this.status == EnrollmentStatus.CONFIRMED) {
            if (policy.isKlassFinished(today)) {
                throw EnrollmentError.KLASS_ALREADY_FINISHED.toException();
            }
            if (!policy.isWithinPeriod(this.confirmedAt, now)) {
                throw EnrollmentError.CANCELLATION_PERIOD_EXPIRED.toException();
            }
        }
        this.status = EnrollmentStatus.CANCELLED;
        this.cancelledAt = now;
        this.cancelReason = CancelReason.USER;
        this.expiresAt = null;
    }

    /**
     * 결제 기한이 지난 신청을 회수한다. {@code PENDING → CANCELLED}.
     *
     * <h4>{@link #cancel} 에 플래그를 넣지 않은 이유</h4>
     * 만료에는 취소 가능 기간·강의 종료 관문이 <b>애초에 무의미하다</b> — {@code PENDING} 은
     * 두 관문을 면제받으므로 {@code today}·{@code policy} 를 받을 이유가 없다. 인자를 추가하면
     * 두 경로가 한 메서드 안에서 조건문으로 갈리고, 기존 호출부가 전부 바뀐다 (Design D-51).
     *
     * <h4>호출 규약</h4>
     * 호출자는 반드시 <b>{@code klass} 배타 락 아래</b>에서 이 메서드와 {@code releaseSeat()}
     * · 대기자 승격을 <b>한 트랜잭션으로</b> 끝내야 한다. 락을 놓고 회수하면 그 틈에 일반
     * 신청자가 반납된 좌석을 채간다 (ERD 정본 §4.1).
     *
     * <p>이 메서드는 카운터를 건드리지 않는다 — {@link #cancel} 과 같은 이유다.
     *
     * @param now 회수 시각. {@code LocalDateTime.now(clock)} 으로 얻은 값
     * @throws com.toby.klass.common.domain.error.BusinessException {@code PENDING} 이 아니거나
     *                                                              아직 기한이 남은 경우
     */
    public void expire(LocalDateTime now) {
        if (this.status != EnrollmentStatus.PENDING) {
            throw EnrollmentError.INVALID_ENROLLMENT_STATUS_TRANSITION.toException();
        }
        if (!isExpiredAt(now)) {
            throw EnrollmentError.ENROLLMENT_NOT_EXPIRED.toException();
        }
        this.status = EnrollmentStatus.CANCELLED;
        this.cancelledAt = now;
        this.cancelReason = CancelReason.EXPIRED;
        this.expiresAt = null;
    }

    // ── 판별 ─────────────────────────────────────────────────────────────────

    /**
     * 이 신청의 주인인지 판별한다.
     *
     * <p>{@code user} 는 {@code LAZY} 프록시지만 {@code getId()} 는 프록시가 이미 들고 있는
     * 값이라 <b>초기화를 유발하지 않는다</b> — 소유권 검사 때문에 추가 쿼리가 나가지 않는다.
     *
     * @param userId 판별 대상 사용자 id. {@code null} 이면 항상 {@code false}
     */
    public boolean isOwnedBy(Long userId) {
        return userId != null && this.user.getId().equals(userId);
    }

    /**
     * 결제 기한이 지났는지 판별한다.
     *
     * <h4>{@link #confirm} 과 {@link #expire} 가 이 판정을 공유한다</h4>
     * 둘은 <b>정확히 반대 조건</b>에서 성립한다 — 확정은 기한 안에서만, 회수는 기한 밖에서만.
     * 판정이 두 벌이 되면 경계에서 갈라져 <b>확정도 회수도 되지 않는 행</b>이 생긴다.
     * 만료 회수 배치의 재확인(Design FR-08)도 같은 메서드를 쓴다.
     *
     * <h4>경계 — 같은 시각은 이미 만료다</h4>
     * {@code expiresAt} 이 정확히 {@code now} 이면 {@code true} 다. 기존 {@code confirm} 의
     * {@code !expiresAt.isAfter(now)} 를 <b>그대로 옮긴 것</b>이라 동작이 바뀌지 않았다 —
     * "기한이 10:30 까지"가 아니라 "10:30 이 되면 끝"이다.
     *
     * <p>포트의 후보 조회({@code expires_at <= now})와 <b>같은 경계</b>다. 배치가 집어온
     * 후보가 재확인에서 억울하게 걸러지지 않는다.
     *
     * <p>{@code PENDING} 이 아니면 항상 {@code false} 다. 그 상태에서는 {@code expiresAt} 이
     * {@code null} 이므로({@code ck_enrollment_pending}) <b>이 순서가 NPE 를 막는다</b> —
     * 조건을 뒤집으면 종착 상태의 신청에서 터진다.
     *
     * <p>Design Ref: pending-expiry-reaper §3.2
     *
     * @param now 현재 시각. {@code LocalDateTime.now(clock)} 으로 얻은 값
     */
    public boolean isExpiredAt(LocalDateTime now) {
        return this.status == EnrollmentStatus.PENDING && !this.expiresAt.isAfter(now);
    }

    /**
     * 좌석을 점유하고 있는지 판별한다. {@code PENDING} 과 {@code CONFIRMED} 가 참이다.
     *
     * <p>{@code klass.enrollment_count} 의 집계 기준과 <b>같은 정의</b>다 (ERD 정본 §2 ①).
     * 둘이 어긋나면 카운터가 실제 점유 행 수와 맞지 않는다.
     */
    public boolean isSeatOccupying() {
        return this.status == EnrollmentStatus.PENDING
                || this.status == EnrollmentStatus.CONFIRMED;
    }

    /**
     * 지금 취소할 수 있는지 판별한다. {@link #cancel} 과 <b>같은 판정</b>을 boolean 으로 돌려준다.
     *
     * <p><b>이 메서드가 없으면 판정이 두 벌이 된다.</b> 응답 DTO 의 {@code isCancellable}
     * 필드(Design D-39)를 채우려면 같은 계산이 필요한데, 여기 없으면 서비스나 DTO 매퍼가
     * 조건을 다시 쓴다 — 클라이언트 복제를 막으려던 결정이 서버 안에서 복제를 만드는 셈이다.
     *
     * <p>{@code cancel} 은 이것을 재사용하지 <b>않는다.</b> 거부 시 어느 관문에 걸렸는지에 따라
     * 다른 예외를 골라야 하는데 boolean 하나로는 그 정보가 사라지기 때문이다. 대신 두 메서드가
     * 같은 {@code policy} 판정을 부르므로 규칙이 갈라지지 않는다.
     *
     * @param now    현재 시각
     * @param today  오늘 날짜 ({@code LocalDate.now(clock)})
     * @param policy 강의가 정한 취소 조건
     */
    public boolean isCancellableAt(LocalDateTime now, LocalDate today, CancellationPolicy policy) {
        if (!isSeatOccupying()) {
            return false;
        }
        if (this.status == EnrollmentStatus.PENDING) {
            return true;
        }
        return !policy.isKlassFinished(today)
                && policy.isWithinPeriod(this.confirmedAt, now);
    }

}
