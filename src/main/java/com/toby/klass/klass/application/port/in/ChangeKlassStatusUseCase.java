package com.toby.klass.klass.application.port.in;

import com.toby.klass.klass.application.dto.ChangeKlassStatusCommand;
import com.toby.klass.klass.application.dto.KlassResult;

/**
 * 강의 상태 변경.
 *
 * <p>허용 전이는 3종뿐이다 — {@code DRAFT → OPEN}, {@code DRAFT → CLOSED},
 * {@code OPEN → CLOSED}. 판단은 도메인이 한다 (Design §3.3).
 *
 * <p>Design Ref: §4.1 PATCH /v1/klasses/{id}/status
 */
public interface ChangeKlassStatusUseCase {

    /**
     * 강의 상태를 바꾼다.
     *
     * @throws com.toby.klass.common.domain.error.BusinessException 강의가 없거나(404),
     *         본인 강의가 아니거나(403), 허용되지 않는 전이인 경우(409)
     */
    KlassResult changeStatus(ChangeKlassStatusCommand command);
}
