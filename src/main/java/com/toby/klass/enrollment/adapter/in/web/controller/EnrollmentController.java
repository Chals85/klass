package com.toby.klass.enrollment.adapter.in.web.controller;

import com.toby.klass.common.adapter.in.web.dto.ApiResponse;
import com.toby.klass.common.adapter.in.web.dto.CursorPageResponse;
import com.toby.klass.enrollment.adapter.in.web.dto.EnrollmentResponse;
import com.toby.klass.enrollment.adapter.in.web.dto.EnrollmentSummaryResponse;
import com.toby.klass.enrollment.adapter.in.web.dto.KlassEnrollmentResponse;
import com.toby.klass.enrollment.application.dto.ApplyEnrollmentCommand;
import com.toby.klass.enrollment.application.dto.CancelEnrollmentCommand;
import com.toby.klass.enrollment.application.dto.ConfirmEnrollmentCommand;
import com.toby.klass.enrollment.application.dto.EnrollmentQuery;
import com.toby.klass.enrollment.application.port.in.ApplyEnrollmentUseCase;
import com.toby.klass.enrollment.application.port.in.CancelEnrollmentUseCase;
import com.toby.klass.enrollment.application.port.in.ConfirmEnrollmentUseCase;
import com.toby.klass.enrollment.application.port.in.FindEnrollmentUseCase;
import com.toby.klass.enrollment.application.port.in.ListEnrollmentUseCase;
import com.toby.klass.enrollment.domain.EnrollmentStatus;
import com.toby.klass.infrastructure.security.principal.AuthenticatedUser;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 수강 신청 API.
 *
 * <h2>클래스 레벨 {@code @RequestMapping} 을 두지 않는다</h2>
 * 경로가 두 뿌리에 걸쳐 있다 — 신청·명단은 강의의 하위 자원({@code /v1/klasses/{klassId}/…}),
 * 확정·취소·조회는 신청 자체({@code /v1/enrollments/…}). 공통 접두어를 {@code /v1} 로 잡으면
 * 메서드마다 나머지를 다시 써야 해서 읽기가 나빠진다.
 *
 * <h2>전이를 {@code PATCH /status} 하나로 합치지 않은 이유</h2>
 * 강의는 {@code PATCH /v1/klasses/{id}/status} 하나로 {@code publish}/{@code close} 를
 * 받는다. 그쪽은 전이별 검증이 "화이트리스트 조회" 한 가지로 균질했다. 신청은 다르다.
 *
 * <table border="1">
 *   <caption>확정과 취소의 차이</caption>
 *   <tr><th></th><th>confirm</th><th>cancel</th></tr>
 *   <tr><td>검증</td><td>만료 시각</td><td>취소 기간 + 강의 종료일</td></tr>
 *   <tr><td>락 범위</td><td>{@code enrollment} 단독</td>
 *       <td>{@code klass}+{@code enrollment}+{@code waitlist}</td></tr>
 *   <tr><td>부수 효과</td><td>없음</td><td>카운터 감소 + 승격</td></tr>
 * </table>
 *
 * <p>한 엔드포인트에 담으면 이 분기가 컨트롤러로 새어나온다 (Design D-35).
 *
 * <h2>{@code @Validated} 를 <b>붙이지 않는다</b> — 붙이면 500 이 된다</h2>
 * Spring 6.1 부터 {@code @RequestParam} 의 제약 애노테이션은 내장 메서드 검증이 처리하고
 * {@code HandlerMethodValidationException} 을 던진다. 클래스에 {@code @Validated} 를 붙이면
 * AOP 검증이 대신 동작해 {@code ConstraintViolationException} 을 던지는데, 그 예외는 Advice 에
 * 핸들러가 없어 500 이 된다. {@code KlassController} 에서 실제로 겪고 걷어낸 함정이다.
 *
 * <p><b>사용자 id 는 항상 {@code principal} 에서 온다.</b> 경로나 본문에서 받으면 남의
 * 이름으로 신청·취소할 수 있다 (ERD 정본 §7).
 *
 * <p>Design Ref: enrollment-management §6.1 · §6.2
 */
@RestController
public class EnrollmentController {

    private final ApplyEnrollmentUseCase applyEnrollmentUseCase;
    private final ConfirmEnrollmentUseCase confirmEnrollmentUseCase;
    private final CancelEnrollmentUseCase cancelEnrollmentUseCase;
    private final FindEnrollmentUseCase findEnrollmentUseCase;
    private final ListEnrollmentUseCase listEnrollmentUseCase;

    public EnrollmentController(ApplyEnrollmentUseCase applyEnrollmentUseCase,
                                ConfirmEnrollmentUseCase confirmEnrollmentUseCase,
                                CancelEnrollmentUseCase cancelEnrollmentUseCase,
                                FindEnrollmentUseCase findEnrollmentUseCase,
                                ListEnrollmentUseCase listEnrollmentUseCase) {
        this.applyEnrollmentUseCase = applyEnrollmentUseCase;
        this.confirmEnrollmentUseCase = confirmEnrollmentUseCase;
        this.cancelEnrollmentUseCase = cancelEnrollmentUseCase;
        this.findEnrollmentUseCase = findEnrollmentUseCase;
        this.listEnrollmentUseCase = listEnrollmentUseCase;
    }

    /**
     * 강의에 신청한다. 요청 본문이 없다 — 필요한 것은 경로의 강의와 토큰의 사용자뿐이다.
     *
     * <p>정원이 찼으면 409 이고 <b>대기열로 자동 분기하지 않는다.</b> 사용자가 원하면
     * {@code POST /v1/klasses/{klassId}/waitlists} 를 별도로 호출한다.
     *
     * @return 201 과 {@code PENDING} 상태의 신청
     */
    @PostMapping("/v1/klasses/{klassId}/enrollments")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<EnrollmentResponse> apply(
            @PathVariable Long klassId,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        return ApiResponse.ok(EnrollmentResponse.from(applyEnrollmentUseCase.apply(
                new ApplyEnrollmentCommand(klassId, principal.id()))));
    }

    /**
     * 강의별 수강생 목록. <b>크리에이터 전용이다.</b>
     *
     * <p>{@code SecurityConfig} 가 {@code hasRole("CREATOR")} 로 막고 <b>소유권은 서비스가
     * 따로 검사한다</b> — 권한만 보면 크리에이터끼리 서로의 명단을 볼 수 있다.
     *
     * @param status 상태 필터. 미지정이면 취소분까지 전부 — 크리에이터가 이탈을 확인할 수
     *               있어야 하므로 기본에서 감추지 않는다
     * @return 200 과 커서 페이지
     */
    @GetMapping("/v1/klasses/{klassId}/enrollments")
    public ApiResponse<CursorPageResponse<KlassEnrollmentResponse>> listByKlass(
            @PathVariable Long klassId,
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "조회 개수는 1 이상이어야 합니다")
            @Max(value = EnrollmentQuery.MAX_SIZE, message = "조회 개수는 100 이하여야 합니다")
            int size,
            @RequestParam(required = false) EnrollmentStatus status,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        return ApiResponse.ok(CursorPageResponse.from(
                listEnrollmentUseCase.listByKlass(klassId, principal.id(),
                        new EnrollmentQuery(cursor, size, status)),
                KlassEnrollmentResponse::from));
    }

    /**
     * 내 신청 목록.
     *
     * <p><b>이 경로는 {@code /{id}} 보다 먼저 매칭돼야 한다.</b> Spring MVC 가 리터럴 경로를
     * 변수 경로보다 우선하므로 자동으로 해결되고, {@code {id}} 를 {@code Long} 으로 받아
     * 두 번째 방어선을 둔다 — {@code /v1/klasses/me} 와 같은 구조다.
     *
     * @return 200 과 커서 페이지
     */
    @GetMapping("/v1/enrollments/me")
    public ApiResponse<CursorPageResponse<EnrollmentSummaryResponse>> listMine(
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "조회 개수는 1 이상이어야 합니다")
            @Max(value = EnrollmentQuery.MAX_SIZE, message = "조회 개수는 100 이하여야 합니다")
            int size,
            @RequestParam(required = false) EnrollmentStatus status,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        return ApiResponse.ok(CursorPageResponse.from(
                listEnrollmentUseCase.listMine(principal.id(),
                        new EnrollmentQuery(cursor, size, status)),
                EnrollmentSummaryResponse::from));
    }

    /**
     * 신청 상세. 본인 것만 볼 수 있다.
     *
     * <p>타인의 것이면 <b>403</b> 이다. 강의 상세가 타인의 초안을 404 로 감추는 것과 다른데,
     * 근거가 다르다 — 초안은 존재 자체가 비밀이지만 신청 id 는 연속된 정수라 감춰봐야
     * 존재가 추측되고 애초에 비밀이 아니다.
     *
     * @return 200 과 신청 상세
     */
    @GetMapping("/v1/enrollments/{id}")
    public ApiResponse<EnrollmentResponse> detail(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        return ApiResponse.ok(EnrollmentResponse.from(
                findEnrollmentUseCase.findById(id, principal.id())));
    }

    /**
     * 결제 완료를 처리한다. {@code PENDING → CONFIRMED}.
     *
     * <p>결제 게이트웨이 연동은 이 프로젝트의 범위가 아니다 — 외부에서 결제가 끝났다는
     * 신호로 상태만 전이한다.
     *
     * <p>좌석 점유 수는 변하지 않는다. {@code PENDING} 이 이미 점유하고 있었기 때문이며,
     * 그래서 이 요청만 강의 락을 잡지 않는다.
     *
     * @return 200 과 확정된 신청
     */
    @PostMapping("/v1/enrollments/{id}/confirm")
    public ApiResponse<EnrollmentResponse> confirm(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        return ApiResponse.ok(EnrollmentResponse.from(confirmEnrollmentUseCase.confirm(
                new ConfirmEnrollmentCommand(id, principal.id()))));
    }

    /**
     * 수강을 취소한다. 좌석이 반납되고, 강의가 모집 중이며 대기자가 있으면 <b>같은
     * 트랜잭션에서 1건이 승격</b>된다.
     *
     * <p>{@code CONFIRMED} 는 두 관문을 통과해야 한다 — 결제일 기준 취소 가능 기간 안이고
     * <b>강의 종료일이 지나지 않았어야</b> 한다. {@code PENDING} 은 둘 다 면제된다.
     *
     * @return 200 과 취소된 신청
     */
    @PostMapping("/v1/enrollments/{id}/cancel")
    public ApiResponse<EnrollmentResponse> cancel(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        return ApiResponse.ok(EnrollmentResponse.from(cancelEnrollmentUseCase.cancel(
                new CancelEnrollmentCommand(id, principal.id()))));
    }
}
