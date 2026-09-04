package com.toby.klass.enrollment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toby.klass.klass.domain.Klass;
import com.toby.klass.user.domain.Role;
import com.toby.klass.user.domain.User;
import com.toby.klass.waitlist.domain.Waitlist;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

/**
 * 수강 도메인 스키마가 ERD 정본대로 만들어졌는지 검증한다.
 *
 * <h2>왜 이 테스트가 필요한가</h2>
 * Design §1.2 는 <b>"불변식은 가장 낮은 계층에서 지킨다"</b>를 원칙으로 둔다. 정원 초과 방지가
 * 애플리케이션 로직의 성실성에 의존하면, 그 로직에 버그가 생기는 순간 데이터가 깨진다.
 * 그래서 DB 제약이 최종 방어선인데 — <b>제약이 실제로 생성됐는지는 확인해야만 알 수 있다.</b>
 * {@code @Check} 를 붙였어도 DDL 에 반영되지 않으면 조용히 무방비가 된다.
 *
 * <p>Plan FR-03(테이블 생성) · FR-04(CHECK) · FR-05(활성 중복 차단) · FR-15(인덱스)의
 * 판정 근거이며, Design §8.2 #13 · #15 에 해당한다.
 *
 * <p>IDENTITY 전략은 PK 를 얻으려고 {@code persist()} 시점에 곧바로 INSERT 를 날리므로,
 * 제약 위반 예외는 {@code flush()} 가 아니라 {@code persist()} 에서 터진다. 아래 테스트가
 * 둘을 함께 감싸는 이유다.
 */
@DataJpaTest
class EnrollmentSchemaTest {

    /** 고정 시각. CLAUDE.md 는 전 계층에서 무인자 {@code now()} 를 금지한다. */
    private static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 9, 1, 10, 0);

    @Autowired
    private EntityManager em;

    private Klass klass;
    private User user;
    private Long klassId;
    private Long userId;

    @BeforeEach
    void setUp() {
        User user = User.register("tester", "hashed", Set.of(Role.ROLE_USER), FIXED_NOW);
        em.persist(user);

        // description 은 필수값이다 (Design D-18) — null 을 넣으면 NOT NULL 에 걸린다
        Klass klass = Klass.open(user, "테스트 강의", "테스트 내용", new BigDecimal("10000"),
                10, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1), 7, FIXED_NOW);
        em.persist(klass);
        em.flush();

        this.user = user;
        this.klass = klass;
        this.userId = user.getId();
        this.klassId = klass.getId();
    }

    private Enrollment pending(User who) {
        return Enrollment.apply(klass, who, EnrollmentSource.DIRECT,
                FIXED_NOW, FIXED_NOW.plusMinutes(30));
    }

    /** FR-03 — 7개 테이블이 기동 시 생성된다. */
    @Nested
    @DisplayName("FR-03 테이블 생성")
    class TableCreation {

        @Test
        @DisplayName("ERD 정본의 7개 테이블이 모두 존재한다")
        void allSevenTablesExist() {
            for (String table : new String[] {
                    "USERS", "USER_ROLES", "REFRESH_TOKEN", "REVOKED_ACCESS_TOKEN",
                    "KLASS", "ENROLLMENT", "WAITLIST"}) {

                // table_schema 를 걸지 않으면 H2 의 시스템 뷰(INFORMATION_SCHEMA.USERS)까지
                // 세어 USERS 가 2건으로 나온다.
                Number count = (Number) em.createNativeQuery(
                                "select count(*) from information_schema.tables "
                                        + "where table_schema = 'PUBLIC' "
                                        + "  and upper(table_name) = '" + table + "'")
                        .getSingleResult();
                assertThat(count.intValue()).as(table).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("ENUM 은 ordinal 이 아니라 문자열로 저장된다")
        void enumsAreStoredAsStrings() {
            em.persist(pending(user));
            em.flush();
            em.clear();

            String status = (String) em.createNativeQuery(
                            "select status from enrollment where user_id = " + userId)
                    .getSingleResult();
            assertThat(status).isEqualTo("PENDING");

            // 취소 원인도 같은 규약을 따른다. ordinal 이면 클라이언트가 숫자를 받는다
            em.createNativeQuery(
                            "update enrollment set status = 'CANCELLED', expires_at = null, "
                                    + "cancelled_at = current_timestamp, "
                                    + "cancel_reason = 'EXPIRED' where user_id = " + userId)
                    .executeUpdate();

            String reason = (String) em.createNativeQuery(
                            "select cancel_reason from enrollment where user_id = " + userId)
                    .getSingleResult();
            assertThat(reason).isEqualTo("EXPIRED");
        }

        @Test
        @DisplayName("cancel_reason 컬럼이 존재한다 — 만료 회수가 원인을 남길 자리")
        void cancelReasonColumnExists() {
            Number count = (Number) em.createNativeQuery(
                            "select count(*) from information_schema.columns "
                                    + "where upper(table_name) = 'ENROLLMENT' "
                                    + "  and upper(column_name) = 'CANCEL_REASON'")
                    .getSingleResult();
            assertThat(count.intValue()).isEqualTo(1);
        }
    }

    /**
     * 강의 관리 기능이 도입한 {@code klass} 컬럼 변경 2건.
     *
     * <p><b>"선언했다"와 "생성됐다"는 다르다</b>는 이 클래스의 전제가 그대로 적용된다.
     * 엔티티에 {@code nullable = false} 를 붙여도 DDL 에 반영되지 않으면 조용히 무방비가
     * 되고, 그 상태로 애플리케이션만 값을 강제하면 다른 경로로 들어온 데이터가 규칙을 깬다.
     *
     * <p>Design Ref: klass-management §3.1 스키마 변경, D-18
     */
    @Nested
    @DisplayName("강의 관리 — klass 컬럼 변경")
    class KlassColumnChange {

        /** {@code klass} 컬럼의 {@code is_nullable} 을 읽는다. 없는 컬럼이면 결과가 비어 있다. */
        private String nullableOf(String column) {
            return (String) em.createNativeQuery(
                            "select is_nullable from information_schema.columns "
                                    + "where table_schema = 'PUBLIC' and upper(table_name) = 'KLASS' "
                                    + "  and upper(column_name) = '" + column + "'")
                    .getSingleResult();
        }

        @Test
        @DisplayName("updated_at 이 NOT NULL 로 존재한다")
        void updatedAtExists() {
            assertThat(nullableOf("UPDATED_AT")).isEqualTo("NO");
        }

        @Test
        @DisplayName("description 이 NOT NULL 이다 — ERD 원안의 NULL 허용에서 바뀌었다 (D-18)")
        void descriptionIsNotNull() {
            assertThat(nullableOf("DESCRIPTION")).isEqualTo("NO");
        }

        @Test
        @DisplayName("내용 없는 강의를 DB 가 거부한다 — 앱을 우회해도 막힌다")
        void rejectsNullDescription() {
            assertThatThrownBy(() -> em.createNativeQuery(
                            "insert into klass (creator_id, title, description, price, capacity,"
                                    + " enrollment_count, status, starts_on, ends_on, created_at, updated_at)"
                                    + " values (?, '내용 없음', null, 1, 1, 0, 'DRAFT',"
                                    + " date '2026-10-01', date '2026-12-01', current_timestamp, current_timestamp)")
                    .setParameter(1, userId)
                    .executeUpdate())
                    // Exception 으로 두면 컬럼명 오타·SQL 문법 오류로도 통과한다.
                    // EntityManager 네이티브 쿼리는 Spring 예외 변환을 타지 않으므로
                    // Hibernate 의 예외를 직접 기대한다
                    .isInstanceOf(ConstraintViolationException.class);
        }

        @Test
        @DisplayName("생성 직후 created_at 과 updated_at 이 같다")
        void timestampsMatchOnCreation() {
            Number diff = (Number) em.createNativeQuery(
                            "select count(*) from klass "
                                    + "where id = ? and created_at = updated_at")
                    .setParameter(1, klassId)
                    .getSingleResult();
            assertThat(diff.intValue()).isEqualTo(1);
        }
    }

    /** FR-04 — 정원 불변식을 DB 가 지킨다. */
    @Nested
    @DisplayName("FR-04 CHECK 제약")
    class CheckConstraints {

        @Test
        @DisplayName("ERD 정본의 CHECK 제약 10종이 DDL 에 존재한다")
        void allCheckConstraintsExist() {
            for (String name : new String[] {
                    "CK_KLASS_CAPACITY", "CK_KLASS_COUNT", "CK_KLASS_PRICE",
                    "CK_KLASS_PERIOD", "CK_KLASS_CANCEL",
                    "CK_ENROLLMENT_PENDING", "CK_ENROLLMENT_CONFIRMED", "CK_ENROLLMENT_CANCELLED",
                    "CK_WAITLIST_POSITION", "CK_WAITLIST_PROMOTED"}) {

                Number count = (Number) em.createNativeQuery(
                                "select count(*) from information_schema.table_constraints "
                                        + "where constraint_type = 'CHECK' "
                                        + "  and upper(constraint_name) = '" + name + "'")
                        .getSingleResult();
                assertThat(count.intValue()).as(name).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("정원을 넘는 enrollment_count 를 DB 가 거부한다 — 오버부킹의 최종 방어선")
        void rejectsOverbooking() {
            assertThatThrownBy(() -> {
                em.createNativeQuery(
                                "update klass set enrollment_count = 11 where id = " + klassId)
                        .executeUpdate();
                em.flush();
            }).isInstanceOf(Exception.class);
        }

        /**
         * {@code ck_enrollment_cancelled} 가 <b>양방향</b>이 된 뒤의 검증이다 (D-49).
         * 원인 없는 취소가 들어오면 만료율 통계가 조용히 비어 버린다.
         */
        /**
         * <b>이름 존재만으로는 부족하다.</b> {@code allCheckConstraintsExist} 는
         * {@code CK_ENROLLMENT_CANCELLED} 라는 <b>이름</b>이 있는지만 센다 — 확장 전
         * 단방향 식이 그대로 남아 있어도 통과한다. 식 자체에 {@code cancel_reason} 이
         * 들어갔는지 확인한다 (D-49).
         */
        @Test
        @DisplayName("ck_enrollment_cancelled 식에 cancel_reason 이 실제로 들어가 있다")
        void cancelledCheckClauseCoversReason() {
            String clause = (String) em.createNativeQuery(
                            "select check_clause from information_schema.check_constraints "
                                    + "where upper(constraint_name) = 'CK_ENROLLMENT_CANCELLED'")
                    .getSingleResult();

            assertThat(clause.toUpperCase())
                    .as("이름만 남고 식이 확장 전이면 양방향 보장이 사라진다")
                    .contains("CANCEL_REASON");
        }

        @Test
        @DisplayName("CANCELLED 인데 cancel_reason 이 없으면 거부한다")
        void rejectsCancelledWithoutReason() {
            em.persist(pending(user));
            em.flush();

            assertThatThrownBy(() -> {
                em.createNativeQuery(
                                "update enrollment set status = 'CANCELLED', expires_at = null, "
                                        + "cancelled_at = current_timestamp "
                                        + "where user_id = " + userId)
                        .executeUpdate();
                em.flush();
            }).isInstanceOf(Exception.class);
        }

        @Test
        @DisplayName("CANCELLED 가 아닌데 cancel_reason 이 있으면 거부한다 — 역방향")
        void rejectsReasonOnNonCancelled() {
            em.persist(pending(user));
            em.flush();

            assertThatThrownBy(() -> {
                em.createNativeQuery(
                                "update enrollment set cancel_reason = 'USER' "
                                        + "where user_id = " + userId)
                        .executeUpdate();
                em.flush();
            }).isInstanceOf(Exception.class);
        }

        @Test
        @DisplayName("PENDING 인데 expires_at 이 없으면 거부한다 — 좌석 영구 점유 방지")
        void rejectsPendingWithoutExpiry() {
            assertThatThrownBy(() -> {
                em.persist(Enrollment.apply(klass, user, EnrollmentSource.DIRECT,
                        FIXED_NOW, null));
                em.flush();
            }).isInstanceOf(Exception.class);
        }

        /**
         * <b>네이티브 INSERT 로 검사하는 이유.</b> 원래는 {@code Klass.open(...)} 으로 역전된
         * 기간을 만들어 {@code persist} 했는데, 강의 관리 기능이 붙으면서 <b>팩토리가 먼저
         * 거부</b>하게 됐다({@code KlassError.INVALID_KLASS_PERIOD}). 그런데 이 단언은
         * {@code Exception} 을 받으므로 <b>테스트는 초록불인 채 검증 대상만 바뀐다</b> —
         * DB 제약이 사라져도 앱 가드가 대신 통과시켜 드러나지 않는다.
         *
         * <p>이 테스트의 책임은 <b>DDL 에 CHECK 가 살아 있는지</b>이므로 앱 계층을 우회한다.
         * 앱 가드 쪽은 {@code KlassTest} 가 따로 검증한다 (이중 방어).
         */
        @Test
        @DisplayName("종료일이 시작일보다 빠른 강의를 거부한다 — 앱을 우회해도 DB 가 막는다")
        void rejectsInvertedPeriod() {
            assertThatThrownBy(() -> em.createNativeQuery(
                            "insert into klass (creator_id, title, description, price, capacity,"
                                    + " enrollment_count, status, starts_on, ends_on, created_at, updated_at)"
                                    + " values (?, '역전된 기간', '내용', 1, 1, 0, 'DRAFT',"
                                    + " date '2026-10-01', date '2026-09-01', current_timestamp, current_timestamp)")
                    .setParameter(1, userId)
                    .executeUpdate())
                    .isInstanceOf(ConstraintViolationException.class);
        }

        @Test
        @DisplayName("대기 순번 0 이하를 거부한다")
        void rejectsNonPositivePosition() {
            assertThatThrownBy(() -> {
                em.persist(Waitlist.enqueue(klass, user, 0, FIXED_NOW));
                em.flush();
            }).isInstanceOf(Exception.class);
        }
    }

    /**
     * FR-03 — 수강 도메인 FK.
     *
     * <h2>이 테스트가 뒤늦게 추가된 이유</h2>
     * Check 단계에서 <b>FK 5개가 DDL 에 생성되지 않은 것</b>이 발견됐다. 값 참조
     * ({@code Long creatorId})만 두면 Hibernate 가 FK 를 만들지 않는데, 테이블·CHECK·인덱스만
     * 확인하고 {@code referential_constraints} 를 보지 않아 빌드가 통과했다.
     *
     * <p>ERD 정본 §3.1.1 이 FK 를 요구한 이유는 <b>고아 행 방지</b>다 — 존재하지 않는
     * {@code creator_id} 로 강의가 생기면 소유권 검사 {@code creator_id == sub} 를 아무도
     * 통과할 수 없어 관리 주체가 없는 강의가 영구히 남는다.
     */
    @Nested
    @DisplayName("FR-03 FK 제약")
    class ForeignKeys {

        @Test
        @DisplayName("수강 도메인 FK 5종이 DDL 에 존재한다")
        void allForeignKeysExist() {
            for (String name : new String[] {
                    "FK_KLASS_CREATOR",
                    "FK_ENROLLMENT_KLASS", "FK_ENROLLMENT_USER",
                    "FK_WAITLIST_KLASS", "FK_WAITLIST_USER"}) {

                Number count = (Number) em.createNativeQuery(
                                "select count(*) from information_schema.table_constraints "
                                        + "where constraint_type in ('REFERENTIAL', 'FOREIGN KEY') "
                                        + "  and upper(constraint_name) = '" + name + "'")
                        .getSingleResult();
                assertThat(count.intValue()).as(name).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("존재하지 않는 사용자로는 강의를 만들 수 없다 — 고아 행 방지")
        void rejectsOrphanKlass() {
            assertThatThrownBy(() -> {
                em.createNativeQuery(
                                "insert into klass (creator_id, title, price, capacity, enrollment_count,"
                                        + " status, starts_on, ends_on, created_at) values"
                                        + " (999999, '고아 강의', 0, 1, 0, 'DRAFT', '2026-09-01',"
                                        + " '2026-10-01', current_timestamp)")
                        .executeUpdate();
                em.flush();
            }).isInstanceOf(Exception.class);
        }
    }

    /** FR-05 — 활성 중복만 차단하고 취소 후 재신청은 허용한다. */
    @Nested
    @DisplayName("FR-05 활성 중복 차단 (생성 컬럼 + UNIQUE)")
    class ActiveDuplicateBlocking {

        @Test
        @DisplayName("같은 강의에 활성 신청이 둘이면 거부한다")
        void blocksSecondActiveEnrollment() {
            em.persist(pending(user));
            em.flush();

            assertThatThrownBy(() -> {
                em.persist(pending(user));
                em.flush();
            }).isInstanceOf(Exception.class);
        }

        @Test
        @DisplayName("취소한 뒤에는 다시 신청할 수 있다 — 생성 컬럼이 NULL 이 되기 때문")
        void allowsReapplyAfterCancellation() {
            em.persist(pending(user));
            em.flush();

            // 2차의 취소 로직 대신 DB 를 직접 바꾼다. 여기서 검증하려는 것은 스키마의 성질이다.
            em.createNativeQuery(
                            "update enrollment set status = 'CANCELLED', expires_at = null, "
                                    + "cancelled_at = current_timestamp, "
                                    // ck_enrollment_cancelled 가 양방향이라 원인이 없으면 거부된다 (D-49)
                                    + "cancel_reason = 'USER' where user_id = " + userId)
                    .executeUpdate();
            em.clear();

            assertThatCode(() -> {
                em.persist(pending(user));
                em.flush();
            }).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("생성 컬럼을 DB 가 채운다 — 활성이면 user_id, 취소면 NULL")
        void generatedColumnIsComputedByDatabase() {
            em.persist(pending(user));
            em.flush();
            em.clear();

            Object active = em.createNativeQuery(
                            "select active_user_key from enrollment where user_id = " + userId)
                    .getSingleResult();
            assertThat(((Number) active).longValue()).isEqualTo(userId);

            em.createNativeQuery(
                            "update enrollment set status = 'CANCELLED', expires_at = null, "
                                    + "cancelled_at = current_timestamp, "
                                    // ck_enrollment_cancelled 가 양방향이라 원인이 없으면 거부된다 (D-49)
                                    + "cancel_reason = 'USER' where user_id = " + userId)
                    .executeUpdate();

            Object cancelled = em.createNativeQuery(
                            "select active_user_key from enrollment where user_id = " + userId)
                    .getSingleResult();
            assertThat(cancelled).isNull();
        }
    }

    /** FR-15 — 조회 요건에 대응하는 인덱스가 생성된다. */
    @Nested
    @DisplayName("FR-15 인덱스")
    class Indexes {

        @Test
        @DisplayName("ERD 정본 §3.6 의 조회 인덱스가 모두 존재한다")
        void allIndexesExist() {
            for (String name : new String[] {
                    "IDX_KLASS_STATUS", "IDX_KLASS_CREATOR",
                    "IDX_ENROLLMENT_USER", "IDX_ENROLLMENT_KLASS_STATUS", "IDX_ENROLLMENT_EXPIRY",
                    "IDX_WAITLIST_NEXT"}) {

                Number count = (Number) em.createNativeQuery(
                                "select count(*) from information_schema.indexes "
                                        + "where upper(index_name) = '" + name + "'")
                        .getSingleResult();
                assertThat(count.intValue()).as(name).isGreaterThan(0);
            }
        }

        @Test
        @DisplayName("활성 중복을 막는 UNIQUE 제약 3종이 존재한다")
        void uniqueConstraintsExist() {
            // UNIQUE 는 H2 에서 인덱스가 아니라 제약으로 등록되므로 table_constraints 를 본다.
            // (뒷받침 인덱스의 이름은 H2 가 따로 정한다)
            for (String name : new String[] {
                    "UQ_ENROLLMENT_ACTIVE", "UQ_WAITLIST_POSITION", "UQ_WAITLIST_WAITING"}) {

                Number count = (Number) em.createNativeQuery(
                                "select count(*) from information_schema.table_constraints "
                                        + "where constraint_type = 'UNIQUE' "
                                        + "  and upper(constraint_name) = '" + name + "'")
                        .getSingleResult();
                assertThat(count.intValue()).as(name).isEqualTo(1);
            }
        }
    }

    /**
     * 대기열 제약이 <b>실제로 거부하는지</b> 확인한다 (수강신청 사이클 module-0).
     *
     * <h2>"존재한다"와 "작동한다"는 또 다르다</h2>
     * 이 클래스의 나머지는 {@code information_schema} 를 읽어 <b>제약이 생성됐는지</b>를 본다.
     * 그것으로 한 단계는 막았지만, <b>생성된 제약이 의도한 위반을 정말 거부하는지</b>는 넣어봐야
     * 안다. 대기열은 이번 사이클이 처음 쓰는 테이블이라 두 제약이 한 번도 작동한 적이 없다.
     *
     * <p>{@code uq_waitlist_position} 은 승격 순서의 최종 방어선이고,
     * {@code waiting_user_key} 는 "대기 포기 후 재대기"를 허용하는 근거다
     * (ERD 정본 §8 시나리오 36). 둘 다 앱이 먼저 막지만 앱에 버그가 생기는 순간
     * 이것만 남는다.
     *
     * <p>Design Ref: enrollment-management §9.6
     */
    @Nested
    @DisplayName("대기열 제약 동작 — 선언·생성을 넘어 실제 거부까지")
    class WaitlistConstraintBehavior {

        private Waitlist waiting(User who, int position) {
            return Waitlist.enqueue(klass, who, position, FIXED_NOW);
        }

        private User another(String username) {
            User other = User.register(username, "hashed", Set.of(Role.ROLE_USER), FIXED_NOW);
            em.persist(other);
            return other;
        }

        @Test
        @DisplayName("WaitlistStatus 도 ordinal 이 아니라 문자열로 저장된다")
        void waitlistEnumIsStoredAsString() {
            em.persist(waiting(user, 1));
            em.flush();
            em.clear();

            String status = (String) em.createNativeQuery(
                            "select status from waitlist where user_id = " + userId)
                    .getSingleResult();

            assertThat(status)
                    .as("ordinal 로 저장되면 enum 값 순서가 바뀔 때 기존 데이터가 "
                            + "조용히 다른 의미가 된다")
                    .isEqualTo("WAITING");
        }

        @Test
        @DisplayName("uq_waitlist_position 이 같은 강의의 순번 중복을 거부한다")
        void rejectsDuplicatePosition() {
            em.persist(waiting(user, 1));
            em.flush();

            User other = another("other");

            // IDENTITY 전략이라 persist 시점에 INSERT 가 나가므로 둘을 함께 감싼다
            assertThatThrownBy(() -> {
                em.persist(waiting(other, 1));
                em.flush();
            })
                    .as("승격은 position 순서로 일어난다. 순번이 겹치면 그 순서가 무너진다")
                    .isInstanceOf(ConstraintViolationException.class);
        }

        @Test
        @DisplayName("순번이 다르면 같은 강의에 여러 명이 대기할 수 있다 — 위 제약이 과잉이 아니다")
        void allowsDistinctPositions() {
            em.persist(waiting(user, 1));
            em.persist(waiting(another("second"), 2));

            assertThatCode(em::flush).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("uq_waitlist_waiting 이 같은 강의 활성 중복 대기를 거부한다")
        void rejectsDuplicateActiveWaiting() {
            em.persist(waiting(user, 1));
            em.flush();

            assertThatThrownBy(() -> {
                em.persist(waiting(user, 2));   // 순번은 다르지만 같은 사용자다
                em.flush();
            })
                    .as("waiting_user_key 가 WAITING 인 행의 user_id 를 담으므로 충돌해야 한다")
                    .isInstanceOf(ConstraintViolationException.class);
        }

        @Test
        @DisplayName("waiting_user_key 는 WAITING 일 때만 값을 갖는다 — 생성 컬럼이 실제로 계산된다")
        void generatedKeyIsNullWhenNotWaiting() {
            em.persist(waiting(user, 1));
            em.flush();

            Number activeKey = (Number) em.createNativeQuery(
                            "select waiting_user_key from waitlist where user_id = " + userId)
                    .getSingleResult();
            assertThat(activeKey)
                    .as("WAITING 이면 user_id 가 들어와야 한다")
                    .isNotNull()
                    .extracting(Number::longValue)
                    .isEqualTo(userId);

            // 상태를 바꾸면 DB 가 생성 컬럼을 다시 계산해야 한다
            em.createNativeQuery(
                            "update waitlist set status = 'CANCELLED' where user_id = " + userId)
                    .executeUpdate();
            em.clear();

            Object cancelledKey = em.createNativeQuery(
                            "select waiting_user_key from waitlist where user_id = " + userId)
                    .getSingleResult();
            assertThat(cancelledKey)
                    .as("WAITING 이 아니면 NULL 이어야 재대기가 가능해진다")
                    .isNull();
        }

        @Test
        @DisplayName("대기 포기 후 같은 강의에 재대기할 수 있다 — 정본 시나리오 36")
        void allowsReEnqueueAfterGivingUp() {
            em.persist(waiting(user, 1));
            em.flush();

            em.createNativeQuery(
                            "update waitlist set status = 'CANCELLED' where user_id = " + userId)
                    .executeUpdate();
            em.clear();

            // 같은 사용자, 같은 강의, 새 순번. NULL 은 UNIQUE 에서 서로 충돌하지 않는다
            assertThatCode(() -> {
                em.persist(Waitlist.enqueue(em.find(Klass.class, klassId),
                        em.find(User.class, userId), 2, FIXED_NOW));
                em.flush();
            })
                    .as("부분 유니크의 존재 이유가 이것이다. 막히면 포기한 사용자가 "
                            + "영구히 재대기할 수 없다")
                    .doesNotThrowAnyException();
        }
    }
}
