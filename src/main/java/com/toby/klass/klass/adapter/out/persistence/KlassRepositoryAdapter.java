package com.toby.klass.klass.adapter.out.persistence;

import com.toby.klass.common.application.dto.CursorPageResult;
import com.toby.klass.klass.application.dto.KlassQuery;
import com.toby.klass.klass.application.port.out.KlassCommandPort;
import com.toby.klass.klass.application.port.out.KlassQueryPort;
import com.toby.klass.klass.domain.Klass;
import com.toby.klass.klass.domain.KlassStatus;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Repository;

/**
 * 강의 포트 구현.
 *
 * <p>두 리포지토리를 조립한다 — 단건·락은 {@link KlassJpaRepository}, 목록의 동적 조건은
 * {@link KlassQueryDslRepository}. 서비스는 이 둘의 존재를 모른 채 포트만 본다.
 *
 * <p>Design Ref: §2.1 컴포넌트 구조, §9.1 계층 배치
 */
@Repository
public class KlassRepositoryAdapter implements KlassCommandPort, KlassQueryPort {

    private final KlassJpaRepository jpaRepository;
    private final KlassQueryDslRepository queryDslRepository;

    public KlassRepositoryAdapter(KlassJpaRepository jpaRepository,
                                  KlassQueryDslRepository queryDslRepository) {
        this.jpaRepository = jpaRepository;
        this.queryDslRepository = queryDslRepository;
    }

    @Override
    public Klass save(Klass klass) {
        return jpaRepository.save(klass);
    }

    @Override
    public Optional<Klass> findById(Long klassId) {
        return jpaRepository.findWithCreatorById(klassId);
    }

    @Override
    public CursorPageResult<Klass> findPublicPage(KlassQuery query) {
        return page(null, publicStatuses(query.status()), query);
    }

    @Override
    public CursorPageResult<Klass> findCreatorPage(Long creatorId, KlassQuery query) {
        // 개설자 본인의 목록이므로 상태를 가리지 않는다. 지정하면 그것만 본다
        Set<KlassStatus> statuses =
                query.status() == null ? Set.of() : Set.of(query.status());
        return page(creatorId, statuses, query);
    }

    /**
     * 공개 목록이 볼 상태. 미지정이면 공개 상태 전체, 지정되면 그것 하나다.
     *
     * <p>{@code DRAFT} 가 지정되는 경우는 걸러내지 않고 그대로 넘긴다 — {@link #page} 의
     * 가드가 빈 페이지로 처리한다. 여기서 조용히 공개 상태로 바꿔치기하면 <b>요청과 다른
     * 결과</b>가 나가 클라이언트가 필터가 먹었다고 오해한다.
     */
    private static Set<KlassStatus> publicStatuses(KlassStatus requested) {
        return requested == null ? KlassQueryDslRepository.PUBLIC_STATUSES : Set.of(requested);
    }

    private CursorPageResult<Klass> page(Long creatorId, Set<KlassStatus> statuses,
                                         KlassQuery query) {
        // 공개 목록에서 DRAFT 를 요청하면 쿼리를 내지 않고 빈 페이지를 돌려준다.
        // 조건식으로 녹일 수도 있지만, "공개 목록에 DRAFT 는 없다"를 코드에 드러내는 편이 낫다
        if (creatorId == null && statuses.contains(KlassStatus.DRAFT)) {
            return new CursorPageResult<>(List.of(), false, null);
        }
        List<Klass> fetched = queryDslRepository.findSlice(
                creatorId, statuses, query.cursor(), query.fetchLimit());
        return CursorPageResult.of(fetched, query.size(), Klass::getId);
    }
}
