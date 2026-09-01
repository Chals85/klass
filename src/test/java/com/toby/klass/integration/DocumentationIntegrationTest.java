package com.toby.klass.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 문서 산출물이 실제로 서빙되는지 검증한다.
 *
 * <h2>왜 별도 클래스이고 별도 Gradle 태스크인가</h2>
 * 이 검증은 {@code openapi3.json} 이 정적 리소스로 배치된 <b>뒤에</b> 돌아야 한다.
 * 그런데 그 산출물을 만드는 {@code generatedDocument} 는 {@code test} 에 의존한다
 * (스니펫이 테스트에서 나오므로). 이 검증을 {@code test} 안에 두면 <b>순환</b>이 된다 —
 * clean 상태에서 아직 존재하지 않는 파일을 요구하게 된다.
 *
 * <p>그래서 {@code documentationTest} 라는 별도 Test 태스크가 {@code generatedDocument}
 * 이후에 이 클래스만 실행한다. {@code build} 에 물려 있으므로 {@code ./gradlew build} 로 함께 돈다.
 *
 * <h2>이 검증이 잡는 것</h2>
 * <ul>
 *   <li>문서 페이지가 참조하는 스펙이 <b>실제로 서빙되는지</b>. 페이지만 200 이고 스펙이
 *       404 면 사용자에게는 빈 화면이 보인다</li>
 *   <li>스펙이 <b>유효한 JSON</b> 인지. 복사 과정에 문자열 치환을 걸면 description 의
 *       개행이 raw 제어문자가 되어 깨진다</li>
 *   <li>설계 §4.1 의 엔드포인트가 <b>빠짐없이</b> 문서화됐는지</li>
 * </ul>
 *
 * <p>Design Ref: §8.5, §11.3 module-5
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DocumentationIntegrationTest {

    /** 문서 페이지가 스펙을 가리키는 상대 경로. HTML 과 실제 서빙 경로가 어긋나면 걸린다. */
    private static final String SPEC_PATH_IN_PAGE = "./openapi3.json";

    /** Design §4.1 의 엔드포인트 수. 문서에 빠짐없이 실려야 한다. */
    private static final int DOCUMENTED_ENDPOINT_COUNT = 4;

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    private TestRestTemplate restTemplate;

    @BeforeEach
    void setUp() {
        restTemplate = new TestRestTemplate();
    }

    private ResponseEntity<String> get(String path) {
        return restTemplate.getForEntity("http://localhost:" + port + path, String.class);
    }

    @Test
    @DisplayName("문서 페이지 두 종이 인증 없이 서빙되고, 각자의 렌더러를 담고 있다")
    void documentationPagesAreAccessible() {
        // 상태코드와 Content-Type 만 보면 안 된다. Security 기본 체인이 켜져 있으면
        // 로그인 페이지로 리다이렉트되는데, TestRestTemplate 이 그것을 따라가 200 + text/html
        // 을 돌려주므로 검사를 통과해 버린다 (module-2 에서 실제로 겪은 위양성).
        // 그래서 각 페이지가 자기 렌더러를 실제로 담고 있는지까지 확인한다.
        record Page(String path, String marker) {
        }
        for (Page page : new Page[] {
                new Page("/docs/api-guide.html", "<redoc"),
                new Page("/docs/api-test.html", "swagger-ui")}) {

            ResponseEntity<String> response = get(page.path());
            assertThat(response.getStatusCode()).as(page.path()).isEqualTo(HttpStatus.OK);
            assertThat(response.getHeaders().getContentType())
                    .as(page.path() + " Content-Type")
                    .isNotNull()
                    .satisfies(type -> assertThat(type.includes(MediaType.TEXT_HTML)).isTrue());
            assertThat(response.getBody())
                    .as(page.path() + " 이 로그인 페이지가 아니라 실제 문서 페이지인지")
                    .contains(page.marker());
        }
    }

    @Test
    @DisplayName("문서 페이지가 참조하는 스펙이 실제로 서빙된다 — 페이지만 200 이면 빈 화면이 된다")
    void specReferencedByPagesIsServed() {
        assertThat(get("/docs/api-guide.html").getBody()).contains(SPEC_PATH_IN_PAGE);
        assertThat(get("/docs/api-test.html").getBody()).contains(SPEC_PATH_IN_PAGE);

        assertThat(get("/docs/openapi3.json").getStatusCode())
                .as("문서 페이지가 참조하는 openapi3.json 이 서빙되지 않으면 문서가 죽은 것이다")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("스펙이 유효한 JSON 이고 설계 §4.1 의 엔드포인트 4종을 빠짐없이 담고 있다")
    void specIsValidAndComplete() {
        // 파싱이 실패하면 예외로 테스트가 깨진다. 복사 단계에 문자열 치환 filter 를 걸면
        // description 의 개행이 raw 제어문자가 되어 여기서 잡힌다.
        JsonNode paths = objectMapper.readTree(get("/docs/openapi3.json").getBody()).path("paths");

        // 엔드포인트를 추가하면 이 테스트가 깨지는데, 그것이 의도다 — RestDocs 테스트를
        // 쓰지 않으면 문서에서 조용히 누락되기 때문이다. 깨졌을 때 고칠 것은 개수가 아니라,
        // 해당 엔드포인트의 RestDocs 테스트를 먼저 쓰는 것이다.
        assertThat(paths.size()).as("Design §4.1 의 엔드포인트 수").isEqualTo(DOCUMENTED_ENDPOINT_COUNT);

        for (String path : new String[] {
                "/v1/auth/login", "/v1/auth/reissue", "/v1/auth/logout", "/v1/users/me"}) {
            assertThat(paths.has(path)).as(path).isTrue();
        }
    }
}
