package com.toby.klass.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.toby.klass.user.adapter.out.persistence.UserJpaRepository;
import com.toby.klass.user.domain.Role;
import com.toby.klass.user.domain.User;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 강의 관리 전 흐름 검증 (L4).
 *
 * <h2>여기서만 검증되는 것 — 권한과 인증</h2>
 * {@code KlassControllerTest}(L3)는 {@code @WebMvcTest} + {@code addFilters = false} 라
 * <b>{@code SecurityConfig} 가 아예 없다.</b> {@code hasRole("CREATOR")} 규칙이 실제로
 * 적용되는지, 선택적 인증 경로가 정말 열려 있는지, {@code /me} 가 무인증으로 새지 않는지는
 * <b>이 테스트만 잡을 수 있다.</b>
 *
 * <p>특히 다음 셋은 다른 어떤 테스트도 잡지 못한다.
 * <ul>
 *   <li><b>규칙 순서</b> — {@code permitAll} 이 {@code hasRole} 보다 앞에 오면
 *       {@code /v1/klasses/me} 가 무인증으로 열린다. 컴파일도 L1~L3 도 전부 통과한다</li>
 *   <li><b>{@code hasRole} 접두어</b> — {@code hasRole("ROLE_CREATOR")} 로 쓰면
 *       {@code ROLE_ROLE_CREATOR} 를 찾아 <b>정상 요청까지 403</b> 이 된다</li>
 *   <li><b>수평 권한 상승</b> — 크리에이터 A 가 B 의 강의를 수정하는 경로. 권한 검사만으로는
 *       막히지 않는다 (Context Anchor RISK)</li>
 * </ul>
 *
 * <h2>상태코드만 보면 위양성이 난다</h2>
 * {@link TestRestTemplate} 은 리다이렉트를 따라간다. Security 설정이 잘못돼 로그인 페이지로
 * 보내면 <b>200 + {@code text/html}</b> 이 돌아와 "성공"으로 읽힌다 — 이 저장소에서 실제로
 * 겪은 사고다. 그래서 성공 케이스는 <b>응답 본문의 마커까지</b> 확인한다.
 *
 * <p>Design Ref: §8.6 L4, §4.2 선택적 인증
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class KlassFlowIntegrationTest {

    /** 기본 시딩 계정. {@code ROLE_USER} 만 갖는다. */
    private static final String USER = "chals";

    /** 기본 시딩 계정. {@code ROLE_USER} + {@code ROLE_CREATOR}. */
    private static final String CREATOR = "creator";

    /**
     * 이 테스트가 직접 만드는 <b>두 번째</b> 크리에이터.
     *
     * <p>수평 권한 상승을 검증하려면 크리에이터가 둘 필요한데 시딩에는 하나뿐이다.
     * {@code DefaultUserInitializer} 를 늘리지 않는 이유는 <b>기동 기본값이 문서화된
     * 계약</b>이기 때문이다 — 테스트 편의로 운영 시딩을 바꾸면 안 된다 (Design §8.7).
     */
    private static final String OTHER_CREATOR = "creator2";

    private static final String PASSWORD = "test";

    /**
     * 취소 가능 기간. <b>{@link #registerKlass} 와 {@link #updateKlass} 가 같은 값을 써야 한다.</b>
     *
     * <p>취소 가능 기간은 {@code DRAFT} 에서만 반영된다 (Design D-26 · D-28). 두 헬퍼의 값이
     * 어긋나면 {@code OPEN} 전이 뒤의 수정에서 <b>보낸 값이 조용히 무시돼</b>(409 가 아니다)
     * 단언이 기대값과 달라진다 — 실패 원인이 "무시됐다"로 드러나지 않아 추적이 어렵다.
     * 상수로 묶어 그 어긋남이 생기지 않게 한다.
     */
    private static final int CANCELLATION_PERIOD_DAYS = 7;

    @LocalServerPort
    private int port;

    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private Clock clock;

    @BeforeEach
    void setUp() {
        restTemplate = new TestRestTemplate();
        if (userJpaRepository.findByUsername(OTHER_CREATOR).isEmpty()) {
            userJpaRepository.save(User.register(OTHER_CREATOR, passwordEncoder.encode(PASSWORD),
                    Set.of(Role.ROLE_USER, Role.ROLE_CREATOR), LocalDateTime.now(clock)));
        }
    }

    // ── 요청 헬퍼 ───────────────────────────────────────────────────

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private ResponseEntity<String> exchange(HttpMethod method, String path,
                                            String body, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return restTemplate.exchange(url(path), method,
                new HttpEntity<>(body, headers), String.class);
    }

    private JsonNode json(ResponseEntity<String> response) {
        return objectMapper.readTree(response.getBody());
    }

    private String errorCode(ResponseEntity<String> response) {
        return json(response).path("error").path("code").asString();
    }

    /** 로그인해서 Access 토큰을 얻는다. */
    private String tokenOf(String username) {
        ResponseEntity<String> response = exchange(HttpMethod.POST, "/v1/auth/login", """
                {"username":"%s","password":"%s"}""".formatted(username, PASSWORD), null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return json(response).path("data").path("accessToken").asString();
    }

    /** 강의를 등록하고 id 를 돌려준다. */
    private long registerKlass(String token, String title) {
        // cancellationPeriodDays 를 명시한다 — 생략하면 null 로 저장되고, updateKlass 가
        // 싣는 값과 어긋나 OPEN 이후 수정이 409 가 된다 (위 상수 주석 참조)
        ResponseEntity<String> response = exchange(HttpMethod.POST, "/v1/klasses", """
                {
                  "title": "%s",
                  "description": "%s 의 내용",
                  "price": 50000,
                  "capacity": 30,
                  "startsOn": "2026-10-01",
                  "endsOn": "2026-12-31",
                  "cancellationPeriodDays": %d
                }""".formatted(title, title, CANCELLATION_PERIOD_DAYS), token);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return json(response).path("data").path("id").asLong();
    }

    private ResponseEntity<String> changeStatus(String token, long klassId, String status) {
        return exchange(HttpMethod.PATCH, "/v1/klasses/" + klassId + "/status",
                """
                {"status":"%s"}""".formatted(status), token);
    }

    /**
     * 강의를 수정한다. 제목만 인자로 받되 <b>본문에는 전 필드를 싣는다.</b>
     *
     * <h2>일부 필드만 보내면 테스트가 엉뚱한 이유로 실패한다</h2>
     * 수정은 <b>전체 교체</b>다 (Design D-25) — 필드가 빠지면 400 이 나간다. 권한 테스트가
     * 부분 본문을 쓰면 403/404 를 검증하려던 자리에서 <b>검증이 시작되기도 전에 400</b> 이
     * 돌아온다. 그래서 수정 본문을 만드는 곳을 여기 하나로 모았다.
     *
     * <h2>{@code cancellationPeriodDays} 는 강의의 <b>현재 값</b>을 실어야 한다</h2>
     * 그 필드는 {@code DRAFT} 에서만 반영된다 (Design D-26 · D-28). 등록 시점과 다른 값을
     * 하드코딩하면 {@code OPEN} 이후의 수정에서 <b>그 값이 조용히 무시돼</b>(409 가 아니다)
     * 저장값 단언이 어긋난다 — 실패가 "무시됐다"로 드러나지 않아 추적이 어렵다.
     * {@link #CANCELLATION_PERIOD_DAYS} 를 {@link #registerKlass} 와 공유해 구조적으로 막는다.
     */
    private ResponseEntity<String> updateKlass(String token, long klassId, String title) {
        return exchange(HttpMethod.PUT, "/v1/klasses/" + klassId, """
                {
                  "title": "%s",
                  "description": "%s 의 내용",
                  "price": 50000,
                  "capacity": 30,
                  "startsOn": "2026-10-01",
                  "endsOn": "2026-12-31",
                  "cancellationPeriodDays": %d
                }""".formatted(title, title, CANCELLATION_PERIOD_DAYS), token);
    }

    /**
     * 취소 가능 기간만 다른 값으로 바꾸려는 수정 요청. 나머지 필드는 현재 값 그대로다.
     *
     * <p>{@code DRAFT} 아닌 강의에서 409 가 나오는지 확인하는 데 쓴다.
     */
    private ResponseEntity<String> updateCancellationPeriod(String token, long klassId,
                                                            String title, int days) {
        return exchange(HttpMethod.PUT, "/v1/klasses/" + klassId, """
                {
                  "title": "%s",
                  "description": "%s 의 내용",
                  "price": 50000,
                  "capacity": 30,
                  "startsOn": "2026-10-01",
                  "endsOn": "2026-12-31",
                  "cancellationPeriodDays": %d
                }""".formatted(title, title, days), token);
    }

    // ── ① 정상 흐름 ─────────────────────────────────────────────────

    @Test
    @DisplayName("#1 등록 → 초안은 목록에 없음 → 수정 → 공개 → 목록에 등장 → 비로그인 조회 → 마감")
    void fullLifecycle() {
        String token = tokenOf(CREATOR);

        long klassId = registerKlass(token, "통합 흐름 강의");

        // 초안은 공개 목록에 없다 — 개설자 본인에게도 (D-14)
        assertThat(publicListTitles(token)).doesNotContain("통합 흐름 강의");
        // 내 강의 목록에는 있다
        assertThat(myListTitles(token)).contains("통합 흐름 강의");

        // 수정 — 전 필드를 실어 보낸다 (전체 교체, D-25)
        ResponseEntity<String> updated = updateKlass(token, klassId, "제목이 바뀐 강의");
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(updated).path("data").path("title").asString())
                .isEqualTo("제목이 바뀐 강의");
        // 같은 값으로 보낸 필드는 그 값으로 저장된다
        assertThat(json(updated).path("data").path("capacity").asInt()).isEqualTo(30);

        // 공개
        assertThat(changeStatus(token, klassId, "OPEN").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(publicListTitles(token)).contains("제목이 바뀐 강의");

        // OPEN 이 된 뒤에도 수정된다. 전체 교체라 본문이 cancellationPeriodDays 를 항상
        // 싣고 오는데, 그 필드는 DRAFT 에서만 반영된다 (D-26). 다른 상태에서 온 값을
        // 거부하지 않고 조용히 무시하기로 한 것이 D-28 이며, 거부했다면 OPEN 강의는
        // 제목 하나도 고칠 수 없다. 실제 필터 체인을 통과하는 경로에서 그것을 고정한다
        ResponseEntity<String> afterOpen = updateKlass(token, klassId, "공개 후 수정된 강의");
        assertThat(afterOpen.getStatusCode())
                .as("OPEN 강의도 제목 수정은 통과한다 — 취소 기간은 무시된다 (D-26 · D-28)")
                .isEqualTo(HttpStatus.OK);
        assertThat(json(afterOpen).path("data").path("title").asString())
                .isEqualTo("공개 후 수정된 강의");
        assertThat(json(afterOpen).path("data").path("status").asString()).isEqualTo("OPEN");
        assertThat(json(afterOpen).path("data").path("cancellationPeriodDays").asInt())
                .isEqualTo(CANCELLATION_PERIOD_DAYS);

        // 비로그인 상세 조회 — 선택적 인증이 실제로 열려 있는가
        ResponseEntity<String> anonymous = exchange(HttpMethod.GET, "/v1/klasses/" + klassId,
                null, null);
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(anonymous).path("data").path("title").asString())
                .as("상태코드만 보면 리다이렉트된 HTML 도 200 이다 — 본문 마커까지 확인한다")
                .isEqualTo("공개 후 수정된 강의");

        // 마감
        assertThat(changeStatus(token, klassId, "CLOSED").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(exchange(HttpMethod.GET, "/v1/klasses/" + klassId, null, null))
                .path("data").path("status").asString()).isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("#2 DRAFT → CLOSED 개설 철회가 통과한다")
    void withdrawDraft() {
        String token = tokenOf(CREATOR);
        long klassId = registerKlass(token, "철회할 강의");

        ResponseEntity<String> response = changeStatus(token, klassId, "CLOSED");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // 상태코드만 보면 리다이렉트된 HTML 도 200 이다 — 이 파일 javadoc 이 금지하는 형태
        assertThat(json(response).path("data").path("status").asString()).isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("#3 CLOSED → OPEN 역전이는 409 다")
    void rejectsReopening() {
        String token = tokenOf(CREATOR);
        long klassId = registerKlass(token, "역전이 시도 강의");
        changeStatus(token, klassId, "CLOSED");

        ResponseEntity<String> response = changeStatus(token, klassId, "OPEN");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(errorCode(response)).isEqualTo("INVALID_KLASS_STATUS_TRANSITION");
    }

    /**
     * Design D-28 — 공개된 뒤에는 제목만 바뀐다.
     *
     * <p><b>거부가 아니라 무시</b>라는 것이 이 정책의 핵심이고, 그것은 <b>응답 본문을
     * 봐야만</b> 확인된다. 상태코드는 200 이므로 그것만 보면 "수정 성공"으로 읽힌다 —
     * 이 파일 javadoc 이 경고하는 위양성의 또 다른 형태다.
     */
    @Test
    @DisplayName("#15 OPEN 강의는 제목만 바뀐다 — 나머지는 200 이면서 무시된다")
    void ignoresNonTitleFieldsAfterOpen() {
        String token = tokenOf(CREATOR);
        long klassId = registerKlass(token, "공개된 강의");
        assertThat(changeStatus(token, klassId, "OPEN").getStatusCode()).isEqualTo(HttpStatus.OK);

        // 전 필드를 다른 값으로 실어 보낸다
        ResponseEntity<String> response = exchange(HttpMethod.PUT, "/v1/klasses/" + klassId, """
                {
                  "title": "제목만 바뀐다",
                  "description": "바뀌지 않을 내용",
                  "price": 99999,
                  "capacity": 99,
                  "startsOn": "2027-01-01",
                  "endsOn": "2027-06-30",
                  "cancellationPeriodDays": 30
                }""", token);

        assertThat(response.getStatusCode())
                .as("거부가 아니라 무시다 — 409 가 아니라 200 이다")
                .isEqualTo(HttpStatus.OK);

        JsonNode data = json(response).path("data");
        assertThat(data.path("title").asString()).isEqualTo("제목만 바뀐다");
        assertThat(data.path("description").asString()).isEqualTo("공개된 강의 의 내용");
        assertThat(data.path("price").asInt()).isEqualTo(50000);
        assertThat(data.path("capacity").asInt()).isEqualTo(30);
        assertThat(data.path("startsOn").asString()).isEqualTo("2026-10-01");
        assertThat(data.path("endsOn").asString()).isEqualTo("2026-12-31");
        assertThat(data.path("cancellationPeriodDays").asInt())
                .isEqualTo(CANCELLATION_PERIOD_DAYS);
    }

    @Test
    @DisplayName("#16 DRAFT 강의의 취소 가능 기간은 바꿀 수 있다")
    void allowsCancellationPeriodChangeOnDraft() {
        String token = tokenOf(CREATOR);
        long klassId = registerKlass(token, "취소 기간 바꿀 초안");

        ResponseEntity<String> response = updateCancellationPeriod(
                token, klassId, "취소 기간 바꿀 초안", CANCELLATION_PERIOD_DAYS + 7);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // 상태코드만 보면 리다이렉트된 HTML 도 200 이다 — 본문 마커까지 확인한다
        assertThat(json(response).path("data").path("cancellationPeriodDays").asInt())
                .isEqualTo(CANCELLATION_PERIOD_DAYS + 7);
    }

    private java.util.List<String> publicListTitles(String token) {
        ResponseEntity<String> response =
                exchange(HttpMethod.GET, "/v1/klasses?size=100", null, token);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return json(response).path("data").path("items").valueStream()
                .map(n -> n.path("title").asString()).toList();
    }

    private java.util.List<String> myListTitles(String token) {
        ResponseEntity<String> response =
                exchange(HttpMethod.GET, "/v1/klasses/me?size=100", null, token);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return json(response).path("data").path("items").valueStream()
                .map(n -> n.path("title").asString()).toList();
    }

    // ── ② 인증·권한 게이트 ──────────────────────────────────────────

    /**
     * <b>L3 에서 이관된 케이스들이다.</b> 슬라이스 테스트는 보안 필터가 꺼져 있어
     * 여기 있는 것들을 하나도 잡지 못한다.
     */
    @Nested
    @DisplayName("인증·권한 관문")
    class AuthGate {

        @Test
        @DisplayName("#4 ROLE_USER 로는 강의를 등록할 수 없다 — hasRole 이 실제로 적용되는가")
        void plainUserCannotRegister() {
            ResponseEntity<String> response = exchange(HttpMethod.POST, "/v1/klasses", """
                    {
                      "title": "권한 없는 등록",
                      "description": "내용",
                      "price": 1000, "capacity": 10,
                      "startsOn": "2026-10-01", "endsOn": "2026-12-31"
                    }""", tokenOf(USER));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("#5 다른 크리에이터의 강의는 수정할 수 없다 — 권한만으로는 막히지 않는 지점")
        void otherCreatorCannotUpdate() {
            long klassId = registerKlass(tokenOf(CREATOR), "남의 강의");
            changeStatus(tokenOf(CREATOR), klassId, "OPEN");

            ResponseEntity<String> response =
                    updateKlass(tokenOf(OTHER_CREATOR), klassId, "가로챈 제목");

            assertThat(response.getStatusCode())
                    .as("ROLE_CREATOR 는 있지만 소유자가 아니다 — Context Anchor RISK")
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(errorCode(response)).isEqualTo("NOT_KLASS_OWNER");
        }

        @Test
        @DisplayName("#6 다른 크리에이터의 초안은 403 이 아니라 404 다 — 존재가 새면 안 된다")
        void otherCreatorGetsNotFoundOnDraft() {
            long klassId = registerKlass(tokenOf(CREATOR), "남의 초안");

            ResponseEntity<String> response =
                    updateKlass(tokenOf(OTHER_CREATOR), klassId, "가로챈 제목");

            assertThat(response.getStatusCode())
                    .as("가시성 검사가 소유권 검사보다 먼저 와야 한다 (Design §6.3)")
                    .isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(errorCode(response)).isEqualTo("KLASS_NOT_FOUND");
        }

        @Test
        @DisplayName("#7 /me 는 토큰 없이 열리지 않는다 — permitAll 이 이 경로를 삼키면 여기서 걸린다")
        void myListRequiresAuthentication() {
            ResponseEntity<String> response =
                    exchange(HttpMethod.GET, "/v1/klasses/me", null, null);

            assertThat(response.getStatusCode())
                    .as("SecurityConfig 규칙 순서가 뒤집히면 200 이 된다")
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("#8 /me 는 ROLE_USER 만으로 열리지 않는다")
        void myListRequiresCreatorRole() {
            ResponseEntity<String> response =
                    exchange(HttpMethod.GET, "/v1/klasses/me", null, tokenOf(USER));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("#9 공개 목록은 토큰 없이 열린다 — 선택적 인증이 실제로 동작하는가")
        void publicListIsOpenToAnonymous() {
            ResponseEntity<String> response =
                    exchange(HttpMethod.GET, "/v1/klasses", null, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(json(response).path("success").asBoolean())
                    .as("리다이렉트된 HTML 이 아니라 우리 응답 형식인지 확인한다")
                    .isTrue();
        }

        @Test
        @DisplayName("#10 비로그인은 타인의 초안을 볼 수 없다")
        void anonymousCannotSeeDraft() {
            long klassId = registerKlass(tokenOf(CREATOR), "숨겨진 초안");

            ResponseEntity<String> response =
                    exchange(HttpMethod.GET, "/v1/klasses/" + klassId, null, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(errorCode(response)).isEqualTo("KLASS_NOT_FOUND");
        }

        @Test
        @DisplayName("#11 개설자는 자기 초안을 본다")
        void ownerSeesOwnDraft() {
            String token = tokenOf(CREATOR);
            long klassId = registerKlass(token, "내 초안");

            ResponseEntity<String> response =
                    exchange(HttpMethod.GET, "/v1/klasses/" + klassId, null, token);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(json(response).path("data").path("status").asString()).isEqualTo("DRAFT");
        }
    }

    /**
     * 파라미터 검증이 <b>500 이 아니라 400</b> 으로 나가는지 확인한다.
     *
     * <p>이 기능이 저장소 최초로 쿼리 파라미터와 경로 변수를 쓴다. {@code Advice} 에
     * 핸들러를 더하지 않았다면 {@code handleUnexpected} 가 잡아 전부 500 이 됐을 자리다.
     */
    @Nested
    @DisplayName("파라미터 검증")
    class ParameterValidation {

        /**
         * <b>두 방어선 중 첫째가 잡는다.</b>
         *
         * <p>{@code @Max} 가 붙은 {@code size} 파라미터를 Spring 의 내장 메서드 검증이
         * 확인해 {@code HandlerMethodValidationException} 을 던지고, Advice 가
         * {@code VALIDATION_ERROR}(400)로 번역한다. {@code KlassQuery} 생성자의
         * {@code INVALID_KLASS_PAGE_SIZE} 는 여기까지 도달하지 않는다 — 그쪽은 포트를
         * 직접 호출하는 경로를 위한 둘째 방어선이고, {@code KlassQueryTest} 의
         * {@code rejectsSizeOutOfRange} 가 검증한다.
         *
         * <p><b>클래스에 {@code @Validated} 를 붙이면 이 테스트가 500 으로 깨진다</b> —
         * AOP 검증이 대신 동작해 Advice 가 모르는 예외를 던진다 (컨트롤러 javadoc 참조).
         */
        @Test
        @DisplayName("#12 size 범위 초과는 400 이다 — 파라미터 제약이 먼저 잡는다")
        void rejectsOversizedPage() {
            ResponseEntity<String> response =
                    exchange(HttpMethod.GET, "/v1/klasses?size=101", null, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(errorCode(response)).isEqualTo("VALIDATION_ERROR");
        }

        @Test
        @DisplayName("#13 정의되지 않은 status 값은 400 이다")
        void rejectsUnknownStatus() {
            ResponseEntity<String> response =
                    exchange(HttpMethod.GET, "/v1/klasses?status=OPENED", null, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(errorCode(response)).isEqualTo("VALIDATION_ERROR");
        }

        /**
         * <b>400 이 아니라 401 이다 — 그리고 그것이 의도한 결과다.</b>
         *
         * <p>{@code SecurityConfig} 의 permitAll 매처가 {@code /v1/klasses/\{id:[0-9]+\}} 로
         * <b>숫자만</b> 받는다. {@code /v1/klasses/abc} 는 여기에 걸리지 않아
         * {@code anyRequest().authenticated()} 로 떨어지고, 컨트롤러에 닿기 전에 401 이 된다.
         *
         * <p>매처를 {@code /v1/klasses/*} 로 넓히면 400 을 돌려줄 수 있지만, 그러면
         * {@code /v1/klasses/me} 를 지키는 방어선이 <b>규칙 순서 하나</b>로 줄어든다.
         * 잘못된 경로에 대한 상태코드 정확도보다 그쪽이 중요하다.
         *
         * <p><b>이 지점은 L3 과 L4 의 답이 갈린다.</b> 슬라이스 테스트에는
         * {@code SecurityConfig} 가 없어 컨트롤러까지 도달해 400 이 나온다 — 실제 앱에서는
         * 일어나지 않는 응답이다. 그래서 이 경로의 계약은 <b>여기서만</b> 단언한다.
         */
        @Test
        @DisplayName("#14 숫자가 아닌 경로 변수는 401 이다 — 매처가 숫자만 받기 때문이다")
        void rejectsNonNumericPathVariable() {
            ResponseEntity<String> response =
                    exchange(HttpMethod.GET, "/v1/klasses/abc", null, null);

            assertThat(response.getStatusCode())
                    .as("permitAll 매처가 {id:[0-9]+} 라 컨트롤러에 닿지 않는다")
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }
}
