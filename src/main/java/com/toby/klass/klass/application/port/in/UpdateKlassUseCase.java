package com.toby.klass.klass.application.port.in;

import com.toby.klass.klass.application.dto.KlassResult;
import com.toby.klass.klass.application.dto.UpdateKlassCommand;

/**
 * 강의 내용 수정 (<b>전체 교체</b>).
 *
 * <p>Design Ref: klass-management §4.1 PUT /v1/klasses/{id}, §12 D-25
 */
public interface UpdateKlassUseCase {

    /**
     * 수정 대상 필드 전부를 명령의 값으로 교체한다 (Design D-25).
     *
     * <p>HTTP 메서드는 {@code PUT} 이며 <b>부분 수정이 아니다</b> — 클라이언트가 강의의
     * 전체 값을 실어 보내므로 누락·{@code null}·공백은 "안 바꿈"이 아니라 입력 오류이며
     * {@code adapter.in} 의 검증이 400 으로 거부한다.
     *
     * @throws com.toby.klass.common.domain.error.BusinessException 강의가 없거나(404),
     *         본인 강의가 아니거나(403), 정원·기간이 규칙을 어긴 경우
     */
    KlassResult update(UpdateKlassCommand command);
}
