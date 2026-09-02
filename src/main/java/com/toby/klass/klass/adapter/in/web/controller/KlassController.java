package com.toby.klass.klass.adapter.in.web.controller;

import com.toby.klass.common.adapter.in.web.dto.ApiResponse;
import com.toby.klass.common.adapter.in.web.dto.CursorPageResponse;
import com.toby.klass.infrastructure.security.principal.AuthenticatedUser;
import com.toby.klass.klass.adapter.in.web.dto.ChangeKlassStatusRequest;
import com.toby.klass.klass.adapter.in.web.dto.KlassResponse;
import com.toby.klass.klass.adapter.in.web.dto.KlassSummaryResponse;
import com.toby.klass.klass.adapter.in.web.dto.RegisterKlassRequest;
import com.toby.klass.klass.adapter.in.web.dto.UpdateKlassRequest;
import com.toby.klass.klass.application.dto.KlassQuery;
import com.toby.klass.klass.application.port.in.ChangeKlassStatusUseCase;
import com.toby.klass.klass.application.port.in.FindKlassUseCase;
import com.toby.klass.klass.application.port.in.ListKlassUseCase;
import com.toby.klass.klass.application.port.in.RegisterKlassUseCase;
import com.toby.klass.klass.application.port.in.UpdateKlassUseCase;
import com.toby.klass.klass.domain.KlassStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 강의 관리 API.
 *
 * <h2>세 종류의 인증이 한 컨트롤러에 있다</h2>
 * <ul>
 *   <li><b>크리에이터 전용</b> — 등록·수정·상태 변경·내 강의 목록.
 *       {@code SecurityConfig} 가 {@code hasRole("CREATOR")} 로 막고,
 *       <b>소유권은 서비스가 따로 검사한다</b>. 권한만 보면 크리에이터끼리 서로의 강의를
 *       수정할 수 있다</li>
 *   <li><b>선택적 인증</b> — 상세·공개 목록. 토큰이 없어도 200 이되, 있으면 보이는 범위가
 *       늘어난다. <b>{@code principal} 이 {@code null} 일 수 있다</b></li>
 * </ul>
 *
 * <h2>{@code @Validated} 를 <b>붙이지 않는다</b> — 붙이면 500 이 된다</h2>
 * Spring 6.1 부터 {@code @RequestParam} 의 제약 애노테이션은 <b>내장 메서드 검증</b>이
 * 처리하고 {@code HandlerMethodValidationException} 을 던진다 — 우리 Advice 가 400 으로
 * 번역하는 그 예외다.
 *
 * <p>그런데 클래스에 {@code @Validated} 를 붙이면 <b>AOP 기반 검증이 대신 동작</b>해
 * {@code ConstraintViolationException} 을 던진다(이중 검증을 피하려 내장 쪽이 물러난다).
 * 그 예외는 Advice 에 핸들러가 없어 {@code handleUnexpected} 로 떨어져 <b>500</b> 이 된다.
 * 실제로 붙여 보고 500 을 확인한 뒤 걷어냈다.
 *
 * <p>같은 규칙을 {@code KlassQuery} 생성자도 검사한다. 중복이 아니라 <b>두 방어선</b>이다 —
 * 여기는 HTTP 계약(400 + 필드명), 그쪽은 포트를 직접 쓰는 다른 호출자까지 막는다.
 *
 * <p><b>Swagger 어노테이션을 붙이지 않는다.</b> 근거는 {@code AuthController} 참조 —
 * 엔드포인트 설명은 {@code KlassControllerTest} 의 스니펫 정의가 단일 출처다.
 *
 * <p>Design Ref: §4.1 엔드포인트 목록, §4.2 선택적 인증
 */
@RestController
@RequestMapping("/v1/klasses")
public class KlassController {

    private final RegisterKlassUseCase registerKlassUseCase;
    private final UpdateKlassUseCase updateKlassUseCase;
    private final ChangeKlassStatusUseCase changeKlassStatusUseCase;
    private final FindKlassUseCase findKlassUseCase;
    private final ListKlassUseCase listKlassUseCase;

    public KlassController(RegisterKlassUseCase registerKlassUseCase,
                           UpdateKlassUseCase updateKlassUseCase,
                           ChangeKlassStatusUseCase changeKlassStatusUseCase,
                           FindKlassUseCase findKlassUseCase,
                           ListKlassUseCase listKlassUseCase) {
        this.registerKlassUseCase = registerKlassUseCase;
        this.updateKlassUseCase = updateKlassUseCase;
        this.changeKlassStatusUseCase = changeKlassStatusUseCase;
        this.findKlassUseCase = findKlassUseCase;
        this.listKlassUseCase = listKlassUseCase;
    }

    /**
     * 강의를 등록한다. 상태는 항상 {@code DRAFT} 로 시작한다.
     *
     * @param principal 인증된 크리에이터. 이 경로는 {@code hasRole("CREATOR")} 뒤에 있어
     *                  {@code null} 이 될 수 없다
     * @return 201 과 생성된 강의
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<KlassResponse> register(
            @Valid @RequestBody RegisterKlassRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        return ApiResponse.ok(KlassResponse.from(
                registerKlassUseCase.register(request.toCommand(principal.id()))));
    }

    /**
     * 강의를 <b>전체 교체</b>한다 (Design D-25). 7필드를 모두 보내야 한다.
     *
     * <p>클라이언트 수정 화면이 강의의 전체 값을 들고 있어 변경되지 않은 필드도 그대로
     * 실어 보낸다. 따라서 누락·{@code null}·공백은 "안 바꿈"이 아니라 <b>입력 오류</b>이며
     * {@code @Valid} 가 400 으로 거부한다.
     *
     * <h2>{@code PATCH} 가 아니라 {@code PUT} 인 이유</h2>
     * {@code PATCH} 는 "일부만 고친다", {@code PUT} 은 "이 표현으로 갈아끼운다"는 뜻이다.
     * 전체 교체를 {@code PATCH} 로 노출하면 <b>메서드 이름과 동작이 어긋나</b> 클라이언트가
     * 일부만 보내도 되는 것으로 오해한다 — 그러면 400 을 받고 이유를 스펙에서 찾아야 한다.
     *
     * <p>반면 {@code PATCH /{id}/status} 는 상태 하나만 바꾸므로 그쪽은 {@code PATCH} 가 맞다.
     * 두 엔드포인트의 메서드가 다른 것이 <b>의도</b>다.
     *
     * @return 200 과 교체된 강의
     */
    @PutMapping("/{id}")
    public ApiResponse<KlassResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody UpdateKlassRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        return ApiResponse.ok(KlassResponse.from(
                updateKlassUseCase.update(request.toCommand(id, principal.id()))));
    }

    /**
     * 강의 상태를 바꾼다.
     *
     * <p>허용 전이는 3종뿐이다 — {@code DRAFT → OPEN}, {@code DRAFT → CLOSED}(개설 철회),
     * {@code OPEN → CLOSED}. 판단은 도메인이 하므로 여기서는 값을 넘기기만 한다.
     *
     * @return 200 과 상태가 바뀐 강의
     */
    @PatchMapping("/{id}/status")
    public ApiResponse<KlassResponse> changeStatus(
            @PathVariable Long id,
            @Valid @RequestBody ChangeKlassStatusRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        return ApiResponse.ok(KlassResponse.from(
                changeKlassStatusUseCase.changeStatus(request.toCommand(id, principal.id()))));
    }

    /**
     * 강의 상세를 조회한다. <b>인증이 선택적이다.</b>
     *
     * <p>토큰이 없으면 공개된 강의만 보이고, 개설자의 토큰이 있으면 자기 초안도 보인다.
     * 보이지 않는 강의는 <b>404</b> 다 — 403 은 "그 강의는 존재한다"를 알려주는데 초안은
     * 존재 자체가 비밀이다.
     *
     * @param principal 인증된 사용자. <b>비로그인 요청이면 {@code null} 이다.</b>
     *                  기존 {@code UserController.me()} 처럼 바로 역참조하면 NPE 가 난다
     * @return 200 과 강의 상세
     */
    @GetMapping("/{id}")
    public ApiResponse<KlassResponse> detail(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        return ApiResponse.ok(KlassResponse.from(
                findKlassUseCase.findById(id, viewerIdOf(principal))));
    }

    /**
     * 공개된 강의 목록. <b>인증이 선택적이다.</b>
     *
     * <p>{@code DRAFT} 는 <b>누구에게도</b> 보이지 않는다 — 개설자 본인에게도. 초안은
     * {@code GET /v1/klasses/me} 가 담당한다. 그래야 크리에이터가 <b>자기 강의가 남에게
     * 어떻게 보이는지</b> 이 화면에서 확인할 수 있다 (Design D-14).
     *
     * @param cursor 직전 페이지 마지막 항목의 id. 없으면 첫 페이지
     * @param size   가져올 개수. 1~100, 기본 20
     * @param status 상태 필터. 미지정이면 {@code OPEN}·{@code CLOSED} 둘 다
     * @return 200 과 커서 페이지
     */
    @GetMapping
    public ApiResponse<CursorPageResponse<KlassSummaryResponse>> list(
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "조회 개수는 1 이상이어야 합니다")
            @Max(value = KlassQuery.MAX_SIZE, message = "조회 개수는 100 이하여야 합니다") int size,
            @RequestParam(required = false) KlassStatus status) {

        return ApiResponse.ok(CursorPageResponse.from(
                listKlassUseCase.listPublic(new KlassQuery(cursor, size, status)),
                KlassSummaryResponse::from));
    }

    /**
     * 내가 개설한 강의 목록. {@code DRAFT} 를 포함한 전부를 돌려준다.
     *
     * <p><b>이 경로는 {@code /{id}} 보다 먼저 매칭돼야 한다.</b> Spring MVC 는 리터럴 경로를
     * 변수 경로보다 우선하므로 자동으로 해결되고, {@code {id}} 를 {@code Long} 으로 받아
     * 두 번째 방어선을 둔다. {@code SecurityConfig} 쪽에도 같은 함정이 있다 — 그쪽은
     * 규칙 순서와 숫자 패턴으로 막는다.
     *
     * @return 200 과 커서 페이지
     */
    @GetMapping("/me")
    public ApiResponse<CursorPageResponse<KlassSummaryResponse>> listMine(
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "조회 개수는 1 이상이어야 합니다")
            @Max(value = KlassQuery.MAX_SIZE, message = "조회 개수는 100 이하여야 합니다") int size,
            @RequestParam(required = false) KlassStatus status,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        return ApiResponse.ok(CursorPageResponse.from(
                listKlassUseCase.listByCreator(principal.id(), new KlassQuery(cursor, size, status)),
                KlassSummaryResponse::from));
    }

    /**
     * 조회자 id 를 꺼낸다. <b>비로그인이면 {@code null} 이다.</b>
     *
     * <p>선택적 인증 경로에서 {@code principal.id()} 를 바로 부르면 NPE 가 난다.
     * 이 저장소에서 principal 이 {@code null} 일 수 있는 첫 경로다.
     */
    private static Long viewerIdOf(AuthenticatedUser principal) {
        return principal == null ? null : principal.id();
    }
}
