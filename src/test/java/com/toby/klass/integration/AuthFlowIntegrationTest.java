package com.toby.klass.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.toby.klass.auth.adapter.out.persistence.RefreshTokenJpaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 실제 필터 체인을 통과하는 인증 시나리오 검증.
 *
 * <h2>이 테스트가 없으면 안 되는 이유</h2>
 * L3 문서화 테스트는 {@code @WebMvcTest} 슬라이스에 {@code addFilters = false} 로 돈다.
 * 즉 <b>JWT 필터가 실제로 동작하는지는 그쪽에서 전혀 검증되지 않는다.</b> 여기가 그 공백을
 * 메우는 유일한 지점이므로 선택 사항이 아니다.
 *
 * <p>특히 다음 세 가지는 다른 어떤 테스트도 잡지 못한다.
 * <ul>
 *   <li><b>재사용 감지의 트랜잭션 분리</b>(#5) — {@code REQUIRES_NEW} 가 빠지면 침해 대응
 *       UPDATE 가 롤백되는데, 단위 테스트는 목이라 이를 재현할 수 없다</li>
 *   <li><b>Security 7 CSRF 회귀</b>(#8) — 설정을 되돌리면 모든 POST 가 403 이 된다</li>
 *   <li><b>필터와 Advice 의 응답 형식 일치</b>(#2) — 두 경로가 서로 다른 코드에 있어
 *       한쪽만 고치면 조용히 어긋난다</li>
 * </ul>
 *
 * <p>문서 산출물 검증은 {@code DocumentationIntegrationTest} 에 따로 있다.
 * 그쪽은 {@code generatedDocument} 이후에 돌아야 해서 별도 태스크로 분리했다.
 *
 * <p>Design Ref: §8.4 L3 통합/E2E 시나리오
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthFlowIntegrationTest {

    private static final String USERNAME = "chals";
    private static final String PASSWORD = "test";

    /**
     * 랜덤 포트. Boot 4 에서 {@link TestRestTemplate} 이 별도 모듈
     * ({@code spring-boot-resttestclient})로 분리되면서 <b>빈으로 자동 등록되지 않는다.</b>
     * 포트를 주입받아 직접 조립한다.
     */
    @LocalServerPort
    private int port;

    private TestRestTemplate restTemplate;

    @BeforeEach
    void setUpRestTemplate() {
        restTemplate = new TestRestTemplate();
    }

    /** 상대 경로를 실제 호출 URL 로 바꾼다. */
    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RefreshTokenJpaRepository refreshTokenJpaRepository;

    // ── 요청 헬퍼 ───────────────────────────────────────────────────

    private ResponseEntity<String> postJson(String path, String body) {
        return postJson(path, body, null);
    }

    private ResponseEntity<String> postJson(String path, String body, String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (accessToken != null) {
            headers.setBearerAuth(accessToken);
        }
        return restTemplate.exchange(url(path), HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> get(String path, String bearerToken) {
        HttpHeaders headers = new HttpHeaders();
        if (bearerToken != null) {
            headers.setBearerAuth(bearerToken);
        }
        return restTemplate.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private JsonNode json(ResponseEntity<String> response) {
        return objectMapper.readTree(response.getBody());
    }

    private String errorCode(ResponseEntity<String> response) {
        return json(response).path("error").path("code").asString();
    }

    /** 로그인해서 토큰 쌍을 받는다. */
    private JsonNode login() {
        ResponseEntity<String> response = postJson("/v1/auth/login",
                """
                {"username":"%s","password":"%s"}""".formatted(USERNAME, PASSWORD));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return json(response).path("data");
    }

    private ResponseEntity<String> reissue(String refreshToken) {
        return postJson("/v1/auth/reissue", """
                {"refreshToken":"%s"}""".formatted(refreshToken));
    }

    // ── 시나리오 ────────────────────────────────────────────────────

    @Test
    @DisplayName("#1 시딩 확인 — 사용자 생성 없이 chals/test 로 로그인된다")
    void seededUserCanLogIn() {
        JsonNode data = login();

        assertThat(data.path("tokenType").asString()).isEqualTo("Bearer");
        assertThat(data.path("accessToken").asString()).isNotBlank();
        assertThat(data.path("refreshToken").asString()).isNotBlank();
        assertThat(data.path("accessTokenExpiresIn").asLong()).isEqualTo(1800);
    }

    @Nested
    @DisplayName("인증 관문")
    class AuthGate {

        @Test
        @DisplayName("#2 토큰 없이 보호 자원에 접근하면 UNAUTHENTICATED 이고, 형식이 Advice 와 같다")
        void rejectsRequestWithoutToken() {
            ResponseEntity<String> response = get("/v1/users/me", null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(errorCode(response)).isEqualTo("UNAUTHENTICATED");
            // 필터에서 만든 응답이 Advice 가 만드는 것과 같은 3필드 구조여야 한다
            JsonNode body = json(response);
            assertThat(body.has("success")).isTrue();
            assertThat(body.has("data")).isTrue();
            assertThat(body.has("error")).isTrue();
            assertThat(body.path("success").asBoolean()).isFalse();
            assertThat(body.path("error").path("message").asString()).isNotBlank();
        }

        @Test
        @DisplayName("#2b 형식이 잘못된 토큰은 TOKEN_INVALID 다")
        void rejectsGarbageToken() {
            ResponseEntity<String> response = get("/v1/users/me", "garbage");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(errorCode(response)).isEqualTo("TOKEN_INVALID");
        }

        @Test
        @DisplayName("#3 정상 Access 토큰이면 통과한다 — JWT 필터 실동작 검증")
        void acceptsValidAccessToken() {
            String accessToken = login().path("accessToken").asString();

            ResponseEntity<String> response = get("/v1/users/me", accessToken);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(json(response).path("data").path("username").asString()).isEqualTo(USERNAME);
        }

        @Test
        @DisplayName("#6 Refresh 토큰으로는 보호 자원에 접근할 수 없다")
        void rejectsRefreshTokenAsAccessToken() {
            String refreshToken = login().path("refreshToken").asString();

            ResponseEntity<String> response = get("/v1/users/me", refreshToken);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(errorCode(response)).isEqualTo("TOKEN_TYPE_MISMATCH");
        }
    }

    @Nested
    @DisplayName("토큰 회전")
    class Rotation {

        @Test
        @DisplayName("#4 재발급하면 새 토큰 쌍이 나오고 그것으로 인증된다")
        void rotatesAndIssuesUsableTokens() {
            String oldRefresh = login().path("refreshToken").asString();

            JsonNode reissued = json(reissue(oldRefresh)).path("data");
            String newAccess = reissued.path("accessToken").asString();
            String newRefresh = reissued.path("refreshToken").asString();

            assertThat(newRefresh).isNotEqualTo(oldRefresh);
            assertThat(get("/v1/users/me", newAccess).getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("#5 재사용을 감지하면 직전에 발급된 토큰까지 무효화된다 — REQUIRES_NEW 검증")
        void reuseDetectionRevokesAllTokens() {
            String oldRefresh = login().path("refreshToken").asString();
            String newRefresh = json(reissue(oldRefresh)).path("data").path("refreshToken").asString();

            // 탈취된 옛 토큰으로 재발급 시도
            ResponseEntity<String> reused = reissue(oldRefresh);
            assertThat(reused.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(errorCode(reused)).isEqualTo("REFRESH_TOKEN_REUSED");

            // 여기가 핵심이다. 침해 대응이 같은 트랜잭션에서 일어났다면 예외 재전파로
            // 롤백되어 아래 요청이 200 을 냈을 것이다.
            ResponseEntity<String> afterBreach = reissue(newRefresh);
            assertThat(afterBreach.getStatusCode())
                    .as("REQUIRES_NEW 가 없으면 무효화가 롤백되어 이 요청이 성공한다")
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(errorCode(afterBreach)).isEqualTo("REFRESH_TOKEN_REUSED");
        }
    }

    @Nested
    @DisplayName("로그아웃")
    class Logout {

        @Test
        @DisplayName("#7 로그아웃한 토큰으로 재발급하면 NOT_FOUND 다 — REUSED 가 아니다")
        void loggedOutTokenIsNotFoundRatherThanReused() {
            JsonNode tokens = login();
            String access = tokens.path("accessToken").asString();
            String refresh = tokens.path("refreshToken").asString();

            ResponseEntity<String> logout = postJson("/v1/auth/logout",
                    """
                    {"refreshToken":"%s"}""".formatted(refresh), access);
            assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

            ResponseEntity<String> afterLogout = reissue(refresh);
            assertThat(afterLogout.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            // 폐기(revoke)로 남겼다면 REFRESH_TOKEN_REUSED 가 되면서
            // 정상 사용자의 전체 세션이 끊겼을 것이다. 삭제하기 때문에 NOT_FOUND 다.
            assertThat(errorCode(afterLogout))
                    .as("로그아웃은 삭제이므로 재사용이 아니라 미등록으로 처리돼야 한다")
                    .isEqualTo("REFRESH_TOKEN_NOT_FOUND");
        }

        @Test
        @DisplayName("#7b 이미 삭제된 refresh 를 다시 로그아웃해도 204 다 — 멱등")
        void logoutIsIdempotent() {
            // 세션 두 개를 만든다. 첫 세션의 refresh 를 두 번 로그아웃하되,
            // Access 토큰은 매번 살아 있는 것을 쓴다 — 로그아웃한 Access 는 즉시 폐기되므로
            // 같은 것을 재사용하면 멱등성이 아니라 폐기 동작을 검증하게 된다(#7d 가 그것을 맡는다).
            String targetRefresh = login().path("refreshToken").asString();
            String body = """
                    {"refreshToken":"%s"}""".formatted(targetRefresh);

            assertThat(postJson("/v1/auth/logout", body, login().path("accessToken").asString())
                    .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

            // 두 번째는 삭제할 행이 0건이다. 그래도 204 여야 한다 —
            // 토큰의 존재 여부를 응답으로 알려주지 않기 위함이다.
            assertThat(postJson("/v1/auth/logout", body, login().path("accessToken").asString())
                    .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        }

        @Test
        @DisplayName("#7c 로그아웃하면 그 Access 토큰이 즉시 막힌다 — 만료를 기다리지 않는다")
        void logoutRevokesAccessTokenImmediately() {
            JsonNode tokens = login();
            String access = tokens.path("accessToken").asString();

            // 로그아웃 직전에는 통과한다
            assertThat(get("/v1/users/me", access).getStatusCode()).isEqualTo(HttpStatus.OK);

            postJson("/v1/auth/logout", """
                    {"refreshToken":"%s"}""".formatted(tokens.path("refreshToken").asString()), access);

            // 이 검증이 이 기능의 존재 이유다. 폐기 목록이 없으면 Access 토큰은
            // 서명도 유효하고 만료도 안 됐으므로 200 이 나온다.
            ResponseEntity<String> afterLogout = get("/v1/users/me", access);
            assertThat(afterLogout.getStatusCode())
                    .as("폐기 목록 대조가 빠지면 로그아웃한 토큰이 만료까지 계속 통과한다")
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(errorCode(afterLogout)).isEqualTo("TOKEN_REVOKED");
        }

        @Test
        @DisplayName("#7d 로그아웃한 Access 토큰으로는 로그아웃도 다시 부를 수 없다")
        void revokedAccessTokenCannotCallLogoutAgain() {
            JsonNode tokens = login();
            String access = tokens.path("accessToken").asString();
            String body = """
                    {"refreshToken":"%s"}""".formatted(tokens.path("refreshToken").asString());

            assertThat(postJson("/v1/auth/logout", body, access).getStatusCode())
                    .isEqualTo(HttpStatus.NO_CONTENT);

            // 로그아웃 엔드포인트도 인증이 필요하므로 폐기된 토큰은 여기서도 막힌다.
            ResponseEntity<String> again = postJson("/v1/auth/logout", body, access);
            assertThat(again.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(errorCode(again)).isEqualTo("TOKEN_REVOKED");
        }

        @Test
        @DisplayName("#7e 다른 세션의 Access 토큰은 로그아웃의 영향을 받지 않는다")
        void logoutDoesNotAffectOtherSessions() {
            String otherAccess = login().path("accessToken").asString();

            JsonNode tokens = login();
            postJson("/v1/auth/logout", """
                    {"refreshToken":"%s"}""".formatted(tokens.path("refreshToken").asString()),
                    tokens.path("accessToken").asString());

            // 폐기는 jti 단위다. 사용자 단위로 끊으면 다른 기기까지 로그아웃된다.
            assertThat(get("/v1/users/me", otherAccess).getStatusCode())
                    .as("폐기 범위가 사용자 단위로 넓어지면 다른 기기의 세션까지 끊긴다")
                    .isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("로그아웃은 행을 삭제한다 — 폐기가 아니다")
        void logoutDeletesRow() {
            JsonNode tokens = login();
            long before = refreshTokenJpaRepository.count();

            postJson("/v1/auth/logout", """
                    {"refreshToken":"%s"}""".formatted(tokens.path("refreshToken").asString()),
                    tokens.path("accessToken").asString());

            assertThat(refreshTokenJpaRepository.count()).isEqualTo(before - 1);
        }
    }

    @Nested
    @DisplayName("회귀 방어")
    class Regression {

        @Test
        @DisplayName("#8 CSRF — POST 가 403 이 아니다 (Security 7 기본 적용 회귀 방어)")
        void csrfIsDisabledForApi() {
            ResponseEntity<String> response = postJson("/v1/auth/login",
                    """
                    {"username":"%s","password":"%s"}""".formatted(USERNAME, PASSWORD));

            // Security 7 은 CSRF 를 API 에도 기본 적용한다. disable 을 되돌리면 여기가 403 이 된다.
            assertThat(response.getStatusCode())
                    .as("403 이면 SecurityConfig 의 csrf disable 이 사라진 것이다")
                    .isNotEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("인증된 요청이 없는 경로를 부르면 404 다 — 500 으로 뭉개지지 않는다")
        void unknownPathReturnsNotFound() {
            String access = login().path("accessToken").asString();

            ResponseEntity<String> response = get("/v1/does-not-exist", access);

            // @ExceptionHandler(Exception.class) 가 NoResourceFoundException 을 삼키면 500 이 된다.
            // module-3 에서 실제로 발생했던 결함이라 회귀 방어로 고정한다.
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(errorCode(response)).isEqualTo("NOT_FOUND");
        }

        @Test
        @DisplayName("인증 없이 없는 경로를 부르면 404 가 아니라 401 이다 — 경로 존재 여부를 노출하지 않는다")
        void unknownPathWithoutAuthReturnsUnauthorized() {
            ResponseEntity<String> response = get("/v1/does-not-exist", null);

            // Security 필터가 DispatcherServlet 앞단이라 인가 판단이 먼저다.
            // 결과적으로 인증되지 않은 사용자는 어떤 경로가 존재하는지 알 수 없다.
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(errorCode(response)).isEqualTo("UNAUTHENTICATED");
        }
    }

}
