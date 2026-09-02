package com.toby.klass.klass.adapter.in.web.dto;

import com.toby.klass.klass.application.dto.UpdateKlassCommand;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 강의 수정 요청.
 *
 * <h2>수정은 전체 교체다 (D-25)</h2>
 * 클라이언트의 수정 화면은 강의의 <b>전체 값을 이미 들고 있다</b> — 상세 조회로 받아 폼에
 * 채워 놓은 상태다. 그래서 저장할 때 <b>변경되지 않은 필드도 현재 값을 그대로 실어 보낸다.</b>
 * "바뀐 것만 골라 보낸다"는 전제는 이 저장소의 클라이언트 계약이 아니다.
 *
 * <h2>그래서 누락·{@code null}·공백은 입력 오류다</h2>
 * 전체를 실어 보내는 것이 정상이므로, 필드가 빠졌거나 {@code null}·공백으로 왔다는 것은
 * <b>"안 바꿈"이 아니라 클라이언트가 실제로 그렇게 입력했다는 뜻</b>이다. 그것은 400 으로
 * 거부해야 한다. 부분 수정 규격은 같은 입력을 "바꾸지 않는다"로 읽어 <b>입력 오류를 조용히
 * 무시</b>하게 되므로 채택하지 않는다.
 *
 * <p>따라서 검증 기준은 {@link RegisterKlassRequest} 와 <b>같다</b> — 같은 값 집합을 같은
 * 필수 조건으로 받기 때문이다. 두 요청의 애노테이션과 메시지가 어긋나면 같은 입력이
 * 등록에서는 통과하고 수정에서는 거부되는(또는 그 반대) 자리가 생긴다.
 *
 * <p>{@code cancellationPeriodDays} 만 등록과 같이 선택이다({@code @Min(0)} 뿐).
 * 등록에서 선택 필드이므로 기준을 그대로 따른다 — 생략하거나 {@code null} 로 보내면
 * <b>전역 기본값으로 되돌아간다.</b> 전체 교체 시맨틱의 자연스러운 결과다.
 *
 * <p><b>HTTP 메서드는 {@code PATCH} 를 유지한다.</b> 시맨틱상 {@code PUT} 이 맞지만
 * {@code SecurityConfig} 매처 · openapi 오퍼레이션 키 · RestDocs 스니펫 이름까지 번져
 * 위험 대비 이득이 없다. 메서드가 {@code PATCH} 라는 사실이 부분 수정을 뜻하지 않는다.
 *
 * <p><b>{@code endsOn >= startsOn} 을 여기서 검증하지 않는다.</b> 클래스 레벨 커스텀
 * 검증기를 만들면 규칙이 {@code adapter.in} 에 놓이고, 같은 규칙을 등록 API 도 지켜야 하므로
 * 두 곳에 흩어진다. <b>규칙은 도메인에 하나만 둔다</b> ({@code Klass.changePeriod}) —
 * {@code RegisterKlassRequest} 가 같은 근거로 같은 선택을 한다.
 *
 * <p>상태는 받지 않는다 — 상태 전이는 {@code PATCH /v1/klasses/{id}/status} 소관이다.
 * {@code enrollmentCount} 도 서버가 관리하는 값이라 받지 않는다.
 *
 * <p>Design Ref: §4.3 PATCH /v1/klasses/{id}, §12 D-25
 */
public record UpdateKlassRequest(

        @NotBlank(message = "제목은 필수입니다")
        @Size(max = 200, message = "제목은 200자를 넘을 수 없습니다")
        String title,

        /** 필수값이다 (Design D-18). ERD 원안은 nullable 이었다. */
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

    /**
     * 명령으로 옮긴다. 값을 감싸지 않고 그대로 넘긴다 — 전 필드가 필수이므로
     * "비어 있음 = 안 바꿈"을 표현할 수단이 필요하지 않다.
     */
    public UpdateKlassCommand toCommand(Long klassId, Long requesterId) {
        return new UpdateKlassCommand(klassId, requesterId, title, description, price,
                capacity, startsOn, endsOn, cancellationPeriodDays);
    }
}
