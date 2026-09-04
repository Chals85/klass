package com.toby.klass.waitlist.adapter.in.web.controller;

import com.toby.klass.common.adapter.in.web.dto.ApiResponse;
import com.toby.klass.common.adapter.in.web.dto.CursorPageResponse;
import com.toby.klass.enrollment.application.dto.GiveUpWaitlistCommand;
import com.toby.klass.enrollment.application.dto.RegisterWaitlistCommand;
import com.toby.klass.enrollment.application.port.in.GiveUpWaitlistUseCase;
import com.toby.klass.enrollment.application.port.in.ListWaitlistUseCase;
import com.toby.klass.enrollment.application.port.in.RegisterWaitlistUseCase;
import com.toby.klass.infrastructure.security.principal.AuthenticatedUser;
import com.toby.klass.waitlist.adapter.in.web.dto.WaitlistResponse;
import com.toby.klass.waitlist.application.dto.WaitlistQuery;
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
 * 대기열 API.
 *
 * <h2>{@code waitlist} 패키지에 있는데 {@code EnrollmentService} 를 부른다</h2>
 * 어색해 보이지만 {@code adapter.in → port.in} 이라 계층 규칙 안이다. 좌석을 건드리는
 * 유스케이스가 전부 한 서비스에 모여 있고(D-29), URL 이 {@code /v1/waitlists/**} 인 이상
 * 컨트롤러가 이 패키지에 있는 편이 찾기 쉽다.
 *
 * <h2>경로가 복수형인 이유</h2>
 * {@code /v1/klasses}·{@code /v1/enrollments} 와 맞춘다. 생성되는 자원은 "대기열"이 아니라
 * <b>대기 등록 항목</b>이므로 복수형이 어색하지 않다 (Design D-36).
 *
 * <p>{@code @Validated} 를 붙이지 않는 근거는 {@code EnrollmentController} 참조.
 *
 * <p>Design Ref: enrollment-management §6.1, D-29 · D-36
 */
@RestController
public class WaitlistController {

    private final RegisterWaitlistUseCase registerWaitlistUseCase;
    private final GiveUpWaitlistUseCase giveUpWaitlistUseCase;
    private final ListWaitlistUseCase listWaitlistUseCase;

    public WaitlistController(RegisterWaitlistUseCase registerWaitlistUseCase,
                              GiveUpWaitlistUseCase giveUpWaitlistUseCase,
                              ListWaitlistUseCase listWaitlistUseCase) {
        this.registerWaitlistUseCase = registerWaitlistUseCase;
        this.giveUpWaitlistUseCase = giveUpWaitlistUseCase;
        this.listWaitlistUseCase = listWaitlistUseCase;
    }

    /**
     * 대기열에 등록한다.
     *
     * <p><b>자리가 남아 있으면 409 다.</b> 승격은 좌석 반납 경로에서만 트리거되므로, 빈자리가
     * 있는 강의의 대기자는 누군가 취소할 때까지 영구히 기다리게 된다 — 신청으로 안내한다.
     *
     * <p>응답의 {@code id} 가 <b>대기 포기 API 의 경로 변수</b>다. 놓치면
     * {@code GET /v1/waitlists/me} 로 다시 찾아야 한다.
     *
     * @return 201 과 {@code WAITING} 상태의 대기
     */
    @PostMapping("/v1/klasses/{klassId}/waitlists")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<WaitlistResponse> register(
            @PathVariable Long klassId,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        return ApiResponse.ok(WaitlistResponse.from(registerWaitlistUseCase.register(
                new RegisterWaitlistCommand(klassId, principal.id()))));
    }

    /**
     * 내 대기 목록. 승격·포기한 기록도 포함한다 — 내 이력이다.
     *
     * <p>상태 필터를 받지 않는다. 대기는 상태가 셋뿐이고 목록이 짧아 걸러낼 이유가 적다.
     *
     * @return 200 과 커서 페이지
     */
    @GetMapping("/v1/waitlists/me")
    public ApiResponse<CursorPageResponse<WaitlistResponse>> listMine(
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "조회 개수는 1 이상이어야 합니다")
            @Max(value = WaitlistQuery.MAX_SIZE, message = "조회 개수는 100 이하여야 합니다")
            int size,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        return ApiResponse.ok(CursorPageResponse.from(
                listWaitlistUseCase.listMineWaitlist(principal.id(),
                        new WaitlistQuery(cursor, size)),
                WaitlistResponse::from));
    }

    /**
     * 대기를 포기한다.
     *
     * <p>이미 승격됐으면 <b>409</b> 다. 승격이 먼저 커밋된 상황에서 포기시켜 버리면 배정된
     * 좌석이 주인 없이 남는다 — 사용자는 "이미 자리가 배정되었다"를 안내받아야 한다.
     *
     * @return 200 과 포기된 대기
     */
    @PostMapping("/v1/waitlists/{id}/cancel")
    public ApiResponse<WaitlistResponse> giveUp(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        return ApiResponse.ok(WaitlistResponse.from(giveUpWaitlistUseCase.giveUp(
                new GiveUpWaitlistCommand(id, principal.id()))));
    }
}
