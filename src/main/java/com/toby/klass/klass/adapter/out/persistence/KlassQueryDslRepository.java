package com.toby.klass.klass.adapter.out.persistence;

import static com.toby.klass.klass.domain.QKlass.klass;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.toby.klass.klass.domain.Klass;
import com.toby.klass.klass.domain.KlassStatus;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Repository;

/**
 * 강의 목록의 동적 조건 조회.
 *
 * <h2>Spring Data 커스텀 리포지토리({@code *Impl})를 쓰지 않는 이유</h2>
 * {@code KlassJpaRepositoryImpl} 이라는 <b>명명 규약</b>으로 구현을 찾아 붙이는 방식인데,
 * 그 규약은 또 하나의 "컴파일러가 잡지 못하는 문자열"이다. 이름을 잘못 쓰면 조용히 연결되지
 * 않는다. 어댑터가 두 빈을 주입받으면 끝나는 일에 마법을 들일 이유가 없다 (Design D-17).
 *
 * <h2>fetch join 을 붙이는 이유</h2>
 * {@code Klass.creator} 는 {@code LAZY} 이고 응답에 개설자명이 들어간다. 그대로 두면 20건
 * 조회에 21번 쿼리가 나간다(N+1). {@code @ManyToOne} 단일 연관이라 {@code limit} 과 함께
 * 써도 페이징이 메모리로 내려가지 않는다 — 컬렉션 조인이었다면 그렇지 않다.
 *
 * <p>Design Ref: §2.0 커서 조회, §3.5 가시성, §8.3 L2
 */
@Repository
public class KlassQueryDslRepository {

    /** 공개 목록에 보이는 상태. {@code DRAFT} 는 개설자에게만 보이므로 여기 없다 (Design §3.5). */
    public static final Set<KlassStatus> PUBLIC_STATUSES =
            Set.of(KlassStatus.OPEN, KlassStatus.CLOSED);

    private final JPAQueryFactory queryFactory;

    public KlassQueryDslRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    /**
     * 커서 기준으로 강의를 읽는다.
     *
     * <p>정렬은 {@code id DESC} 고정이다. {@code idx_klass_status(status, id DESC)} 와
     * {@code idx_klass_creator(creator_id, id DESC)} 가 이 순서로 저장돼 있어 정렬 작업이
     * 발생하지 않는다 (ERD 정본 §3.6).
     *
     * @param creatorId 개설자 필터. {@code null} 이면 전체 (공개 목록)
     * @param statuses  포함할 상태. 비어 있으면 상태를 가리지 않는다
     * @param cursor    직전 페이지 마지막 id. {@code null} 이면 첫 페이지
     * @param limit     가져올 개수. 다음 페이지 판정을 위해 호출자가 {@code size + 1} 을 넘긴다
     */
    public List<Klass> findSlice(Long creatorId, Set<KlassStatus> statuses, Long cursor, int limit) {
        BooleanBuilder where = new BooleanBuilder()
                .and(creatorEq(creatorId))
                .and(statusIn(statuses))
                .and(cursorLt(cursor));

        return queryFactory
                .selectFrom(klass)
                // 개설자를 함께 읽는다. 없으면 목록 렌더링에서 N+1 이 난다
                .join(klass.creator).fetchJoin()
                .where(where)
                .orderBy(klass.id.desc())
                .limit(limit)
                .fetch();
    }

    // ── 조건 조각 ────────────────────────────────────────────────────────────
    // null 을 돌려주면 BooleanBuilder 가 그 조건을 무시한다. if 분기를 늘리지 않고
    // 조합을 표현하는 QueryDSL 의 관용적 방식이다.

    private static BooleanExpression creatorEq(Long creatorId) {
        return creatorId == null ? null : klass.creator.id.eq(creatorId);
    }

    private static BooleanExpression statusIn(Set<KlassStatus> statuses) {
        return statuses == null || statuses.isEmpty() ? null : klass.status.in(statuses);
    }

    /**
     * 커서보다 <b>작은</b> id 를 읽는다 — 정렬이 내림차순이므로 "다음 페이지"가 곧 더 작은 id 다.
     *
     * <p>{@code <=} 가 아니라 {@code <} 인 것이 중요하다. 같으면 직전 페이지의 마지막 항목이
     * 다시 실려 <b>페이지 경계마다 1건씩 중복</b>된다.
     */
    private static BooleanExpression cursorLt(Long cursor) {
        return cursor == null ? null : klass.id.lt(cursor);
    }
}
