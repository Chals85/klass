package com.toby.klass.enrollment.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.toby.klass.common.application.dto.CursorPageResult;
import com.toby.klass.enrollment.application.dto.EnrollmentQuery;
import com.toby.klass.enrollment.domain.Enrollment;
import com.toby.klass.enrollment.domain.EnrollmentSource;
import com.toby.klass.enrollment.domain.EnrollmentStatus;
import com.toby.klass.infrastructure.config.QueryDslConfig;
import com.toby.klass.klass.domain.Klass;
import com.toby.klass.user.domain.Role;
import com.toby.klass.user.domain.User;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.hibernate.Hibernate;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

/**
 * 수강 신청 포트 구현 검증 (L2).
 *
 * <h2>이 클래스가 잡으려는 것</h2>
 * <ol>
 *   <li><b>파생 쿼리 속성 경로</b> — 틀리면 Hibernate 부트스트랩에서 앱이 안 뜬다.
 *       컨텍스트가 떴다는 사실 자체가 첫 검증이다</li>
 *   <li><b>활성 판정의 정의</b> — {@code uq_enrollment_active} 의 생성 컬럼과 어긋나면
 *       앱은 통과시키고 DB 가 거부하거나 그 반대가 된다</li>
 *   <li><b>fetch join</b> — 목록마다 필요한 연관이 반대다. 빠지면 N+1 이 나고
 *       {@code open-in-view: false} 라 컨트롤러에서 터진다</li>
 * </ol>
 *
 * <p>Design Ref: enrollment-management §9.3, R-07
 */
@DataJpaTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Import({QueryDslConfig.class, EnrollmentQueryDslRepository.class,
        EnrollmentRepositoryAdapter.class})
@DisplayName("EnrollmentRepositoryAdapter — 포트 구현")
class EnrollmentRepositoryAdapterTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 1, 10, 0);
    private static final LocalDate STARTS_ON = LocalDate.of(2026, 11, 1);
    private static final LocalDate ENDS_ON = LocalDate.of(2026, 12, 31);

    @Autowired
    private EntityManager em;

    @Autowired
    private EnrollmentRepositoryAdapter adapter;

    private Klass klass;
    private Klass otherKlass;
    private User student;

    @BeforeEach
    void setUp() {
        User creator = persistUser("creator", Role.ROLE_CREATOR);
        student = persistUser("student", Role.ROLE_USER);

        klass = persistKlass(creator, "스프링 부트 입문");
        otherKlass = persistKlass(creator, "다른 강의");

        em.flush();
        em.clear();
    }

    private User persistUser(String username, Role role) {
        User user = User.register(username, "hashed", Set.of(role), NOW);
        em.persist(user);
        return user;
    }

    private Klass persistKlass(User creator, String title) {
        Klass created = Klass.open(creator, title, "내용", new BigDecimal("50000"),
                10, STARTS_ON, ENDS_ON, 7, NOW);
        created.publish(NOW);
        em.persist(created);
        return created;
    }

    /** 영속 상태의 신청을 만든다. 상태 전이는 반환값에 직접 건다. */
    private Enrollment persistEnrollment(Klass target, User who) {
        Enrollment enrollment = Enrollment.apply(
                em.find(Klass.class, target.getId()), em.find(User.class, who.getId()),
                EnrollmentSource.DIRECT, NOW, NOW.plusMinutes(30));
        em.persist(enrollment);
        return enrollment;
    }

    private Statistics statistics() {
        return em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
    }

    @Nested
    @DisplayName("단건 조회 — 락과 조인이 갈린다")
    class SingleFetch {

        @Test
        @DisplayName("findById 는 강의를 함께 읽는다 — 상세 응답이 강의 제목을 쓴다")
        void findByIdJoinsKlass() {
            Long id = persistEnrollment(klass, student).getId();
            em.flush();
            em.clear();

            Enrollment found = adapter.findById(id).orElseThrow();

            assertThat(Hibernate.isInitialized(found.getKlass()))
                    .as("프록시로 남으면 상세 응답을 만들 때 조회가 한 번 더 나간다")
                    .isTrue();
        }

        @Test
        @DisplayName("findWithLockById 는 강의를 조인하지 않는다 — 락 대상이 번지면 안 된다")
        void lockFetchDoesNotJoinKlass() {
            Long id = persistEnrollment(klass, student).getId();
            em.flush();
            em.clear();

            Enrollment found = adapter.findWithLockById(id).orElseThrow();

            assertThat(Hibernate.isInitialized(found.getKlass()))
                    .as("조인하면 klass 행까지 잠겨 락 획득 순서(§4.1)가 뒤집힌다")
                    .isFalse();
        }

        @Test
        @DisplayName("findKlassIdById 는 엔티티를 로딩하지 않고 강의 id 만 준다")
        void fetchesKlassIdOnly() {
            Long id = persistEnrollment(klass, student).getId();
            em.flush();
            em.clear();
            statistics().clear();

            Long klassId = adapter.findKlassIdById(id).orElseThrow();

            assertThat(klassId).isEqualTo(klass.getId());
            assertThat(statistics().getEntityLoadCount())
                    .as("취소가 락 순서를 지키려고 부르는 것이라 엔티티까지 읽을 이유가 없다")
                    .isZero();
        }

        @Test
        @DisplayName("없는 id 는 빈 Optional 이다 — 세 조회 모두")
        void missingIdYieldsEmpty() {
            assertThat(adapter.findById(999L)).isEmpty();
            assertThat(adapter.findWithLockById(999L)).isEmpty();
            assertThat(adapter.findKlassIdById(999L)).isEmpty();
        }
    }

    @Nested
    @DisplayName("활성 신청 판정 — uq_enrollment_active 와 같은 정의여야 한다")
    class ActiveExistence {

        @Test
        @DisplayName("PENDING 은 활성이다")
        void pendingIsActive() {
            persistEnrollment(klass, student);
            em.flush();
            em.clear();

            assertThat(adapter.existsActive(klass.getId(), student.getId())).isTrue();
        }

        @Test
        @DisplayName("CONFIRMED 도 활성이다 — 결제했다고 자리가 비는 것은 아니다")
        void confirmedIsActive() {
            Enrollment enrollment = persistEnrollment(klass, student);
            enrollment.confirm(NOW.plusMinutes(10));
            em.flush();
            em.clear();

            assertThat(adapter.existsActive(klass.getId(), student.getId())).isTrue();
        }

        @Test
        @DisplayName("CANCELLED 는 활성이 아니다 — 취소 후 재신청의 근거")
        void cancelledIsNotActive() {
            Enrollment enrollment = persistEnrollment(klass, student);
            enrollment.cancel(NOW.plusMinutes(5), STARTS_ON,
                    em.find(Klass.class, klass.getId()).cancellationPolicy(7));
            em.flush();
            em.clear();

            assertThat(adapter.existsActive(klass.getId(), student.getId()))
                    .as("생성 컬럼 active_user_key 가 NULL 이 되는 것과 같은 판정이어야 한다")
                    .isFalse();
        }

        @Test
        @DisplayName("다른 강의의 신청은 세지 않는다")
        void scopedToKlass() {
            persistEnrollment(otherKlass, student);
            em.flush();
            em.clear();

            assertThat(adapter.existsActive(klass.getId(), student.getId())).isFalse();
            assertThat(adapter.existsActive(otherKlass.getId(), student.getId())).isTrue();
        }

        @Test
        @DisplayName("다른 사용자의 신청은 세지 않는다")
        void scopedToUser() {
            User another = persistUser("another", Role.ROLE_USER);
            persistEnrollment(klass, another);
            em.flush();
            em.clear();

            assertThat(adapter.existsActive(klass.getId(), student.getId())).isFalse();
        }
    }

    @Nested
    @DisplayName("내 신청 목록")
    class UserPage {

        @BeforeEach
        void enrollThree() {
            persistEnrollment(klass, student);
            persistEnrollment(otherKlass, student);
            persistEnrollment(klass, persistUser("stranger", Role.ROLE_USER));
            em.flush();
            em.clear();
        }

        @Test
        @DisplayName("본인 것만, id 내림차순으로 준다")
        void returnsOwnOnly() {
            CursorPageResult<Enrollment> page = adapter.findUserPage(
                    student.getId(), new EnrollmentQuery(null, 20, null));

            assertThat(page.items()).hasSize(2);
            assertThat(page.items())
                    .extracting(Enrollment::getId)
                    .isSortedAccordingTo(java.util.Comparator.reverseOrder());
            assertThat(page.hasNext()).isFalse();
        }

        @Test
        @DisplayName("강의를 fetch join 한다 — 없으면 목록 렌더링에서 N+1 이다")
        void fetchJoinsKlass() {
            CursorPageResult<Enrollment> page = adapter.findUserPage(
                    student.getId(), new EnrollmentQuery(null, 20, null));

            assertThat(page.items())
                    .allSatisfy(e -> assertThat(Hibernate.isInitialized(e.getKlass()))
                            .as("klassTitle 과 isCancellable 이 둘 다 klass 를 필요로 한다")
                            .isTrue());
        }

        @Test
        @DisplayName("연관 접근이 추가 쿼리를 만들지 않는다")
        void noAdditionalQueryOnAccess() {
            CursorPageResult<Enrollment> page = adapter.findUserPage(
                    student.getId(), new EnrollmentQuery(null, 20, null));
            statistics().clear();

            page.items().forEach(e -> e.getKlass().getTitle());

            assertThat(statistics().getPrepareStatementCount())
                    .as("절대 쿼리 수가 아니라 '접근으로 늘어난 쿼리'를 센다")
                    .isZero();
        }

        @Test
        @DisplayName("상태 필터가 먹는다")
        void filtersByStatus() {
            CursorPageResult<Enrollment> pending = adapter.findUserPage(
                    student.getId(), new EnrollmentQuery(null, 20, EnrollmentStatus.PENDING));
            CursorPageResult<Enrollment> confirmed = adapter.findUserPage(
                    student.getId(), new EnrollmentQuery(null, 20, EnrollmentStatus.CONFIRMED));

            assertThat(pending.items()).hasSize(2);
            assertThat(confirmed.items()).isEmpty();
        }

        @Test
        @DisplayName("커서가 다음 페이지를 정확히 이어붙인다")
        void paginatesWithCursor() {
            CursorPageResult<Enrollment> first = adapter.findUserPage(
                    student.getId(), new EnrollmentQuery(null, 1, null));

            assertThat(first.items()).hasSize(1);
            assertThat(first.hasNext()).isTrue();
            assertThat(first.nextCursor()).isEqualTo(first.items().get(0).getId());

            CursorPageResult<Enrollment> second = adapter.findUserPage(
                    student.getId(), new EnrollmentQuery(first.nextCursor(), 1, null));

            assertThat(second.items()).hasSize(1);
            assertThat(second.items().get(0).getId())
                    .as("커서보다 작은 id 여야 한다 — 같은 행이 두 번 나오면 안 된다")
                    .isLessThan(first.nextCursor());
            assertThat(second.hasNext()).isFalse();
        }
    }

    @Nested
    @DisplayName("강의별 수강생 목록")
    class KlassPage {

        @BeforeEach
        void enrollTwo() {
            persistEnrollment(klass, student);
            persistEnrollment(klass, persistUser("second", Role.ROLE_USER));
            persistEnrollment(otherKlass, student);
            em.flush();
            em.clear();
        }

        @Test
        @DisplayName("해당 강의 것만 준다")
        void scopedToKlass() {
            CursorPageResult<Enrollment> page = adapter.findKlassPage(
                    klass.getId(), new EnrollmentQuery(null, 20, null));

            assertThat(page.items()).hasSize(2);
        }

        @Test
        @DisplayName("수강생을 fetch join 한다 — 강의가 아니다")
        void fetchJoinsUserNotKlass() {
            List<Enrollment> items = adapter.findKlassPage(
                    klass.getId(), new EnrollmentQuery(null, 20, null)).items();

            assertThat(items).allSatisfy(e -> {
                assertThat(Hibernate.isInitialized(e.getUser()))
                        .as("username 이 응답에 들어간다").isTrue();
                assertThat(Hibernate.isInitialized(e.getKlass()))
                        .as("강의는 경로에 이미 있어 조인할 이유가 없다").isFalse();
            });
        }
    }

    /**
     * 만료 회수 후보 조회 (L2).
     *
     * <h4>이 절이 잡으려는 것</h4>
     * {@code findExpiredIds} 는 <b>경계·정렬·상한을 한 쿼리에 담고 있다.</b> 셋 중 하나만
     * 틀려도 배치는 정상적으로 돌면서 잘못된 대상을 집는다 — 결제를 마친 좌석을 회수하거나,
     * 오래 묶인 좌석을 영영 뒤로 미루거나, 한 사이클이 끝나지 않는다.
     *
     * <p>특히 <b>경계는 도메인 {@code isExpiredAt} 과 같아야 한다.</b> 어긋나면 배치가 집어온
     * 후보가 재확인에서 전부 걸러지거나, 아직 유효한 신청을 집어 온다.
     *
     * <p>Design Ref: pending-expiry-reaper §5.4 · §8.3
     */
    @Nested
    @DisplayName("만료 회수 후보 조회 — 경계·정렬·상한")
    class ExpiredIds {

        /** {@code NOW} 를 기준 시각으로 삼고, 만료 시각을 그 앞뒤로 배치한다. */
        private Enrollment persistWithExpiry(User who, LocalDateTime expiresAt) {
            Enrollment enrollment = Enrollment.apply(
                    em.find(Klass.class, klass.getId()), em.find(User.class, who.getId()),
                    EnrollmentSource.DIRECT, NOW.minusHours(1), expiresAt);
            em.persist(enrollment);
            return enrollment;
        }

        @Test
        @DisplayName("기한이 지난 PENDING 을 반환한다")
        void returnsExpiredPending() {
            Enrollment expired = persistWithExpiry(student, NOW.minusMinutes(1));
            em.flush();
            em.clear();

            assertThat(adapter.findExpiredIds(NOW, 10)).containsExactly(expired.getId());
        }

        /**
         * 도메인 {@code isExpiredAt} 도 같은 시각을 만료로 본다. <b>두 경계가 같아야</b>
         * 배치가 집어온 후보가 재확인을 통과한다.
         */
        @Test
        @DisplayName("경계 — 정확히 기준 시각인 것도 반환한다")
        void includesSameInstant() {
            Enrollment boundary = persistWithExpiry(student, NOW);
            em.flush();
            em.clear();

            assertThat(adapter.findExpiredIds(NOW, 10)).containsExactly(boundary.getId());
        }

        /**
         * <b>1나노초를 쓰면 안 된다.</b> H2 의 {@code TIMESTAMP} 기본 정밀도는 마이크로초라
         * 나노초 단위 차이는 저장 시 잘려 {@code NOW} 와 같아지고, 그러면 이 테스트는 경계를
         * 검증하는 것이 아니라 <b>같은 시각을 검증하는 두 번째 테스트</b>가 된다.
         *
         * <p>도메인 {@code isExpiredAt} 은 나노초까지 판정하지만(L1 이 검증한다), <b>영속화된
         * 값이 가질 수 있는 최소 간격은 1마이크로초</b>다. 실 DB 전환 시 정밀도가 더 낮으면
         * (예: MySQL {@code DATETIME} 은 기본 초 단위) 이 값을 다시 키워야 한다.
         */
        @Test
        @DisplayName("경계 — 기한이 1마이크로초라도 남았으면 반환하지 않는다")
        void excludesNotYetExpired() {
            persistWithExpiry(student, NOW.plusNanos(1_000));
            em.flush();
            em.clear();

            assertThat(adapter.findExpiredIds(NOW, 10)).isEmpty();
        }

        /**
         * {@code ck_enrollment_pending} 때문에 종착 상태는 {@code expires_at} 이 {@code null}
         * 이라 어차피 걸리지 않는다. 그래도 검증하는 이유는 <b>제약이 바뀌어도 이 쿼리가
         * 여전히 옳아야</b> 하기 때문이다.
         */
        @Test
        @DisplayName("PENDING 이 아닌 것은 반환하지 않는다")
        void excludesTerminalStates() {
            Enrollment confirmed = persistWithExpiry(student, NOW.minusMinutes(1));
            confirmed.confirm(NOW.minusMinutes(30));
            em.flush();
            em.clear();

            assertThat(adapter.findExpiredIds(NOW, 10)).isEmpty();
        }

        @Test
        @DisplayName("만료가 오래된 순서다 — 오래 묶인 좌석을 먼저 푼다")
        void ordersByExpiryAscending() {
            User other = persistUser("student2", Role.ROLE_USER);
            Enrollment newer = persistWithExpiry(student, NOW.minusMinutes(1));
            Enrollment older = persistWithExpiry(other, NOW.minusMinutes(30));
            em.flush();
            em.clear();

            assertThat(adapter.findExpiredIds(NOW, 10))
                    .containsExactly(older.getId(), newer.getId());
        }

        @Test
        @DisplayName("상한을 넘겨 반환하지 않는다 — 한 사이클이 길어지지 않는다")
        void respectsLimit() {
            User other = persistUser("student3", Role.ROLE_USER);
            Enrollment older = persistWithExpiry(other, NOW.minusMinutes(30));
            persistWithExpiry(student, NOW.minusMinutes(1));
            em.flush();
            em.clear();

            assertThat(adapter.findExpiredIds(NOW, 1))
                    .as("상한에 걸려 잘려도 가장 오래된 것이 남는다")
                    .containsExactly(older.getId());
        }

        @Test
        @DisplayName("대상이 없으면 빈 목록이다 — null 이 아니다")
        void returnsEmptyWhenNoTarget() {
            assertThat(adapter.findExpiredIds(NOW, 10)).isNotNull().isEmpty();
        }
    }

}
