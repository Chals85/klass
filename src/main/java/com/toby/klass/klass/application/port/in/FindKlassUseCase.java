package com.toby.klass.klass.application.port.in;

import com.toby.klass.klass.application.dto.KlassResult;

/**
 * 강의 상세 조회.
 *
 * <p>Design Ref: §4.1 GET /v1/klasses/{id}
 */
public interface FindKlassUseCase {

    /**
     * 강의 하나를 조회한다.
     *
     * <p><b>{@code viewerId} 가 {@code null} 일 수 있다.</b> 이 엔드포인트는 선택적 인증이라
     * 비로그인 요청이 그대로 들어온다 (Design §4.2).
     *
     * @param viewerId 조회자 id. 비로그인이면 {@code null}
     * @throws com.toby.klass.common.domain.error.BusinessException 강의가 없거나
     *         조회자에게 보이지 않는 경우 — <b>둘 다 404 다</b>. 타인의 초안은 존재 자체를
     *         드러내지 않는다 (Design §6.2)
     */
    KlassResult findById(Long klassId, Long viewerId);
}
