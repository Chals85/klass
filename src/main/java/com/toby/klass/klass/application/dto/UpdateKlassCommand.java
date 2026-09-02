package com.toby.klass.klass.application.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 강의 수정 명령.
 *
 * <h2>전 필드가 필수다 — 수정은 전체 교체다 (D-25)</h2>
 * 클라이언트의 수정 화면은 강의의 전체 값을 들고 있고, 변경되지 않은 필드도 현재 값을 그대로
 * 실어 보낸다. 따라서 이 명령에 도달한 값은 <b>전부 "이 값으로 만들라"는 지시</b>이며
 * "비어 있음 = 안 바꿈"을 표현할 수단이 필요하지 않다. {@code Optional} 로 감싸던
 * 초안을 걷어낸 이유가 이것이다 — 그 체계는 클라이언트의 입력 오류를 "안 바꿈"으로
 * 읽어 <b>조용히 무시</b>한다. 누락·{@code null}·공백은 {@code adapter.in} 의 검증이
 * 400 으로 거부한다.
 *
 * <p><b>"바꿀 것이 없는 요청"은 성립하지 않는다.</b> 그래서 그것을 판별하는 메서드도 없다.
 * 값이 현재와 동일하더라도 매 요청이 수정이며 {@code updatedAt} 이 갱신된다
 * ({@code KlassService.update} 참조).
 *
 * <p>{@code cancellationPeriodDays} 만 {@code null} 을 허용한다 — 등록과 같이 선택 필드이고,
 * {@code null} 은 "전역 기본값을 따른다"는 뜻이다 (Design §10).
 *
 * <h2>수강 기간을 두 필드로 받는 이유</h2>
 * {@code ends_on >= starts_on} 은 두 값이 함께 있어야 판정할 수 있어 도메인은
 * {@code changePeriod(startsOn, endsOn, now)} 로 <b>쌍</b>을 받는다. 여기서는 두 날짜를
 * 따로 싣고 서비스가 그대로 쌍으로 넘긴다 — <b>둘 다 항상 오므로 조립할 것이 없다.</b>
 * {@code KlassPeriod} 같은 값 타입을 만들지 않는 것은 그것이 도메인 시그니처를 한 번 더
 * 감싸기만 하고, 이 명령의 다른 필드들과 표현 방식이 어긋나기 때문이다 (D-22 · D-25).
 *
 * <p>Design Ref: §4.3 PATCH /v1/klasses/{id}, §12 D-25
 *
 * @param requesterId 요청자 id. 소유권 검사의 기준
 */
public record UpdateKlassCommand(
        Long klassId,
        Long requesterId,
        String title,
        String description,
        BigDecimal price,
        Integer capacity,
        LocalDate startsOn,
        LocalDate endsOn,
        Integer cancellationPeriodDays) {
}
