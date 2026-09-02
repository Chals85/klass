package com.toby.klass.klass.adapter.in.web.dto;

import com.toby.klass.klass.application.dto.RegisterKlassCommand;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 강의 등록 요청.
 *
 * <p><b>{@code endsOn >= startsOn} 을 여기서 검증하지 않는다.</b> 클래스 레벨 커스텀
 * 검증기를 만들면 규칙이 {@code adapter.in} 에 놓이는데, 같은 규칙을 수정 API 도 지켜야 하므로
 * 두 요청 DTO 에 같은 검증기가 흩어진다. <b>규칙은 도메인에 하나만 둔다</b>
 * ({@code Klass.changePeriod}).
 *
 * <p>상태는 받지 않는다 — 새 강의는 항상 {@code DRAFT} 로 시작한다.
 *
 * <p>Design Ref: §4.3 POST /v1/klasses
 *
 * @param title                  강의 제목. 최대 200자
 * @param description            강의 내용. <b>필수값</b>이다 (D-18) — ERD 원안은 nullable 이었다
 * @param price                  수강료. 0 이상이며 소수점 이하 2자리까지
 * @param capacity               최대 정원. 1 이상
 * @param startsOn               수강 시작일
 * @param endsOn                 수강 종료일. 시작일 이후여야 하며 그 판정은 도메인이 한다
 * @param cancellationPeriodDays 취소 가능 기간(일). 0 이상이며 생략하면 전역 기본값을 따른다
 */
public record RegisterKlassRequest(

        @NotBlank(message = "제목은 필수입니다")
        @Size(max = 200, message = "제목은 200자를 넘을 수 없습니다")
        String title,

        @NotBlank(message = "내용은 필수입니다")
        String description,

        @NotNull(message = "수강료는 필수입니다")
        @DecimalMin(value = "0", message = "수강료는 0 이상이어야 합니다")
        @Digits(integer = 10, fraction = 2, message = "수강료 형식이 올바르지 않습니다")
        BigDecimal price,

        @NotNull(message = "정원은 필수입니다")
        @Min(value = 1, message = "정원은 1명 이상이어야 합니다")
        Integer capacity,

        @NotNull(message = "수강 시작일은 필수입니다")
        LocalDate startsOn,

        @NotNull(message = "수강 종료일은 필수입니다")
        LocalDate endsOn,

        @Min(value = 0, message = "취소 가능 기간은 0일 이상이어야 합니다")
        Integer cancellationPeriodDays) {

    public RegisterKlassCommand toCommand(Long creatorId) {
        return new RegisterKlassCommand(creatorId, title, description, price, capacity,
                startsOn, endsOn, cancellationPeriodDays);
    }
}
