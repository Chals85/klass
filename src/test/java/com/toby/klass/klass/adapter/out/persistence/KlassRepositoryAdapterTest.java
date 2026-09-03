package com.toby.klass.klass.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.toby.klass.infrastructure.config.QueryDslConfig;
import com.toby.klass.common.application.dto.CursorPageResult;
import com.toby.klass.klass.application.dto.KlassQuery;
import com.toby.klass.klass.domain.Klass;
import com.toby.klass.klass.domain.KlassStatus;
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
 * 강의 영속 어댑터 검증 (L2).
 *
 * <h2>{@code @Import} 가 필요한 이유</h2>
 * {@code @DataJpaTest} 는 JPA 관련 빈만 올린다. {@code JPAQueryFactory}({@link QueryDslConfig})와
 * {@code @Repository} 인 QueryDSL 리포지토리·어댑터는 그 범위 밖이라 직접 넣어야 한다.
 *
 * <h2>쿼리 카운트를 켜는 방법</h2>
 * {@code generate_statistics} 를 테스트 속성으로 준다. 전역 {@code application-test.yml} 을
 * 만들지 않은 이유는 <b>이 파일에서만 필요한 계측</b>이고, 전역 설정은 다른 테스트의 로그와
 * 성능에까지 영향을 주기 때문이다.
 *
 * <p>Design Ref: §2.4 락 조회, §3.5 가시성, §4.3 커서, §8.3 L2 어댑터
 */
@DataJpaTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Import({QueryDslConfig.class, KlassQueryDslRepository.class, KlassRepositoryAdapter.class})
class KlassRepositoryAdapterTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 1, 10, 0);
    private static final LocalDate STARTS_ON = LocalDate.of(2026, 10, 1);
    private static final LocalDate ENDS_ON = LocalDate.of(2026, 12, 31);

    @Autowired
    private EntityManager em;

    @Autowired
    private KlassRepositoryAdapter adapter;

    /** 조인 없는 단건 조회가 필요한 테스트용. 어댑터의 findById 는 개설자를 함께 읽는다. */
    @Autowired
    private KlassJpaRepository jpaRepository;

    private User creator;
    private User otherCreator;

    /** id 오름차순으로 만든 강의들. 커서는 내림차순이므로 뒤쪽부터 조회된다. */
    private List<Klass> openKlasses;

    @BeforeEach
    void setUp() {
        creator = persistUser("creator");
        otherCreator = persistUser("other");

        // creator: OPEN 5 + CLOSED 1 + DRAFT 2,  otherCreator: OPEN 1
        openKlasses = List.of(
                persistKlass(creator, "공개 1", KlassStatus.OPEN),
                persistKlass(creator, "공개 2", KlassStatus.OPEN),
                persistKlass(creator, "공개 3", KlassStatus.OPEN),
                persistKlass(creator, "공개 4", KlassStatus.OPEN),
                persistKlass(creator, "공개 5", KlassStatus.OPEN));
        persistKlass(creator, "마감된 강의", KlassStatus.CLOSED);
        persistKlass(creator, "초안 1", KlassStatus.DRAFT);
        persistKlass(creator, "초안 2", KlassStatus.DRAFT);
        persistKlass(otherCreator, "남의 공개 강의", KlassStatus.OPEN);

        em.flush();
        em.clear();
    }

    private User persistUser(String username) {
        User user = User.register(username, "hashed", Set.of(Role.ROLE_CREATOR), NOW);
        em.persist(user);
        return user;
    }

    private Klass persistKlass(User owner, String title, KlassStatus status) {
        Klass klass = Klass.open(owner, title, title + " 내용", new BigDecimal("10000"),
                10, STARTS_ON, ENDS_ON, 7, NOW);
        if (status == KlassStatus.OPEN) {
            klass.publish(NOW);
        } else if (status == KlassStatus.CLOSED) {
            klass.close(NOW);
        }
        em.persist(klass);
        return klass;
    }

    private static KlassQuery query(Long cursor, int size) {
        return new KlassQuery(cursor, size, null);
    }

    private Statistics statistics() {
        return em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
    }

    private static List<String> titlesOf(CursorPageResult<Klass> page) {
        return page.items().stream().map(Klass::getTitle).toList();
    }

    @Nested
    @DisplayName("공개 목록")
    class PublicPage {

        @Test
        @DisplayName("첫 페이지는 id 내림차순이고 DRAFT 는 한 건도 없다")
        void firstPageExcludesDraft() {
            CursorPageResult<Klass> page = adapter.findPublicPage(query(null, 20));

            // 전체 9건 중 DRAFT 2건을 뺀 7건
            assertThat(page.items()).hasSize(7);
            assertThat(page.items()).extracting(Klass::getStatus)
                    .doesNotContain(KlassStatus.DRAFT);
            assertThat(page.items()).extracting(Klass::getId).isSortedAccordingTo(
                    java.util.Comparator.reverseOrder());
            assertThat(page.hasNext()).isFalse();
            assertThat(page.nextCursor()).isNull();
        }

        @Test
        @DisplayName("size 만큼만 담고 다음 커서를 알려준다 — 초과 조회분은 응답에 실리지 않는다")
        void firstPageWithNext() {
            CursorPageResult<Klass> page = adapter.findPublicPage(query(null, 3));

            assertThat(page.items()).hasSize(3);
            assertThat(page.hasNext()).isTrue();
            assertThat(page.nextCursor())
                    .isEqualTo(page.items().get(2).getId());
        }

        @Test
        @DisplayName("커서를 이어받으면 중복도 누락도 없이 이어진다")
        void cursorPaginationHasNoOverlapOrGap() {
            List<String> collected = new java.util.ArrayList<>();
            Long cursor = null;
            for (int guard = 0; guard < 10; guard++) {
                CursorPageResult<Klass> page = adapter.findPublicPage(query(cursor, 3));
                collected.addAll(titlesOf(page));
                if (!page.hasNext()) {
                    break;
                }
                cursor = page.nextCursor();
            }

            // 7건이 정확히 한 번씩 — 경계에서 1건이 겹치거나 빠지면 여기서 걸린다
            assertThat(collected).hasSize(7).doesNotHaveDuplicates();
            assertThat(collected).contains("남의 공개 강의", "마감된 강의", "공개 1", "공개 5");
        }

        @Test
        @DisplayName("마지막 페이지는 hasNext=false, nextCursor=null 이다")
        void lastPage() {
            Long cursor = openKlasses.get(1).getId();   // "공개 2" 미만 → "공개 1" 한 건

            CursorPageResult<Klass> page = adapter.findPublicPage(query(cursor, 3));

            assertThat(titlesOf(page)).containsExactly("공개 1");
            assertThat(page.hasNext()).isFalse();
            assertThat(page.nextCursor()).isNull();
        }

        @Test
        @DisplayName("status=OPEN 필터는 CLOSED 를 제외한다")
        void statusFilter() {
            CursorPageResult<Klass> page =
                    adapter.findPublicPage(new KlassQuery(null, 20, KlassStatus.OPEN));

            assertThat(page.items()).hasSize(6);
            assertThat(page.items()).extracting(Klass::getStatus)
                    .containsOnly(KlassStatus.OPEN);
        }

        @Test
        @DisplayName("status=DRAFT 로는 남의 초안을 볼 수 없다 — 빈 페이지다")
        void draftFilterYieldsNothing() {
            CursorPageResult<Klass> page =
                    adapter.findPublicPage(new KlassQuery(null, 20, KlassStatus.DRAFT));

            assertThat(page.items()).isEmpty();
            assertThat(page.hasNext()).isFalse();
        }
    }

    @Nested
    @DisplayName("내 강의 목록")
    class CreatorPage {

        @Test
        @DisplayName("본인 것만 나오고 DRAFT 도 포함된다")
        void includesOwnDraftsOnly() {
            CursorPageResult<Klass> page = adapter.findCreatorPage(creator.getId(), query(null, 20));

            // creator 의 8건 (OPEN 5 + CLOSED 1 + DRAFT 2). 남의 것은 없다
            assertThat(page.items()).hasSize(8);
            assertThat(page.items()).extracting(Klass::getStatus)
                    .contains(KlassStatus.DRAFT);
            assertThat(titlesOf(page)).doesNotContain("남의 공개 강의");
        }

        @Test
        @DisplayName("다른 개설자의 목록에는 내 강의가 섞이지 않는다")
        void isolatedPerCreator() {
            CursorPageResult<Klass> page =
                    adapter.findCreatorPage(otherCreator.getId(), query(null, 20));

            assertThat(titlesOf(page)).containsExactly("남의 공개 강의");
        }

        @Test
        @DisplayName("상태 필터를 주면 그것만 본다 — 개설자는 자기 DRAFT 도 필터할 수 있다")
        void statusFilterIncludingDraft() {
            CursorPageResult<Klass> page = adapter.findCreatorPage(
                    creator.getId(), new KlassQuery(null, 20, KlassStatus.DRAFT));

            assertThat(titlesOf(page)).containsExactlyInAnyOrder("초안 1", "초안 2");
        }
    }

    /**
     * N+1 방지 검증.
     *
     * <h2>절대 쿼리 수가 아니라 <b>증가분</b>을 재는 이유</h2>
     * 처음에는 "목록 조회 후 SQL 총 1회"로 단언했는데 실제로는 2가 나왔다. 조사해 보니
     * 실행된 JPQL 은 <b>정확히 하나</b>였고({@code stats.getQueries()} 로 확인), 나머지는
     * Hibernate 가 한 질의를 처리하며 만드는 내부 statement 였다. 즉 그 절대값은 우리가
     * 제어하는 값이 아니라 <b>구현 세부에 묶인 숫자</b>다 — 버전이 바뀌면 테스트가 깨진다.
     *
     * <p>정작 N+1 이 드러나는 지점은 따로 있다. {@code Klass.creator} 는 {@code LAZY}
     * 프록시라 <b>{@code getUsername()} 을 호출하는 순간</b> 쿼리가 나간다. 따라서
     * <b>개설자를 읽기 직전에 카운터를 0으로 놓고, 읽은 뒤 증가분이 0인지</b> 보면 된다.
     * fetch join 이 빠지면 7건에서 정확히 7이 늘어난다.
     *
     * <p>조회만 하고 끝내는 테스트가 무의미한 것도 같은 이유다 — 프록시를 건드리지 않으면
     * fetch join 이 있든 없든 통과한다 (Design §8.3 #7).
     */
    @Nested
    @DisplayName("N+1 방지")
    class FetchJoin {

        @Test
        @DisplayName("목록 7건의 개설자명을 모두 읽어도 추가 쿼리가 없다")
        void listLoadsCreatorWithoutExtraQuery() {
            CursorPageResult<Klass> page = adapter.findPublicPage(query(null, 20));
            assertThat(page.items()).hasSize(7);

            Statistics stats = statistics();
            stats.clear();
            page.items().forEach(k -> k.getCreator().getUsername());

            assertThat(stats.getPrepareStatementCount())
                    .as("fetch join 이 빠지면 항목 수만큼(7) 늘어난다")
                    .isZero();
        }

        @Test
        @DisplayName("목록의 개설자는 프록시가 아니라 이미 초기화된 상태다")
        void creatorIsInitialized() {
            CursorPageResult<Klass> page = adapter.findPublicPage(query(null, 20));

            // allSatisfy 는 빈 컬렉션에서 무조건 통과한다 — 크기 가드가 없으면
            // 쿼리가 아무것도 못 찾아도 초록불이 된다
            assertThat(page.items()).hasSize(7)
                    .allSatisfy(k -> assertThat(Hibernate.isInitialized(k.getCreator())).isTrue());
        }

        @Test
        @DisplayName("목록 조회는 JPQL 을 한 번만 실행한다 — 페이지 크기와 무관하다")
        void listExecutesSingleQuery() {
            Statistics stats = statistics();
            stats.clear();

            adapter.findPublicPage(query(null, 20));

            assertThat(stats.getQueries())
                    .as("실행된 JPQL/HQL 목록")
                    .hasSize(1);
        }

        @Test
        @DisplayName("상세 조회도 개설자명 접근에 추가 쿼리가 없다")
        void detailLoadsCreatorWithoutExtraQuery() {
            Long id = openKlasses.get(0).getId();
            Klass found = adapter.findById(id).orElseThrow();

            Statistics stats = statistics();
            stats.clear();
            found.getCreator().getUsername();

            assertThat(stats.getPrepareStatementCount()).isZero();
        }
    }

    @Nested
    @DisplayName("단건 조회")
    class SingleFetch {

        @Test
        @DisplayName("없는 id 는 빈 Optional 이다 — 예외가 아니다")
        void missingIdReturnsEmpty() {
            assertThat(adapter.findById(999_999L)).isEmpty();
        }

        /**
         * {@code isOwnedBy} 가 프록시를 초기화하지 않는다는 성질을 <b>조인 없는 경로에서</b>
         * 검증한다.
         *
         * <h2>어댑터의 {@code findById} 로는 이 검증이 성립하지 않는다</h2>
         * 그쪽은 {@code @EntityGraph} 로 개설자를 함께 읽으므로 {@code creator} 가 이미
         * 초기화된 채 온다. {@code isOwnedBy} 가 {@code getId()} 만 만지든 전체를 초기화하든
         * 추가 쿼리는 0이라, <b>구현을 어떻게 바꿔도 실패시킬 수 없는 테스트</b>가 된다.
         *
         * <p>그래서 {@code JpaRepository.findById}(조인 없음)로 읽어 <b>프록시 상태에서</b>
         * 소유권을 검사한다. <b>이 성질이 수강신청 사이클에서 실제로 값을 한다</b> — 락 조회
         * ({@code findWithLockById})는 조인 없이 읽어야 하고(ERD §4.1 락 대상 단일화,
         * Design D-21), 그 경로에서 소유권 검사가 프록시를 깨우면 락 트랜잭션마다
         * 쿼리가 하나씩 늘어난다. 신청·취소가 {@code isOwnedBy} 를 부르므로 그 비용이
         * 요청마다 발생하게 된다.
         */
        @Test
        @DisplayName("소유권 검사는 프록시를 깨우지 않는다 — 조인 없이 읽어도 추가 쿼리가 없다")
        void ownershipCheckDoesNotTriggerQuery() {
            Long id = openKlasses.get(0).getId();
            em.clear();
            // 조인 없는 경로. creator 는 프록시로 온다
            Klass found = jpaRepository.findById(id).orElseThrow();
            assertThat(Hibernate.isInitialized(found.getCreator()))
                    .as("이 전제가 깨지면 아래 단언이 무의미해진다")
                    .isFalse();

            Statistics stats = statistics();
            stats.clear();
            boolean owned = found.isOwnedBy(creator.getId());

            assertThat(owned).isTrue();
            assertThat(stats.getPrepareStatementCount())
                    .as("getId() 만 건드리므로 프록시가 초기화되지 않는다")
                    .isZero();
            assertThat(Hibernate.isInitialized(found.getCreator())).isFalse();
        }
    }

    /**
     * 배타 락 조회 — klass-management 에서 걷어냈던 것을 수강신청 사이클이 되살렸다 (D-21).
     *
     * <p>당시 걷어낸 이유는 <b>직렬화할 상대가 없었기</b> 때문이다. 그 락이 막으려던 것은
     * 수강신청 트랜잭션(ERD 정본 §4.2)인데 그것이 존재하지 않았다. 이제 존재한다.
     *
     * <p>Design Ref: enrollment-management §4.2, §4.1.1, D-21
     */
    @Nested
    @DisplayName("배타 락 조회 (D-21 복원)")
    class LockFetch {

        @Test
        @DisplayName("락 조회가 강의를 읽는다 — 이름이 파생 쿼리로 해석된다")
        void readsKlass() {
            Long id = openKlasses.get(0).getId();
            em.clear();

            assertThat(adapter.findWithLockById(id))
                    .as("findWithLockById 는 find~By 사이가 무시돼 findById 와 같은 쿼리가 된다. "
                            + "findByIdForUpdate 였다면 부트스트랩에서 이미 깨졌다")
                    .isPresent();
        }

        @Test
        @DisplayName("락 조회는 개설자를 조인하지 않는다 — 락 대상은 klass 단일 행이다")
        void doesNotJoinCreator() {
            Long id = openKlasses.get(0).getId();
            em.clear();

            Klass found = adapter.findWithLockById(id).orElseThrow();

            assertThat(Hibernate.isInitialized(found.getCreator()))
                    .as("조인하면 users 행까지 잠겨 ERD 정본 §4.1 의 규약이 깨진다")
                    .isFalse();
        }

        @Test
        @DisplayName("조회용 findById 는 여전히 개설자를 함께 읽는다 — 두 경로가 갈려 있다")
        void plainFetchStillJoins() {
            Long id = openKlasses.get(0).getId();
            em.clear();

            Klass found = adapter.findById(id).orElseThrow();

            assertThat(Hibernate.isInitialized(found.getCreator()))
                    .as("락 도입이 조회 경로를 바꾸면 안 된다")
                    .isTrue();
        }

        @Test
        @DisplayName("없는 id 는 빈 Optional 이다")
        void missingIdYieldsEmpty() {
            assertThat(adapter.findWithLockById(999_999L)).isEmpty();
        }

        @Test
        @DisplayName("락으로 읽은 엔티티도 변경 감지가 동작한다 — 카운터 증감이 반영된다")
        void dirtyCheckingWorks() {
            Long id = openKlasses.get(0).getId();
            em.clear();

            adapter.findWithLockById(id).orElseThrow().occupySeat();
            em.flush();
            em.clear();

            assertThat(jpaRepository.findById(id).orElseThrow().getEnrollmentCount())
                    .as("락 조회가 읽기 전용으로 동작하면 신청이 카운터를 못 올린다")
                    .isEqualTo(1);
        }
    }

}
