package com.toby.klass.klass.application.port.in;

import com.toby.klass.klass.application.dto.KlassResult;
import com.toby.klass.klass.application.dto.RegisterKlassCommand;

/**
 * 강의 등록.
 *
 * <p>Design Ref: §4.1 POST /v1/klasses
 */
public interface RegisterKlassUseCase {

    /**
     * 새 강의를 개설한다. 상태는 항상 {@code DRAFT} 로 시작한다.
     *
     * @throws com.toby.klass.common.domain.error.BusinessException 개설자를 찾을 수 없거나
     *         정원·수강 기간이 규칙을 어긴 경우
     */
    KlassResult register(RegisterKlassCommand command);
}
