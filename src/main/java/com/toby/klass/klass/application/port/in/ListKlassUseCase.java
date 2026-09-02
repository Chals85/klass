package com.toby.klass.klass.application.port.in;

import com.toby.klass.common.application.dto.CursorPageResult;
import com.toby.klass.klass.application.dto.KlassQuery;
import com.toby.klass.klass.application.dto.KlassSummaryResult;

/**
 * 강의 목록 조회.
 *
 * <h2>목록이 둘인 이유</h2>
 * 공개 목록은 <b>남에게 보이는 그대로</b>여야 크리에이터가 자기 강의의 노출 상태를 확인할 수
 * 있다. 초안이 섞이면 크리에이터가 보는 화면과 사용자가 보는 화면이 달라진다. 초안은
 * {@code /me} 가 담당한다 (Design D-14).
 *
 * <p>Design Ref: §4.1 GET /v1/klasses · GET /v1/klasses/me
 */
public interface ListKlassUseCase {

    /**
     * 공개된 강의 목록. {@code DRAFT} 는 <b>누구에게도</b> 보이지 않는다 — 개설자 본인에게도.
     */
    CursorPageResult<KlassSummaryResult> listPublic(KlassQuery query);

    /**
     * 내가 개설한 강의 목록. {@code DRAFT} 를 포함한 전부를 돌려준다.
     *
     * @param creatorId 개설자 id. 이 엔드포인트는 인증이 필수라 {@code null} 이 아니다
     */
    CursorPageResult<KlassSummaryResult> listByCreator(Long creatorId, KlassQuery query);
}
