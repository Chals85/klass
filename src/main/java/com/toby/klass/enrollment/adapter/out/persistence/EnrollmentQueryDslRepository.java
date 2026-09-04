package com.toby.klass.enrollment.adapter.out.persistence;

import static com.toby.klass.enrollment.domain.QEnrollment.enrollment;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.toby.klass.enrollment.domain.Enrollment;
import com.toby.klass.enrollment.domain.EnrollmentStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * 신청 목록의 동적 조건 조회.
 *
 * <h2>fetch join 대상이 목록마다 다르다</h2>
 * 두 목록은 조건만 다른 것이 아니라 <b>필요한 연관이 반대</b>다.
 *
 * <table border="1">
 *   <caption>목록별 fetch join</caption>
 *   <tr><th>목록</th><th>조인</th><th>이유</th></tr>
 *   <tr><td>내 신청</td><td>{@code klass}</td>
 *       <td>{@code klassTitle} 과 {@code isCancellable}({@code ends_on}·기간 필요)</td></tr>
 *   <tr><td>강의별 수강생</td><td>{@code user}</td>
 *       <td>{@code username}. 강의는 경로에 이미 있어 필요 없다</td></tr>
 * </table>
 *
 * <p>둘 다 조인하면 필요 없는 쪽까지 끌고 온다. 하나도 안 하면 20건 페이지에서 21번 쿼리가
 * 나가고, {@code open-in-view: false} 라 컨트롤러 직렬화 시점에
 * {@code LazyInitializationException} 으로 <b>즉시 실패</b>한다 — 조용히 느려지는 것보다는
 * 낫지만 어차피 고쳐야 한다.
 *
 * <p>{@code @ManyToOne} 단일 연관이라 {@code limit} 과 함께 써도 페이징이 메모리로 내려가지
 * 않는다. 컬렉션 조인이었다면 그렇지 않다.
 *
 * <p>Design Ref: enrollment-management §6.3, R-07
 */
@Repository
public class EnrollmentQueryDslRepository {

    private final JPAQueryFactory queryFactory;

    public EnrollmentQueryDslRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    /**
     * 내 신청 목록. 강의를 함께 읽는다.
     *
     * <p>정렬은 {@code id DESC} 고정이다. {@code idx_enrollment_user(user_id, id DESC)} 가
     * 이 순서로 저장돼 있어 정렬 작업이 발생하지 않는다 (ERD 정본 §3.6).
     *
     * @param status 상태 필터. {@code null} 이면 취소분까지 전부 — 내 기록이므로 가리지 않는다
     * @param limit  호출자가 {@code size + 1} 을 넘긴다
     */
    public List<Enrollment> findUserSlice(Long userId, EnrollmentStatus status,
                                          Long cursor, int limit) {
        return queryFactory
                .selectFrom(enrollment)
                .join(enrollment.klass).fetchJoin()
                .where(new BooleanBuilder()
                        .and(enrollment.user.id.eq(userId))
                        .and(statusEq(status))
                        .and(cursorLt(cursor)))
                .orderBy(enrollment.id.desc())
                .limit(limit)
                .fetch();
    }

    /**
     * 강의별 수강생 목록. 수강생을 함께 읽는다.
     *
     * <p>{@code idx_enrollment_klass_status(klass_id, status, id DESC)} 가 이 조회를 받는다.
     *
     * @param status 상태 필터. {@code null} 이면 취소분까지 전부 — 크리에이터가 이탈을
     *               확인할 수 있어야 하므로 기본에서 감추지 않는다
     */
    public List<Enrollment> findKlassSlice(Long klassId, EnrollmentStatus status,
                                           Long cursor, int limit) {
        return queryFactory
                .selectFrom(enrollment)
                .join(enrollment.user).fetchJoin()
                .where(new BooleanBuilder()
                        .and(enrollment.klass.id.eq(klassId))
                        .and(statusEq(status))
                        .and(cursorLt(cursor)))
                .orderBy(enrollment.id.desc())
                .limit(limit)
                .fetch();
    }

    // ── 조건 조각 ────────────────────────────────────────────────────────────
    // null 을 돌려주면 BooleanBuilder 가 그 조건을 무시한다. if 분기를 늘리지 않고
    // 조합을 표현하는 QueryDSL 의 관용적 방식이다.

    private static BooleanExpression statusEq(EnrollmentStatus status) {
        return status == null ? null : enrollment.status.eq(status);
    }

    /** {@code id DESC} 정렬이므로 다음 페이지는 커서보다 <b>작은</b> id 다. */
    private static BooleanExpression cursorLt(Long cursor) {
        return cursor == null ? null : enrollment.id.lt(cursor);
    }

    /**
     * 만료 회수 후보의 id 를 오래된 순서로 읽는다. <b>락을 잡지 않는다.</b>
     *
     * <p>{@code status} 조건은 논리적으로 중복이다 — {@code ck_enrollment_pending} 때문에
     * {@code expires_at} 이 있는 행은 전부 {@code PENDING} 이다. 그래도 <b>남긴다</b>:
     * 쿼리만 읽고도 의도를 알 수 있고, 제약이 언젠가 바뀌어도 이 쿼리는 여전히 옳다.
     *
     * <p>정렬이 {@code expires_at ASC} 인 이유는 <b>오래 묶인 좌석을 먼저 푸는 것</b>이
     * 공정하기 때문이다. 상한에 걸려 잘리더라도 가장 오래된 것부터 처리된다.
     *
     * <p>Design Ref: pending-expiry-reaper §5.4
     */
    public List<Long> findExpiredIds(LocalDateTime now, int limit) {
        return queryFactory
                .select(enrollment.id)
                .from(enrollment)
                .where(enrollment.status.eq(EnrollmentStatus.PENDING),
                        enrollment.expiresAt.loe(now))
                .orderBy(enrollment.expiresAt.asc())
                .limit(limit)
                .fetch();
    }

}
