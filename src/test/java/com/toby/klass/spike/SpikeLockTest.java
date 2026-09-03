package com.toby.klass.spike;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.toby.klass.enrollment.domain.Enrollment;
import com.toby.klass.enrollment.domain.EnrollmentSource;
import com.toby.klass.enrollment.domain.EnrollmentStatus;
import com.toby.klass.klass.domain.Klass;
import com.toby.klass.user.domain.Role;
import com.toby.klass.user.domain.User;
import com.toby.klass.waitlist.domain.Waitlist;
import com.toby.klass.waitlist.domain.WaitlistStatus;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * 설계서 §4.2 · §13 R-04 의 전제를 <b>module-2 착수 전에</b> 판정하는 스파이크.
 *
 * <p>확인 대상 3종. 하나라도 실패하면 설계서를 먼저 고친다.
 *
 * <ol>
 *   <li>{@code findWithLockById} 가 파생 쿼리로 해석되고 {@code FOR UPDATE} 를 만드는가
 *       (설계 §4.2 1번)</li>
 *   <li>{@code findFirst...OrderByPositionAsc} + {@code @Lock} 이 H2 2.4.240 에서
 *       거부되지 않는가 (설계 §13 R-04)</li>
 *   <li>{@code existsByKlassIdAndUserIdAndStatusIn} 의 속성 경로가 부트스트랩을 통과하는가
 *       (CLAUDE.md 지점 2번)</li>
 * </ol>
 *
 * <p><b>이 클래스는 판정용이며 module-2 완료 시 삭제한다.</b> 남겨두면 실제 리포지토리와
 * 같은 것을 두 벌 검증하게 된다.
 */
@DataJpaTest(properties = {
        "spring.jpa.properties.hibernate.session_factory.statement_inspector="
                + "com.toby.klass.spike.SqlCapture"})
@EnableJpaRepositories(basePackageClasses = SpikeKlassRepository.class)
@DisplayName("스파이크: 비관적 락 파생 쿼리가 실제로 동작하는가")
class SpikeLockTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 3, 10, 0);
    private static final LocalDate STARTS_ON = LocalDate.of(2026, 10, 1);
    private static final LocalDate ENDS_ON = LocalDate.of(2026, 12, 31);

    @Autowired
    private EntityManager em;

    @Autowired
    private SpikeKlassRepository klassRepository;

    @Autowired
    private SpikeWaitlistRepository waitlistRepository;

    @Autowired
    private SpikeEnrollmentRepository enrollmentRepository;

    private Klass klass;
    private User student;

    @BeforeEach
    void setUp() {
        User creator = User.register("creator", "hashed", Set.of(Role.ROLE_CREATOR), NOW);
        student = User.register("student", "hashed", Set.of(Role.ROLE_USER), NOW);
        em.persist(creator);
        em.persist(student);

        klass = Klass.open(creator, "스파이크 강의", "내용", BigDecimal.valueOf(10000),
                10, STARTS_ON, ENDS_ON, 7, NOW);
        klass.publish(NOW);
        em.persist(klass);
        em.flush();
        em.clear();

        SqlCapture.reset();
    }

    @Nested
    @DisplayName("① klass 단건 락 조회")
    class KlassLock {

        @Test
        @DisplayName("findWithLockById 가 파생 쿼리로 해석되고 FOR UPDATE 를 붙인다")
        void emitsForUpdate() {
            Optional<Klass> found = klassRepository.findWithLockById(klass.getId());

            assertThat(found).as("이름이 파생 쿼리로 해석되지 않으면 여기서 이미 깨진다")
                    .isPresent();

            SqlCapture.dump("① klass 단건 락 조회");
            String sql = SqlCapture.lastSelect().toLowerCase();
            assertThat(sql)
                    .as("실제로 나간 SQL: %s", SqlCapture.lastSelect())
                    .contains("for update");
        }

        @Test
        @DisplayName("락 조회는 개설자를 조인하지 않는다 — 락 대상이 klass 단일 행이어야 한다")
        void doesNotJoinCreator() {
            klassRepository.findWithLockById(klass.getId());

            String sql = SqlCapture.lastSelect().toLowerCase();
            assertThat(sql)
                    .as("@EntityGraph 없이 선언했으므로 users 조인이 없어야 한다. 실제: %s",
                            SqlCapture.lastSelect())
                    .doesNotContain("join");
        }
    }

    @Nested
    @DisplayName("② 승격 대상 1건 락 조회 (H2 의 ORDER BY … LIMIT 1 FOR UPDATE)")
    class WaitlistNextLock {

        @BeforeEach
        void enqueueThree() {
            Klass managed = em.find(Klass.class, klass.getId());
            for (int position = 1; position <= 3; position++) {
                User waiter = User.register("waiter" + position, "hashed",
                        Set.of(Role.ROLE_USER), NOW);
                em.persist(waiter);
                em.persist(Waitlist.enqueue(managed, waiter, position, NOW));
            }
            em.flush();
            em.clear();
            SqlCapture.reset();
        }

        @Test
        @DisplayName("position 오름차순 1건을 락 걸고 꺼낸다 — H2 가 거부하지 않는다")
        void picksFirstWaitingWithLock() {
            Optional<Waitlist> next = waitlistRepository
                    .findFirstWithLockByKlassIdAndStatusAndPositionGreaterThanOrderByPositionAsc(
                            klass.getId(), WaitlistStatus.WAITING, 0);

            assertThat(next).isPresent();
            assertThat(next.get().getPosition())
                    .as("가장 앞선 순번이어야 한다")
                    .isEqualTo(1);

            SqlCapture.dump("② 승격 대상 1건 락 조회");
            String sql = SqlCapture.lastSelect().toLowerCase();
            assertThat(sql).as("실제: %s", SqlCapture.lastSelect())
                    .contains("for update")
                    .contains("order by");
        }

        @Test
        @DisplayName("lastPos 를 넘기면 그 뒤 순번부터 찾는다 — 승격 루프의 건너뛰기가 성립한다")
        void skipsAlreadyVisited() {
            Optional<Waitlist> next = waitlistRepository
                    .findFirstWithLockByKlassIdAndStatusAndPositionGreaterThanOrderByPositionAsc(
                            klass.getId(), WaitlistStatus.WAITING, 1);

            assertThat(next).isPresent();
            assertThat(next.get().getPosition()).isEqualTo(2);
        }

        @Test
        @DisplayName("position 이 SQL 예약어인데도 파생 쿼리가 컬럼으로 다룬다")
        void reservedWordIsUsable() {
            assertThatCode(() -> waitlistRepository
                    .findFirstWithLockByKlassIdAndStatusAndPositionGreaterThanOrderByPositionAsc(
                            klass.getId(), WaitlistStatus.WAITING, 0))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("③ 활성 중복 신청 검사")
    class ActiveEnrollmentExists {

        @Test
        @DisplayName("existsByKlassIdAndUserIdAndStatusIn 의 속성 경로가 통한다")
        void derivedPathResolves() {
            Klass managed = em.find(Klass.class, klass.getId());
            User managedStudent = em.find(User.class, student.getId());
            em.persist(Enrollment.apply(managed, managedStudent, EnrollmentSource.DIRECT,
                    NOW, NOW.plusMinutes(30)));
            em.flush();
            em.clear();

            boolean active = enrollmentRepository.existsByKlassIdAndUserIdAndStatusIn(
                    klass.getId(), student.getId(),
                    Set.of(EnrollmentStatus.PENDING, EnrollmentStatus.CONFIRMED));

            assertThat(active).isTrue();
        }

        @Test
        @DisplayName("CANCELLED 는 활성으로 세지 않는다")
        void cancelledIsNotActive() {
            boolean active = enrollmentRepository.existsByKlassIdAndUserIdAndStatusIn(
                    klass.getId(), student.getId(),
                    Set.of(EnrollmentStatus.PENDING, EnrollmentStatus.CONFIRMED));

            assertThat(active).isFalse();
        }
    }
}
