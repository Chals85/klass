package com.toby.klass.waitlist.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.toby.klass.common.application.dto.CursorPageResult;
import com.toby.klass.waitlist.application.dto.WaitlistQuery;
import com.toby.klass.infrastructure.config.QueryDslConfig;
import com.toby.klass.klass.domain.Klass;
import com.toby.klass.user.domain.Role;
import com.toby.klass.user.domain.User;
import com.toby.klass.waitlist.domain.Waitlist;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

/**
 * 대기열 포트 구현 검증 (L2).
 *
 * <h2>승격 루프가 이 포트에 전적으로 의존한다</h2>
 * {@code findNextWaitingWithLock} 이 순번 순서를 지키지 않거나 {@code afterPosition} 을
 * 무시하면 <b>승격 순서가 무너지거나 루프가 무한히 같은 행을 돈다.</b> 서비스에서 잡기
 * 어려운 종류의 버그라 어댑터 단계에서 못박는다.
 *
 * <p>Design Ref: enrollment-management §9.3, §4.3 ④
 */
@DataJpaTest
@Import({QueryDslConfig.class, WaitlistQueryDslRepository.class, WaitlistRepositoryAdapter.class})
@DisplayName("WaitlistRepositoryAdapter — 포트 구현")
class WaitlistRepositoryAdapterTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 1, 10, 0);
    private static final LocalDate STARTS_ON = LocalDate.of(2026, 11, 1);
    private static final LocalDate ENDS_ON = LocalDate.of(2026, 12, 31);

    @Autowired
    private EntityManager em;

    @Autowired
    private WaitlistRepositoryAdapter adapter;

    private Klass klass;
    private Klass otherKlass;

    @BeforeEach
    void setUp() {
        User creator = persistUser("creator", Role.ROLE_CREATOR);
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

    /** 대기 행을 만든다. 사용자도 함께 만든다 — 활성 중복 제약 때문에 재사용할 수 없다. */
    private Waitlist enqueue(Klass target, String username, int position) {
        Waitlist waitlist = Waitlist.enqueue(
                em.find(Klass.class, target.getId()),
                persistUser(username, Role.ROLE_USER), position, NOW);
        em.persist(waitlist);
        return waitlist;
    }

    @Nested
    @DisplayName("단건 조회")
    class SingleFetch {

        @Test
        @DisplayName("findById 는 강의를 함께 읽는다")
        void findByIdJoinsKlass() {
            Long id = enqueue(klass, "waiter", 1).getId();
            em.flush();
            em.clear();

            Waitlist found = adapter.findById(id).orElseThrow();

            assertThat(Hibernate.isInitialized(found.getKlass())).isTrue();
        }

        @Test
        @DisplayName("findWithLockById 는 조인하지 않는다 — waitlist 단독 락이 §4.1 의 예외다")
        void lockFetchDoesNotJoin() {
            Long id = enqueue(klass, "waiter", 1).getId();
            em.flush();
            em.clear();

            Waitlist found = adapter.findWithLockById(id).orElseThrow();

            assertThat(Hibernate.isInitialized(found.getKlass()))
                    .as("조인하면 klass 행까지 잠겨 '락 하나만 잡는다'는 예외가 성립하지 않는다")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("승격 대상 조회 — 순서가 전부다")
    class NextWaiting {

        @BeforeEach
        void enqueueThree() {
            enqueue(klass, "first", 1);
            enqueue(klass, "second", 2);
            enqueue(klass, "third", 3);
            em.flush();
            em.clear();
        }

        @Test
        @DisplayName("0 을 넘기면 가장 앞선 순번을 준다")
        void picksLowestPosition() {
            Waitlist next = adapter.findNextWaitingWithLock(klass.getId(), 0).orElseThrow();

            assertThat(next.getPosition()).isEqualTo(1);
        }

        @Test
        @DisplayName("afterPosition 뒤부터 찾는다 — 부적격자 건너뛰기가 성립한다")
        void skipsUpToPosition() {
            assertThat(adapter.findNextWaitingWithLock(klass.getId(), 1).orElseThrow()
                    .getPosition()).isEqualTo(2);
            assertThat(adapter.findNextWaitingWithLock(klass.getId(), 2).orElseThrow()
                    .getPosition()).isEqualTo(3);
        }

        @Test
        @DisplayName("마지막 순번을 넘기면 빈 Optional — 루프의 종료 조건이다")
        void emptyWhenExhausted() {
            assertThat(adapter.findNextWaitingWithLock(klass.getId(), 3))
                    .as("여기서 끝나지 않으면 승격 루프가 무한히 돈다")
                    .isEmpty();
        }

        @Test
        @DisplayName("WAITING 이 아닌 행은 건너뛴다 — 승격·포기한 사람이 다시 뽑히지 않는다")
        void ignoresNonWaiting() {
            Waitlist first = adapter.findNextWaitingWithLock(klass.getId(), 0).orElseThrow();
            first.promote(NOW.plusHours(1));
            em.flush();
            em.clear();

            assertThat(adapter.findNextWaitingWithLock(klass.getId(), 0).orElseThrow()
                    .getPosition())
                    .as("승격된 1번을 지나 2번이 나와야 한다")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("다른 강의의 대기자는 뽑지 않는다")
        void scopedToKlass() {
            assertThat(adapter.findNextWaitingWithLock(otherKlass.getId(), 0)).isEmpty();
        }
    }

    @Nested
    @DisplayName("순번 채번")
    class Position {

        @Test
        @DisplayName("대기가 없으면 0 — 다음 순번이 1 이 된다")
        void zeroWhenEmpty() {
            assertThat(adapter.maxPosition(klass.getId()))
                    .as("max() 가 null 을 돌려주는 것을 어댑터가 0 으로 바꿔야 한다")
                    .isZero();
        }

        @Test
        @DisplayName("가장 큰 순번을 준다 — 상태를 가리지 않는다")
        void returnsMaxAcrossStatuses() {
            enqueue(klass, "first", 1);
            Waitlist second = enqueue(klass, "second", 2);
            second.cancel();
            em.flush();
            em.clear();

            assertThat(adapter.maxPosition(klass.getId()))
                    .as("취소된 순번도 gap 으로 남으므로 재사용하지 않는다")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("강의별로 독립이다")
        void scopedToKlass() {
            enqueue(klass, "first", 1);
            em.flush();
            em.clear();

            assertThat(adapter.maxPosition(otherKlass.getId())).isZero();
        }
    }

    @Nested
    @DisplayName("활성 대기 판정과 일괄 조회")
    class WaitingQueries {

        @Test
        @DisplayName("WAITING 만 활성이다 — 포기하면 재대기할 수 있다")
        void onlyWaitingIsActive() {
            Waitlist waitlist = enqueue(klass, "waiter", 1);
            Long userId = waitlist.getUser().getId();
            em.flush();
            em.clear();

            assertThat(adapter.existsWaiting(klass.getId(), userId)).isTrue();

            em.find(Waitlist.class, waitlist.getId()).cancel();
            em.flush();
            em.clear();

            assertThat(adapter.existsWaiting(klass.getId(), userId))
                    .as("waiting_user_key 가 NULL 이 되는 것과 같은 판정이어야 한다")
                    .isFalse();
        }

        @Test
        @DisplayName("findAllWaiting 은 WAITING 만, 순번 순으로 준다")
        void listsWaitingInOrder() {
            enqueue(klass, "first", 1);
            Waitlist second = enqueue(klass, "second", 2);
            enqueue(klass, "third", 3);
            second.promote(NOW.plusHours(1));
            em.flush();
            em.clear();

            List<Waitlist> waiting = adapter.findAllWaiting(klass.getId());

            assertThat(waiting)
                    .as("강의 마감 시 정리 대상은 WAITING 뿐이다. PROMOTED 는 이미 좌석을 받았다")
                    .extracting(Waitlist::getPosition)
                    .containsExactly(1, 3);
        }

        @Test
        @DisplayName("잔여 대기가 없으면 빈 목록이다")
        void emptyWhenNoWaiting() {
            assertThat(adapter.findAllWaiting(klass.getId())).isEmpty();
        }
    }

    @Nested
    @DisplayName("내 대기 목록")
    class UserPage {

        @Test
        @DisplayName("본인 것만, 강의를 fetch join 해서, id 내림차순으로 준다")
        void returnsOwnWithKlass() {
            Waitlist mine = enqueue(klass, "me", 1);
            Long userId = mine.getUser().getId();
            // 같은 사용자가 다른 강의에도 대기 — 활성 중복 제약은 강의별이라 허용된다
            em.persist(Waitlist.enqueue(em.find(Klass.class, otherKlass.getId()),
                    em.find(User.class, userId), 1, NOW));
            enqueue(klass, "stranger", 2);
            em.flush();
            em.clear();

            CursorPageResult<Waitlist> page = adapter.findUserPage(
                    userId, new WaitlistQuery(null, 20));

            assertThat(page.items()).hasSize(2);
            assertThat(page.items())
                    .extracting(Waitlist::getId)
                    .isSortedAccordingTo(java.util.Comparator.reverseOrder());
            assertThat(page.items())
                    .allSatisfy(w -> assertThat(Hibernate.isInitialized(w.getKlass()))
                            .as("응답에 klassTitle 이 들어간다").isTrue());
        }

        @Test
        @DisplayName("승격·포기한 기록도 보인다 — 내 이력이다")
        void includesTerminalStates() {
            Waitlist mine = enqueue(klass, "me", 1);
            Long userId = mine.getUser().getId();
            mine.cancel();
            em.flush();
            em.clear();

            CursorPageResult<Waitlist> page = adapter.findUserPage(
                    userId, new WaitlistQuery(null, 20));

            assertThat(page.items()).hasSize(1);
        }

        @Test
        @DisplayName("커서가 다음 페이지를 이어붙인다")
        void paginatesWithCursor() {
            Waitlist mine = enqueue(klass, "me", 1);
            Long userId = mine.getUser().getId();
            em.persist(Waitlist.enqueue(em.find(Klass.class, otherKlass.getId()),
                    em.find(User.class, userId), 1, NOW));
            em.flush();
            em.clear();

            CursorPageResult<Waitlist> first = adapter.findUserPage(
                    userId, new WaitlistQuery(null, 1));

            assertThat(first.hasNext()).isTrue();

            Optional<Waitlist> second = adapter.findUserPage(
                    userId, new WaitlistQuery(first.nextCursor(), 1))
                    .items().stream().findFirst();

            assertThat(second).isPresent();
            assertThat(second.get().getId()).isLessThan(first.nextCursor());
        }
    }
}
