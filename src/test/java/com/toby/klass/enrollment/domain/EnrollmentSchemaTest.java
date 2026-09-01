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

    @Autowired
    private EntityManager em;

    private Klass klass;
    private User user;
    private Long klassId;
    private Long userId;

    @BeforeEach
    void setUp() {
        User user = User.register("tester", "hashed", Set.of(Role.ROLE_USER), LocalDateTime.now());
        em.persist(user);

        Klass klass = Klass.open(user, "테스트 강의", null, new BigDecimal("10000"),
                10, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1), 7, LocalDateTime.now());
        em.persist(klass);
        em.flush();

        this.user = user;
        this.klass = klass;
        this.userId = user.getId();
        this.klassId = klass.getId();
    }

    private Enrollment pending(User who) {
        return Enrollment.apply(klass, who, EnrollmentSource.DIRECT,
                LocalDateTime.now(), LocalDateTime.now().plusMinutes(30));
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

        @Test
        @DisplayName("PENDING 인데 expires_at 이 없으면 거부한다 — 좌석 영구 점유 방지")
        void rejectsPendingWithoutExpiry() {
            assertThatThrownBy(() -> {
                em.persist(Enrollment.apply(klass, user, EnrollmentSource.DIRECT,
                        LocalDateTime.now(), null));
                em.flush();
            }).isInstanceOf(Exception.class);
        }

        @Test
        @DisplayName("종료일이 시작일보다 빠른 강의를 거부한다")
        void rejectsInvertedPeriod() {
            assertThatThrownBy(() -> {
                em.persist(Klass.open(user, "역전된 기간", null, BigDecimal.ONE, 1,
                        LocalDate.of(2026, 10, 1), LocalDate.of(2026, 9, 1), null, LocalDateTime.now()));
                em.flush();
            }).isInstanceOf(Exception.class);
        }

        @Test
        @DisplayName("대기 순번 0 이하를 거부한다")
        void rejectsNonPositivePosition() {
            assertThatThrownBy(() -> {
                em.persist(Waitlist.enqueue(klass, user, 0, LocalDateTime.now()));
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
                                    + "cancelled_at = current_timestamp where user_id = " + userId)
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
                                    + "cancelled_at = current_timestamp where user_id = " + userId)
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
}
