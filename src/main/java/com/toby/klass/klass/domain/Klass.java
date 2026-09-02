package com.toby.klass.klass.domain;

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
import com.toby.klass.klass.domain.error.KlassError;
import com.toby.klass.user.domain.User;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;

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
 * <h2>어디까지 구현돼 있는가</h2>
 * <b>상태 전이·내용 수정·판별 메서드는 klass-management 사이클에서 추가됐다.</b>
 * 여전히 없는 것은 <b>{@code enrollment_count} 증감</b>이다 — 그것은 수강신청 소관이고
 * 비관적 락 규약(ERD 정본 §4.1)과 함께 2차에서 붙는다. 이 필드를 <b>읽는</b> 코드는
 * 있지만({@link #changeCapacity}) <b>쓰는</b> 코드는 아직 없다.
 *
 * <p>Design Ref: §3.1 엔티티 목록, §3.6 제약, ERD 정본 §3.2.5 · §3.7
 */
@Entity
@Table(
        name = "klass",
        indexes = {
            @Index(name = "idx_klass_status", columnList = "status, id DESC"),
            @Index(name = "idx_klass_creator", columnList = "creator_id, id DESC")
        },
        // ERD 정본 §3.5.2 제약 5종 (Design §3.6)
        check = {
            @CheckConstraint(name = "ck_klass_capacity", constraint = "capacity > 0"),
            @CheckConstraint(name = "ck_klass_count",
                    constraint = "enrollment_count >= 0 AND enrollment_count <= capacity"),
            @CheckConstraint(name = "ck_klass_price", constraint = "price >= 0"),
            @CheckConstraint(name = "ck_klass_period", constraint = "ends_on >= starts_on"),
            @CheckConstraint(name = "ck_klass_cancel",
                    constraint = "cancellation_period_days IS NULL OR cancellation_period_days >= 0")
        })
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

    /**
     * 강의 내용. {@code TEXT} 로 매핑된다.
     *
     * <p><b>필수값이다</b> (Design D-18). ERD 원안은 nullable 이었으나, 원 요구사항이 등록
     * 항목으로 "내용"을 나열했고 선택이라는 단서가 없었다. 등록·수정 양쪽에서 {@code null}
     * 도 공백도 받지 않는다 — 수정은 전체 교체이므로(D-25) 빈 값은 "안 바꿈"이 아니라
     * 입력 오류다. "내용을 비운다"라는 요청은 성립하지 않는다.
     */
    @Column(nullable = false, columnDefinition = "TEXT")
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

    /**
     * 취소 가능 기간(일). {@code null} 이면 전역 기본값을 따른다. 시점이 아니라 기간이다.
     *
     * <p><b>{@code DRAFT} 에서만 바꿀 수 있다</b> — 수강생과의 약속이라 신청자가 생긴 뒤에
     * 바꾸면 안 된다 ({@link #changeCancellationPeriodDays}, Design D-26).
     */
    @Column(name = "cancellation_period_days")
    private Integer cancellationPeriodDays;

    /** 등록 시각. 팩토리가 주입된 {@code Clock} 으로 채운다 ({@code updatable = false}). */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * 최종 수정 시각.
     *
     * <p><b>NULL 을 허용하지 않는다</b> (Design §3.1). "한 번도 수정된 적 없음"은
     * {@code createdAt == updatedAt} 으로 이미 표현되므로, 그 정보를 위해 NULL 을 들이면
     * 응답 DTO 와 정렬 처리에 대가만 번지고 얻는 것이 없다. 생성 시점에는 {@code createdAt}
     * 과 같은 값이 들어간다.
     */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

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
        // 생성 시점에는 두 시각이 같다. "아직 수정된 적 없음"이 이것으로 표현된다.
        this.updatedAt = createdAt;
    }

    /**
     * 강의를 개설한다. 상태는 항상 {@link KlassStatus#DRAFT} 에서 시작한다.
     *
     * <p><b>여기서 불변식을 먼저 검사한다.</b> 생성 시점에 통과시켜 두고 수정 메서드에서만
     * 검사하면, 애초에 잘못된 강의가 만들어져 DB 의 CHECK 제약에 걸린다 — 그때는 사용자에게
     * 무엇이 문제인지 설명할 수 없다.
     *
     * @param description            내용. <b>필수값</b>이다 (D-18)
     * @param price                  수강료. 0 이상이어야 한다
     * @param capacity               정원. 1 이상이어야 한다
     * @param endsOn                 수강 종료일. 시작일 이후여야 한다
     * @param cancellationPeriodDays 취소 가능 기간(일). {@code null} 이면 전역 기본값
     * @param createdAt              생성 시각. {@code LocalDateTime.now(clock)} 으로 얻은 값을 넘긴다.
     *                               {@code updatedAt} 에도 같은 값이 들어간다
     * @return 아직 영속화되지 않은 새 강의
     * @throws com.toby.klass.common.domain.error.BusinessException 정원이 1 미만이거나
     *                               종료일이 시작일보다 빠른 경우
     */
    public static Klass open(User creator, String title, String description, BigDecimal price,
                             int capacity, LocalDate startsOn, LocalDate endsOn,
                             Integer cancellationPeriodDays, LocalDateTime createdAt) {
        verifyCapacity(capacity);
        verifyPeriod(startsOn, endsOn);
        return new Klass(creator, title, description, price, capacity,
                startsOn, endsOn, cancellationPeriodDays, createdAt);
    }

    // ── 상태 전이 ────────────────────────────────────────────────────────────
    // 전이별로 메서드를 나눈 이유: changeStatus(KlassStatus) 하나로 두면 허용 여부 판단이
    // 메서드 안의 조건문으로 숨고 호출부는 어떤 전이가 가능한지 알 수 없다. 이름이 곧
    // 전이이면 존재하지 않는 전이는 호출할 메서드가 없다 (Design §3.2).

    /**
     * 강의를 공개해 모집을 시작한다. {@code DRAFT → OPEN}.
     *
     * @param now 전이 시각. 주입된 {@code Clock} 에서 얻은 값
     * @throws com.toby.klass.common.domain.error.BusinessException 현재 {@code DRAFT} 가 아닌 경우
     */
    public void publish(LocalDateTime now) {
        if (this.status != KlassStatus.DRAFT) {
            throw KlassError.INVALID_KLASS_STATUS_TRANSITION.toException();
        }
        this.status = KlassStatus.OPEN;
        this.updatedAt = now;
    }

    /**
     * 모집을 마감한다. {@code DRAFT → CLOSED}(개설 철회) 와 {@code OPEN → CLOSED}(모집 마감)
     * 양쪽에서 가능하다.
     *
     * <p><b>{@code DRAFT} 에서도 허용하는 이유</b>: 이 설계에는 물리 삭제가 없으므로
     * (ERD 정본 §2), 공개하지 않기로 한 초안을 정리하는 유일한 수단이 이 전이다. 초안은
     * 신청을 받지 않으므로 신청자가 있을 수 없어 안전하다 (ERD 정본 §3.4 "개설 철회").
     *
     * <p><b>2차에서 여기에 붙는다</b>: {@code CLOSED} 전이 시 잔여 {@code WAITING} 대기자를
     * 전부 {@code CANCELLED} 로 정리해야 한다 (ERD 정본 §4.8 상태 전이 5번). {@code CLOSED}
     * 에서는 승격이 중단되고 {@code CLOSED → OPEN} 도 봉쇄돼 있어, 남겨두면 영구히 승격되지
     * 않는 유령 행이 된다. 대기열이 아직 없어 지금은 발현하지 않는다 (Design D-16).
     *
     * @param now 전이 시각
     * @throws com.toby.klass.common.domain.error.BusinessException 이미 {@code CLOSED} 인 경우
     */
    public void close(LocalDateTime now) {
        if (this.status == KlassStatus.CLOSED) {
            throw KlassError.INVALID_KLASS_STATUS_TRANSITION.toException();
        }
        this.status = KlassStatus.CLOSED;
        this.updatedAt = now;
    }

    // ── 내용 수정 ────────────────────────────────────────────────────────────

    /**
     * 제목을 바꾼다.
     */
    public void changeTitle(String title, LocalDateTime now) {
        this.title = title;
        this.updatedAt = now;
    }

    /**
     * 내용을 바꾼다. {@code null} 로 되돌릴 수 없다 — 필수값이기 때문이다 (D-18).
     */
    public void changeDescription(String description, LocalDateTime now) {
        this.description = description;
        this.updatedAt = now;
    }

    /**
     * 수강료를 바꾼다.
     */
    public void changePrice(BigDecimal price, LocalDateTime now) {
        this.price = price;
        this.updatedAt = now;
    }

    /**
     * 수강 기간을 바꾼다.
     *
     * <p><b>두 날짜를 함께 받는 이유</b>: 시작일만 바꾸면 {@code ends_on >= starts_on}
     * ({@code ck_klass_period}) 를 깰 수 있다. 쌍으로 받아 도메인이 먼저 검사하면 CHECK 제약
     * 까지 가지 않는다 — CHECK 는 최종 방어선이지 1차 방어선이 아니다. 한쪽만 바꾸려는
     * 호출자는 나머지에 현재 값을 넣어 넘긴다.
     *
     * @throws com.toby.klass.common.domain.error.BusinessException 종료일이 시작일보다 빠른 경우
     */
    public void changePeriod(LocalDate startsOn, LocalDate endsOn, LocalDateTime now) {
        verifyPeriod(startsOn, endsOn);
        this.startsOn = startsOn;
        this.endsOn = endsOn;
        this.updatedAt = now;
    }

    /**
     * 정원을 바꾼다.
     *
     * <p>이미 좌석을 점유한 인원보다 적게 줄일 수 없다. DB 의 {@code ck_klass_count} 가
     * 최종 방어하지만 여기서 먼저 막아야 사용자에게 이유를 설명할 수 있다 (ERD 정본 §4.8).
     *
     * <p><b>2차에서 여기에 붙는다</b>: 정원이 <b>증가</b>했고 상태가 {@code OPEN} 이면, 늘어난
     * 자리만큼 대기자를 승격해야 한다 (ERD 정본 §4.8 capacity 5번). 승격은 좌석 반납 경로
     * (§4.4)에서만 트리거되므로, 그것이 없으면 <b>신규 신청자가 대기자를 앞지른다.</b>
     * 대기열이 아직 없어 지금은 발현하지 않는다 (Design D-16).
     *
     * @throws com.toby.klass.common.domain.error.BusinessException 정원이 1 미만이거나
     *                                                             현재 점유 인원보다 적은 경우
     */
    public void changeCapacity(int capacity, LocalDateTime now) {
        verifyCapacity(capacity);
        if (capacity < this.enrollmentCount) {
            throw KlassError.CAPACITY_BELOW_ENROLLMENT.toException();
        }
        this.capacity = capacity;
        this.updatedAt = now;
    }

    /**
     * 취소 가능 기간을 바꾼다. {@code null} 이면 전역 기본값을 따른다.
     *
     * <p><b>{@code DRAFT} 에서만 호출된다.</b> 취소 가능 기간은 수강생과의 약속이라
     * 신청자가 생긴 뒤에 바꾸면 이미 신청한 사람의 취소 조건이 사후에 불리해질 수 있다.
     * 그 판정은 {@link #isFullyEditable()} 이 하고 호출자가 건너뛴다 — 이 메서드가
     * 상태를 다시 검사하지 않는 이유다 (Design D-26 · D-28).
     */
    public void changeCancellationPeriodDays(Integer cancellationPeriodDays, LocalDateTime now) {
        this.cancellationPeriodDays = cancellationPeriodDays;
        this.updatedAt = now;
    }

    // ── 판별 ─────────────────────────────────────────────────────────────────

    /**
     * 이 강의를 개설한 사용자인지 판별한다.
     *
     * <p><b>{@code userId} 가 {@code null} 일 수 있다.</b> 강의 조회는 선택적 인증이라
     * 비로그인 요청이 그대로 들어온다. {@code userId.equals(...)} 로 쓰면 그 경로에서 NPE 가
     * 나므로 <b>순서를 뒤집어</b> 개설자 id 를 왼쪽에 둔다.
     *
     * <p>{@code creator} 는 {@code LAZY} 프록시지만 {@code getId()} 는 프록시가 이미 들고
     * 있는 값이라 <b>초기화를 유발하지 않는다</b> — 소유권 검사 때문에 추가 쿼리가 나가지 않는다.
     *
     * @param userId 판별 대상 사용자 id. {@code null} 이면 항상 {@code false}
     */
    public boolean isOwnedBy(Long userId) {
        return userId != null && this.creator.getId().equals(userId);
    }

    /**
     * 이 강의가 해당 사용자에게 보이는지 판별한다.
     *
     * <p>{@code DRAFT} 는 개설자에게만 보인다. 그 외 상태는 누구에게나 보인다. 상세 조회가
     * 이 판정으로 <b>404</b> 를 결정한다 — 403 이 아니다. 403 은 "그 강의는 존재한다"를
     * 알려주는데, 초안은 존재 자체가 비밀이기 때문이다 (Design §6.2).
     *
     * @param viewerId 조회자 id. 비로그인이면 {@code null}
     */
    public boolean isVisibleTo(Long viewerId) {
        return this.status != KlassStatus.DRAFT || isOwnedBy(viewerId);
    }

    /**
     * 제목 <b>외의</b> 필드를 바꿀 수 있는 상태인지 판별한다. {@code DRAFT} 만 허용한다.
     *
     * <h4>왜 공개된 뒤에는 제목만 바꿀 수 있는가</h4>
     * 내용·가격·정원·수강기간·취소기간은 <b>수강생이 신청을 결정할 때 본 조건</b>이다.
     * 공개 후에 바꾸면 이미 신청한 사람이 동의하지 않은 조건으로 갈아치우는 셈이 된다 —
     * 가격이 오르거나 취소 가능 기간이 짧아지는 쪽이면 특히 그렇다.
     *
     * <p>{@code DRAFT} 는 신청 자체가 불가능하므로(ERD 정본 §2.2) 그때까지는 무엇이든
     * 바꿔도 안전하다. 제목만 예외로 두는 것은 오타 수정 같은 요구를 막을 이유가 없기
     * 때문이다.
     *
     * <p><b>{@code DRAFT} 로 되돌아올 수 없다는 점이 이 규칙을 단순하게 만든다</b> —
     * {@code OPEN → DRAFT} 역전이가 차단돼 있어(D-18) 한 번 공개된 강의는 영구히
     * "제목만 수정 가능" 상태다.
     *
     * <p>Design Ref: D-28
     */
    public boolean isFullyEditable() {
        return this.status == KlassStatus.DRAFT;
    }

    // ── 불변식 ───────────────────────────────────────────────────────────────

    private static void verifyCapacity(int capacity) {
        if (capacity < 1) {
            throw KlassError.INVALID_KLASS_CAPACITY.toException();
        }
    }

    private static void verifyPeriod(LocalDate startsOn, LocalDate endsOn) {
        if (endsOn.isBefore(startsOn)) {
            throw KlassError.INVALID_KLASS_PERIOD.toException();
        }
    }

}
