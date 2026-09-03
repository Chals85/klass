package com.toby.klass.waitlist.adapter.out.persistence;

import static com.toby.klass.waitlist.domain.QWaitlist.waitlist;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.toby.klass.waitlist.domain.Waitlist;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * 내 대기 목록 조회.
 *
 * <h2>파생 쿼리로도 되는데 QueryDSL 을 쓰는 이유</h2>
 * 커서 조건({@code cursor == null} 이면 조건 없음)이 동적이다. 파생 쿼리로 하려면
 * {@code findByUserIdOrderByIdDesc} 와 {@code findByUserIdAndIdLessThanOrderByIdDesc} 두
 * 메서드를 두고 호출부가 분기해야 하는데, 그 분기가 어댑터로 새어나온다.
 *
 * <p>fetch join 도 필요하다 — 응답에 {@code klassTitle} 이 들어가는데
 * {@code Waitlist.klass} 가 {@code LAZY} 라 그대로 두면 N+1 이 나고,
 * {@code open-in-view: false} 라 컨트롤러에서 터진다.
 *
 * <p>Design Ref: enrollment-management §6.3, R-07
 */
@Repository
public class WaitlistQueryDslRepository {

    private final JPAQueryFactory queryFactory;

    public WaitlistQueryDslRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    /**
     * 내 대기 목록. 강의를 함께 읽는다.
     *
     * <p>상태를 가리지 않는다 — 승격됐거나 포기한 기록도 내 이력이다. 정렬은 순번이 아니라
     * {@code id DESC} 다. 여러 강의의 대기가 섞이므로 순번은 비교 대상이 아니다.
     *
     * @param limit 호출자가 {@code size + 1} 을 넘긴다
     */
    public List<Waitlist> findUserSlice(Long userId, Long cursor, int limit) {
        return queryFactory
                .selectFrom(waitlist)
                .join(waitlist.klass).fetchJoin()
                .where(new BooleanBuilder()
                        .and(waitlist.user.id.eq(userId))
                        .and(cursorLt(cursor)))
                .orderBy(waitlist.id.desc())
                .limit(limit)
                .fetch();
    }

    /** {@code id DESC} 정렬이므로 다음 페이지는 커서보다 <b>작은</b> id 다. */
    private static BooleanExpression cursorLt(Long cursor) {
        return cursor == null ? null : waitlist.id.lt(cursor);
    }
}
