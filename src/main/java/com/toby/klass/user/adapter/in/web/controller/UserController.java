package com.toby.klass.user.adapter.in.web.controller;

import com.toby.klass.common.adapter.in.web.dto.ApiResponse;
import com.toby.klass.infrastructure.security.principal.AuthenticatedUser;
import com.toby.klass.user.adapter.in.web.dto.UserResponse;
import com.toby.klass.user.application.port.in.FindUserUseCase;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 사용자 조회 API. 인증이 필요하다.
 *
 * <p><b>Swagger 어노테이션을 붙이지 않는다.</b> 근거는 {@code AuthController} 참조 —
 * 엔드포인트 설명은 {@code UserControllerTest} 의 스니펫 정의가 단일 출처다.
 *
 * <p>Design Ref: §4.2 GET /v1/users/me
 */
@RestController
@RequestMapping("/v1/users")
public class UserController {

    private final FindUserUseCase findUserUseCase;

    /**
     * 사용자 조회 유즈케이스를 주입받는다.
     *
     * @param findUserUseCase 사용자 조회 유즈케이스
     */
    public UserController(FindUserUseCase findUserUseCase) {
        this.findUserUseCase = findUserUseCase;
    }

    /**
     * 현재 인증된 사용자를 조회한다.
     *
     * <p>principal 에는 토큰에서 복원한 값이 들어 있지만 그대로 응답하지 않는다.
     * {@code isEnabled}·{@code createdAt} 은 토큰에 없고, 권한 변경도 즉시 반영돼야 하므로
     * id 로 DB 를 다시 읽는다.
     *
     * @param principal 인증된 사용자. 필터가 SecurityContext 에 넣은 값이다
     * @return 200 과 사용자 정보
     */
    @GetMapping("/me")
    public ApiResponse<UserResponse> me(@AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.ok(UserResponse.from(findUserUseCase.findById(principal.id())));
    }
}
