package com.toby.klass.klass.domain;

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
import com.toby.klass.user.domain.User;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import org.hibernate.annotations.Check;

/**
 * 강의 애그리거트 루트.
 *
 * <h2>이름이 {@code Klass} 인 이유</h2>
 * 자바에서 {@code Class} 는 {@code java.lang.Class} 와 충돌해 선언 자체가 불가능하다.
 * {@code Klass} 는 도메인 어휘를 유지하면서 식별자 충돌만 우회한다. <b>엔티티·테이블·FK
 * 컬럼에 같은 이름을 쓰므로</b> {@code @Table(name=...)} 매핑이 필요 없다
 * (ERD 정본 §7.2).
 *
 * <h2>{@code enrollment_count} 가 비정규화 카운터인 이유</h2>
 * 목록·상세 조회에서 강의당 {@code COUNT(*)} 쿼리를 없애기 위함이다. 락 대상 행과 같은
 * 행이라 갱신 비용이 사실상 0이다. 대신 <b>실제 좌석 점유 행 수와 어긋날 위험</b>이 생기므로,
 * DB 의 {@code ck_klass_count} 와 트랜잭션 규약이 함께 지킨다 (ERD 정본 §4).
 *
 * <p><b>1차 범위는 스키마 확정까지다.</b> 정원 증감·상태 전이 메서드는 동시성 규약과 함께
 * 2차에서 붙인다 (Design §2.2 Out of Scope).
 *
 * <p>Design Ref: §3.1 엔티티 목록, §3.6 제약, ERD 정본 §3.2.5 · §3.7
 */
@Entity
@Table(
        name = "klass",
        indexes = {
            @Index(name = "idx_klass_status", columnList = "status, id DESC"),
            @Index(name = "idx_klass_creator", columnList = "creator_id, id DESC")
        })
// ERD 정본 §3.5.2 제약 5종. @Table 에는 check 속성이 없다 — Hibernate @Check 를 쓴다 (Design §3.6)
@Check(name = "ck_klass_capacity", constraints = "capacity > 0")
@Check(name = "ck_klass_count", constraints = "enrollment_count >= 0 AND enrollment_count <= capacity")
@Check(name = "ck_klass_price", constraints = "price >= 0")
@Check(name = "ck_klass_period", constraints = "ends_on >= starts_on")
@Check(name = "ck_klass_cancel",
        constraints = "cancellation_period_days IS NULL OR cancellation_period_days >= 0")
@Getter
public class Klass {

    /** PK. 단조 증가 {@code BIGINT} 이라 커서 페이지네이션의 정렬 키로도 안정적이다. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 개설자.
     *
     * <p>컬럼명이 {@code user_id} 가 아닌 이유는 강의에 두 종류의 사용자(개설자·수강생)가
     * 얽혀 있어 모호해지기 때문이다. <b>사용자가 만든 것은 역할명, 사용자 자신의 기록은
     * {@code user_id}</b> 가 규칙이다 (ERD 정본 §3.1.2).
     *
     * <p>{@code LAZY} 로 둔다. 강의 목록·상세에서 개설자 정보가 항상 필요하지는 않으므로
     * 기본 로딩에 포함시키면 불필요한 조인이 생긴다. <b>목록 조회에서 개설자를 함께 쓸 때는
     * fetch join 을 명시</b>해야 N+1 이 나지 않는다.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "creator_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_klass_creator"))
    private User creator;

    /** 강의 제목. */
    @Column(nullable = false, length = 200)
    private String title;

    /** 강의 설명. {@code TEXT} 로 매핑된다. */
    @Column(columnDefinition = "TEXT")
    private String description;

    /** 수강료. 부동소수점 오차를 배제하려고 {@code DECIMAL} 을 쓴다 (ERD 정본 §7.2). */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    /** 최대 정원. 1 이상이어야 한다 ({@code ck_klass_capacity}). */
    @Column(nullable = false)
    private int capacity;

    /**
     * 좌석 점유 인원 = {@code enrollment} 의 PENDING + CONFIRMED 수.
     *
     * <p>이름이 {@code confirmed_count} 가 아닌 이유: CONFIRMED 만 센다고 읽히지만 실제로는
     * PENDING 도 포함하며, 결제 확정 시에는 값이 변하지 않는다 (ERD 정본 v1.9 변경 이력).
     */
    @Column(name = "enrollment_count", nullable = false)
    private int enrollmentCount;

    /**
     * 강의 상태. <b>{@link KlassStatus#OPEN} 만 신청을 받는다.</b>
     *
     * <p>{@code starts_on}/{@code ends_on} 은 표시·안내용이며 신청 차단 조건이 아니다 —
     * 수강 시작 후 신청을 막고 싶으면 크리에이터가 {@code CLOSED} 로 전환한다 (ERD 정본 §2.2).
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private KlassStatus status;

    /** 수강 시작일. 시각이 아니라 <b>날짜</b>다 — 컬럼명 {@code _on} 이 그것을 알린다. */
    @Column(name = "starts_on", nullable = false)
    private LocalDate startsOn;

    /** 수강 종료일. */
    @Column(name = "ends_on", nullable = false)
    private LocalDate endsOn;

    /** 취소 가능 기간(일). {@code null} 이면 전역 기본값을 따른다. 시점이 아니라 기간이다. */
    @Column(name = "cancellation_period_days")
    private Integer cancellationPeriodDays;

    /** 등록 시각. 팩토리가 주입된 {@code Clock} 으로 채운다 ({@code updatable = false}). */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** JPA 가 리플렉션으로 인스턴스를 만들 때 쓴다. 직접 호출하지 말 것. */
    protected Klass() {
    }

    private Klass(User creator, String title, String description, BigDecimal price,
                  int capacity, LocalDate startsOn, LocalDate endsOn,
                  Integer cancellationPeriodDays, LocalDateTime createdAt) {
        this.creator = creator;
        this.title = title;
        this.description = description;
        this.price = price;
        this.capacity = capacity;
        this.enrollmentCount = 0;
        this.status = KlassStatus.DRAFT;
        this.startsOn = startsOn;
        this.endsOn = endsOn;
        this.cancellationPeriodDays = cancellationPeriodDays;
        this.createdAt = createdAt;
    }

    /**
     * 강의를 개설한다. 상태는 항상 {@link KlassStatus#DRAFT} 에서 시작한다.
     *
     * @param description            설명. {@code null} 허용
     * @param price                  수강료. 0 이상이어야 한다
     * @param capacity               정원. 1 이상이어야 한다
     * @param endsOn                 수강 종료일. 시작일 이후여야 한다
     * @param cancellationPeriodDays 취소 가능 기간(일). {@code null} 이면 전역 기본값
     * @param createdAt              생성 시각. {@code LocalDateTime.now(clock)} 으로 얻은 값을 넘긴다
     * @return 아직 영속화되지 않은 새 강의
     */
    public static Klass open(User creator, String title, String description, BigDecimal price,
                             int capacity, LocalDate startsOn, LocalDate endsOn,
                             Integer cancellationPeriodDays, LocalDateTime createdAt) {
        return new Klass(creator, title, description, price, capacity,
                startsOn, endsOn, cancellationPeriodDays, createdAt);
    }

}
