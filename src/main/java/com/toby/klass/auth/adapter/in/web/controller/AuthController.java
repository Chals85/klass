package com.toby.klass.auth.adapter.in.web.controller;

import com.toby.klass.auth.adapter.in.web.dto.LoginRequest;
import com.toby.klass.auth.adapter.in.web.dto.LogoutRequest;
import com.toby.klass.auth.adapter.in.web.dto.ReissueRequest;
import com.toby.klass.auth.adapter.in.web.dto.TokenResponse;
import com.toby.klass.auth.application.port.in.LoginUseCase;
import com.toby.klass.auth.application.port.in.LogoutUseCase;
import com.toby.klass.auth.application.port.in.ReissueTokenUseCase;
import com.toby.klass.common.adapter.in.web.dto.ApiResponse;
import com.toby.klass.infrastructure.security.principal.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인증 API.
 *
 * <p>구현체가 아니라 <b>유즈케이스 인터페이스</b>를 주입받는다. 컨트롤러는
 * {@code AuthService} 의 존재를 모르며, 이것이 인바운드 어댑터의 방향성이다.
 *
 * <p><b>Swagger 어노테이션을 붙이지 않는다.</b> API 문서는 RestDocs 테스트가 만드는
 * {@code openapi3.json} 이 정본이다. 컨트롤러에 {@code @Operation} 을 중복으로 두면
 * 설명이 두 곳에 흩어져 서로 어긋나고, 어노테이션 쪽은 실제 동작을 보증하지도 않는다.
 * 엔드포인트 설명은 {@code AuthControllerTest} 의 스니펫 정의에 있다.
 *
 * <p>Design Ref: §4.2 API Specification, §2.3 Dependencies
 */
@RestController
@RequestMapping("/v1/auth")
public class AuthController {

    private final LoginUseCase loginUseCase;
    private final ReissueTokenUseCase reissueTokenUseCase;
    private final LogoutUseCase logoutUseCase;

    /**
     * 인증 유즈케이스들을 주입받는다.
     *
     * @param loginUseCase 로그인
     * @param reissueTokenUseCase 토큰 재발급
     * @param logoutUseCase 로그아웃
     */
    public AuthController(LoginUseCase loginUseCase,
                          ReissueTokenUseCase reissueTokenUseCase,
                          LogoutUseCase logoutUseCase) {
        this.loginUseCase = loginUseCase;
        this.reissueTokenUseCase = reissueTokenUseCase;
        this.logoutUseCase = logoutUseCase;
    }

    /**
     * 로그인해서 토큰 쌍을 발급받는다.
     *
     * @param request 아이디·비밀번호
     * @return 200 과 Access/Refresh 토큰
     */
    @PostMapping("/login")
    public ApiResponse<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(TokenResponse.from(loginUseCase.login(request.toCommand())));
    }

    /**
     * Refresh 토큰을 회전시켜 새 토큰 쌍을 받는다.
     *
     * <p>기존 Refresh 토큰은 폐기되므로 응답으로 받은 새 토큰을 저장해야 한다.
     * 옛 토큰을 다시 쓰면 탈취로 간주되어 전체 세션이 끊긴다.
     *
     * @param request Refresh 토큰
     * @return 200 과 새 Access/Refresh 토큰
     */
    @PostMapping("/reissue")
    public ApiResponse<TokenResponse> reissue(@Valid @RequestBody ReissueRequest request) {
        return ApiResponse.ok(TokenResponse.from(reissueTokenUseCase.reissue(request.toCommand())));
    }

    /**
     * 로그아웃한다. 멱등하다.
     *
     * <p>Refresh 토큰을 지워 갱신을 막고, 요청에 쓰인 <b>Access 토큰도 즉시 폐기</b>한다.
     * 따라서 이 응답을 받은 뒤 같은 Access 토큰으로 보호된 API 를 부르면
     * {@code 401 TOKEN_REVOKED} 다.
     *
     * <p>사용자 id 와 Access 토큰 정보를 모두 principal 에서 꺼내는 것이 중요하다.
     * 클라이언트가 보낸 값을 믿으면 남의 토큰을 지울 수 있다.
     *
     * @param request   폐기할 Refresh 토큰
     * @param principal 인증된 사용자. 현재 Access 토큰의 {@code jti} 와 만료 시각을 함께 들고 있다
     * @return 204 (본문 없음)
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody LogoutRequest request,
                                       @AuthenticationPrincipal AuthenticatedUser principal) {
        logoutUseCase.logout(request.toCommand(
                principal.id(), principal.jti(), principal.tokenExpiresAt()));
        return ResponseEntity.noContent().build();
    }
}
