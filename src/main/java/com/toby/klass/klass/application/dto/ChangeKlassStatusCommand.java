package com.toby.klass.klass.application.dto;

import com.toby.klass.klass.domain.KlassStatus;

/**
 * 강의 상태 변경 명령.
 *
 * <p>서비스는 {@code status} 로 분기해 도메인 메서드를 고를 뿐, <b>전이 가부는 판단하지
 * 않는다</b> — 그것은 {@code Klass.publish()}/{@code close()} 의 몫이다 (Design §4.3).
 *
 * <p>Design Ref: §4.3 PATCH /v1/klasses/{id}/status
 *
 * @param requesterId 요청자 id. 소유권 검사의 기준
 * @param status      목표 상태. {@code DRAFT} 는 되돌아갈 메서드가 없어 항상 거부된다
 */
public record ChangeKlassStatusCommand(Long klassId, Long requesterId, KlassStatus status) {
}
