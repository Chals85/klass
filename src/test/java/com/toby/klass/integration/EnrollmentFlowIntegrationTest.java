package com.toby.klass.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.toby.klass.user.adapter.out.persistence.UserJpaRepository;
import com.toby.klass.user.domain.Role;
import com.toby.klass.user.domain.User;
import com.toby.klass.waitlist.domain.Waitlist;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 수강신청 전 흐름 통합 검증 (L4).
 *
 * <h2>여기서만 검증할 수 있는 것</h2>
 * <ol>
 *   <li><b>동시성</b> — 잔여 1석에 100건이 동시에 들어올 때 정확히 1건만 성공하는가.
 *       단위·서비스 테스트는 단일 스레드라 이것을 볼 수 없다</li>
 *   <li><b>권한 게이트</b> — L3 는 필터를 끈 슬라이스라 {@code SecurityConfig} 규칙이
 *       적용되지 않는다. 통과해도 아무것도 증명하지 못한다</li>
 *   <li><b>정합성</b> — 카운터가 실제 좌석 점유 행 수와 어긋나지 않는가. 전 시나리오를
 *       수행한 뒤 마지막에 확인한다 (ERD 정본 §5.1)</li>
 * </ol>
 *
 * <h2>시각을 조작하지 않고 시간 조건을 만든다</h2>
 * {@code Clock} 이 {@code systemDefaultZone()} 이라 테스트에서 시간을 옮길 수 없다.
 * 대신 <b>데이터로 조건을 만든다</b> — 취소 기간 0일이면 확정 직후의 취소가 이미 기간
 * 초과이고, 종료일이 과거인 강의는 그 자체로 "끝난 강의"다. 시계 조작보다 실제 사용자
 * 경험에 가깝다.
 *
 * <p>Design Ref: enrollment-management §9.5, ERD 정본 §8
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("수강신청 통합 흐름")
class EnrollmentFlowIntegrationTest {

    /** 기본 시딩 계정. {@code ROLE_USER} 만 갖는다. */
    private static final String USER = "chals";

    /** 기본 시딩 계정. {@code ROLE_USER} + {@code ROLE_CREATOR}. */
    private static final String CREATOR = "creator";

    /** 이 테스트가 만드는 두 번째 크리에이터. 소유권 검증에 필요하다. */
    private static final String OTHER_CREATOR = "creator2";

    private static final String PASSWORD = "test";

    /** 동시 신청 테스트의 요청 수. */
    private static final int CONCURRENT_REQUESTS = 100;

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
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Clock clock;

    @BeforeEach
    void setUp() {
        restTemplate = new TestRestTemplate();
        ensureUser(OTHER_CREATOR, Role.ROLE_USER, Role.ROLE_CREATOR);
    }

    private void ensureUser(String username, Role... roles) {
        if (userJpaRepository.findByUsername(username).isEmpty()) {
            userJpaRepository.save(User.register(username, passwordEncoder.encode(PASSWORD),
                    Set.of(roles), LocalDateTime.now(clock)));
        }
    }

    // ── 요청 헬퍼 ────────────────────────────────────────────────────────────

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

    private String tokenOf(String username) {
        ResponseEntity<String> response = exchange(HttpMethod.POST, "/v1/auth/login", """
                {"username":"%s","password":"%s"}""".formatted(username, PASSWORD), null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return json(response).path("data").path("accessToken").asString();
    }

    // ── 강의 준비 ────────────────────────────────────────────────────────────

    /**
     * {@code OPEN} 강의를 만든다.
     *
     * @param cancellationPeriodDays 취소 가능 기간. <b>0 을 주면 확정 직후의 취소가 이미
     *                               기간 초과</b>가 되어 시계 조작 없이 그 경로를 검증할 수 있다
     * @param endsOn                 종료일. <b>과거 날짜를 주면 "끝난 강의"</b>가 된다.
     *                               {@code ck_klass_period} 는 {@code endsOn >= startsOn} 만
     *                               요구하므로 과거 기간도 만들 수 있다
     */
    private long openKlass(String creatorToken, String title, int capacity,
                           int cancellationPeriodDays, LocalDate startsOn, LocalDate endsOn) {
        ResponseEntity<String> created = exchange(HttpMethod.POST, "/v1/klasses", """
                {
                  "title": "%s",
                  "description": "%s 의 내용",
                  "price": 50000,
                  "capacity": %d,
                  "startsOn": "%s",
                  "endsOn": "%s",
                  "cancellationPeriodDays": %d
                }""".formatted(title, title, capacity, startsOn, endsOn,
                cancellationPeriodDays), creatorToken);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        long klassId = json(created).path("data").path("id").asLong();
        assertThat(changeStatus(creatorToken, klassId, "OPEN").getStatusCode())
                .isEqualTo(HttpStatus.OK);
        return klassId;
    }

    /** 미래 기간의 정상 {@code OPEN} 강의. 취소 기간 7일. */
    private long openKlass(String creatorToken, String title, int capacity) {
        return openKlass(creatorToken, title, capacity, 7,
                LocalDate.now(clock).plusMonths(1), LocalDate.now(clock).plusMonths(3));
    }

    private ResponseEntity<String> changeStatus(String token, long klassId, String status) {
        return exchange(HttpMethod.PATCH, "/v1/klasses/" + klassId + "/status",
                """
                {"status":"%s"}""".formatted(status), token);
    }

    private int enrollmentCountOf(long klassId) {
        return jdbcTemplate.queryForObject(
                "select enrollment_count from klass where id = ?", Integer.class, klassId);
    }

    // ── 신청·확정·취소 헬퍼 ─────────────────────────────────────────────────

    private ResponseEntity<String> apply(String token, long klassId) {
        return exchange(HttpMethod.POST, "/v1/klasses/" + klassId + "/enrollments", null, token);
    }

    private long applyOk(String token, long klassId) {
        ResponseEntity<String> response = apply(token, klassId);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return json(response).path("data").path("id").asLong();
    }

    private ResponseEntity<String> confirm(String token, long enrollmentId) {
        return exchange(HttpMethod.POST, "/v1/enrollments/" + enrollmentId + "/confirm",
                null, token);
    }

    private ResponseEntity<String> cancel(String token, long enrollmentId) {
        return exchange(HttpMethod.POST, "/v1/enrollments/" + enrollmentId + "/cancel",
                null, token);
    }

    private ResponseEntity<String> registerWaitlist(String token, long klassId) {
        return exchange(HttpMethod.POST, "/v1/klasses/" + klassId + "/waitlists", null, token);
    }

    private ResponseEntity<String> giveUpWaitlist(String token, long waitlistId) {
        return exchange(HttpMethod.POST, "/v1/waitlists/" + waitlistId + "/cancel", null, token);
    }

    // ── ① 정상 흐름 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("#34 신청 → 결제 확정 → 취소. 좌석이 잡히고 반납된다")
    void applyConfirmCancel() {
        String creator = tokenOf(CREATOR);
        String student = tokenOf(USER);
        long klassId = openKlass(creator, "정상흐름", 10);

        long enrollmentId = applyOk(student, klassId);
        assertThat(enrollmentCountOf(klassId)).as("신청 즉시 좌석을 점유한다").isEqualTo(1);

        ResponseEntity<String> confirmed = confirm(student, enrollmentId);
        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(confirmed).path("data").path("status").asString())
                .isEqualTo("CONFIRMED");
        assertThat(json(confirmed).path("data").path("expiresAt").isNull())
                .as("확정되면 결제 기한이 사라진다 — ck_enrollment_pending 이 강제한다")
                .isTrue();
        assertThat(enrollmentCountOf(klassId))
                .as("확정은 좌석 수를 바꾸지 않는다 — PENDING 이 이미 점유했다")
                .isEqualTo(1);

        ResponseEntity<String> cancelled = cancel(student, enrollmentId);
        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(cancelled).path("data").path("status").asString())
                .isEqualTo("CANCELLED");
        assertThat(json(cancelled).path("data").path("isCancellable").asBoolean())
                .as("취소된 신청은 더 취소할 수 없다")
                .isFalse();
        assertThat(enrollmentCountOf(klassId)).as("좌석이 반납된다").isZero();
    }

    @Test
    @DisplayName("#4 취소 후 같은 강의에 재신청할 수 있다 — 부분 유니크의 존재 이유")
    void reappliesAfterCancel() {
        String creator = tokenOf(CREATOR);
        String student = tokenOf(USER);
        long klassId = openKlass(creator, "재신청", 10);

        long first = applyOk(student, klassId);
        assertThat(cancel(student, first).getStatusCode()).isEqualTo(HttpStatus.OK);

        long second = applyOk(student, klassId);

        assertThat(second)
                .as("CANCELLED 는 active_user_key 가 NULL 이라 유니크에 걸리지 않는다")
                .isNotEqualTo(first);
        assertThat(enrollmentCountOf(klassId)).isEqualTo(1);
    }

    // ── ② 동시성 — 이 사이클의 SUCCESS 기준 ─────────────────────────────────

    @Test
    @DisplayName("#1 잔여 1석에 100건 동시 신청 → 정확히 1건만 성공한다")
    void onlyOneWinsTheLastSeat() throws Exception {
        String creator = tokenOf(CREATOR);
        long klassId = openKlass(creator, "동시성", 1);

        // 사용자마다 토큰이 필요하다 — 같은 사용자로 100번 보내면 중복 신청 차단에 걸려
        // 정원 경합이 아니라 다른 것을 검증하게 된다
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            String username = "racer" + i;
            ensureUser(username, Role.ROLE_USER);
            tokens.add(tokenOf(username));
        }

        AtomicInteger created = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(CONCURRENT_REQUESTS);
        ExecutorService pool = Executors.newFixedThreadPool(32);
        try {
            for (String token : tokens) {
                pool.submit(() -> {
                    try {
                        start.await();
                        HttpStatus status = (HttpStatus) apply(token, klassId).getStatusCode();
                        if (status == HttpStatus.CREATED) {
                            created.incrementAndGet();
                        } else if (status == HttpStatus.CONFLICT) {
                            conflict.incrementAndGet();
                        } else {
                            other.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();                     // 동시 발사
            assertThat(done.await(60, TimeUnit.SECONDS))
                    .as("60초 안에 끝나지 않으면 락이 풀리지 않은 것이다")
                    .isTrue();
        } finally {
            pool.shutdownNow();
        }

        // 3중 단정 — 성공 건수만 세면 카운터 버그를 놓친다 (Design §9.5)
        assertThat(created.get())
                .as("배타 락이 마지막 자리 경합을 직렬화한다")
                .isEqualTo(1);
        assertThat(other.get())
                .as("정원 초과(409) 외의 실패가 있으면 락이 아닌 다른 문제다")
                .isZero();
        assertThat(conflict.get()).isEqualTo(CONCURRENT_REQUESTS - 1);

        assertThat(enrollmentCountOf(klassId))
                .as("카운터가 정원과 같아야 한다")
                .isEqualTo(1);
        assertThat(activeEnrollmentCountOf(klassId))
                .as("실제 활성 행 수까지 봐야 카운터 버그가 드러난다")
                .isEqualTo(1);
    }

    private int activeEnrollmentCountOf(long klassId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from enrollment "
                        + "where klass_id = ? and status in ('PENDING','CONFIRMED')",
                Integer.class, klassId);
    }

    /**
     * 정본 시나리오 #6 — <b>취소 경로의 락 순서를 동시 부하에서 검증한다.</b>
     *
     * <h2>왜 신청 100건 테스트로는 부족한가</h2>
     * 그쪽은 {@code klass} 락 하나만 잡는 단순한 경로다. 취소는 <b>세 자원을 순서대로</b>
     * 잡는다 — 무락 {@code klass_id} 조회 → {@code klass} → {@code enrollment} → 승격의
     * {@code waitlist}. 순서가 어긋나면 데드락이 나는데(Plan R-02), 단일 스레드 테스트로는
     * 그 순서가 지켜지는지 알 수 없다.
     *
     * <p>대기자가 1명뿐인데 취소가 2건 동시에 들어오면 <b>같은 대기자를 두 번 승격</b>할
     * 위험이 생긴다. 그것을 막는 것은 {@code klass} 락과 {@link Waitlist#promote} 의 상태
     * 재확인 두 겹이다.
     *
     * <h2>기대 결과</h2>
     * 취소 2건은 <b>둘 다 성공</b>한다 — 서로 다른 신청이므로 경합 대상이 아니다.
     * 경합하는 것은 <b>승격</b>이며 대기자가 1명이므로 1건만 승격돼야 한다.
     * 좌석은 2 → (반납 2, 승격 1) → 1 이 된다.
     */
    @Test
    @DisplayName("#6 취소 2건 동시 발생, 대기자 1명 → 승격은 1건만")
    void concurrentCancelsPromoteOnlyOnce() throws Exception {
        String creator = tokenOf(CREATOR);
        long klassId = openKlass(creator, "동시취소", 2);

        ensureUser("canceller1", Role.ROLE_USER);
        ensureUser("canceller2", Role.ROLE_USER);
        String first = tokenOf("canceller1");
        String second = tokenOf("canceller2");
        long firstEnrollment = applyOk(first, klassId);
        long secondEnrollment = applyOk(second, klassId);
        assertThat(enrollmentCountOf(klassId)).isEqualTo(2);

        ensureUser("soleWaiter", Role.ROLE_USER);
        String waiter = tokenOf("soleWaiter");
        ResponseEntity<String> enqueued = registerWaitlist(waiter, klassId);
        assertThat(enqueued.getStatusCode())
                .as("정원이 찬 상태여야 대기 등록이 가능하다")
                .isEqualTo(HttpStatus.CREATED);
        long waitlistId = json(enqueued).path("data").path("id").asLong();

        AtomicInteger cancelled = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (Object[] target : new Object[][] {
                    {first, firstEnrollment}, {second, secondEnrollment}}) {
                pool.submit(() -> {
                    try {
                        start.await();
                        if (cancel((String) target[0], (Long) target[1])
                                .getStatusCode() == HttpStatus.OK) {
                            cancelled.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS))
                    .as("30초 안에 끝나지 않으면 데드락이다 — 락 순서가 어긋난 것")
                    .isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(cancelled.get())
                .as("서로 다른 신청이라 둘 다 취소돼야 한다. 경합 대상은 승격이다")
                .isEqualTo(2);

        // 3중 단정 — 승격이 두 번 일어나면 카운터와 행 수가 함께 어긋난다
        assertThat(promotedCountOf(klassId))
                .as("대기자가 1명이므로 승격은 1건뿐이어야 한다. "
                        + "두 번 승격되면 uq_enrollment_active 에 걸리거나 좌석이 초과된다")
                .isEqualTo(1);
        assertThat(enrollmentCountOf(klassId))
                .as("2석 점유 → 반납 2 + 승격 1 = 1")
                .isEqualTo(1);
        assertThat(activeEnrollmentCountOf(klassId))
                .as("카운터만 보면 승격 중복을 놓친다")
                .isEqualTo(1);

        assertThat(jdbcTemplate.queryForObject(
                "select status from waitlist where id = ?", String.class, waitlistId))
                .isEqualTo("PROMOTED");
    }

    private int promotedCountOf(long klassId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from enrollment where klass_id = ? and source = 'WAITLIST'",
                Integer.class, klassId);
    }

    // ── ③ 정원과 중복 ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("정원·중복·상태")
    class Guards {

        @Test
        @DisplayName("#2 정원이 찬 강의에 신청하면 409 이고 카운터가 변하지 않는다")
        void rejectsWhenFull() {
            String creator = tokenOf(CREATOR);
            long klassId = openKlass(creator, "만석", 1);
            applyOk(tokenOf(USER), klassId);

            ensureUser("late", Role.ROLE_USER);
            ResponseEntity<String> response = apply(tokenOf("late"), klassId);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(errorCode(response)).isEqualTo("KLASS_CAPACITY_FULL");
            assertThat(enrollmentCountOf(klassId)).isEqualTo(1);
        }

        @Test
        @DisplayName("#3 같은 사용자가 두 번 신청하면 409 DUPLICATE_ENROLLMENT")
        void rejectsDuplicate() {
            String creator = tokenOf(CREATOR);
            String student = tokenOf(USER);
            long klassId = openKlass(creator, "중복", 10);
            applyOk(student, klassId);

            ResponseEntity<String> second = apply(student, klassId);

            assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(errorCode(second)).isEqualTo("DUPLICATE_ENROLLMENT");
            assertThat(enrollmentCountOf(klassId)).isEqualTo(1);
        }

        @Test
        @DisplayName("#22 DRAFT 강의에 신청하면 409 KLASS_NOT_OPEN — 404 로 감추지 않는다")
        void rejectsDraft() {
            String creator = tokenOf(CREATOR);
            ResponseEntity<String> draft = exchange(HttpMethod.POST, "/v1/klasses", """
                    {
                      "title": "초안", "description": "내용", "price": 10000, "capacity": 10,
                      "startsOn": "2027-01-01", "endsOn": "2027-03-01",
                      "cancellationPeriodDays": 7
                    }""", creator);
            long klassId = json(draft).path("data").path("id").asLong();

            ResponseEntity<String> response = apply(tokenOf(USER), klassId);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(errorCode(response))
                    .as("이 경로는 인증 필수라 존재를 숨겨 얻는 것이 없다")
                    .isEqualTo("KLASS_NOT_OPEN");
        }

        @Test
        @DisplayName("#11 CLOSED 강의에 신청하면 409")
        void rejectsClosed() {
            String creator = tokenOf(CREATOR);
            long klassId = openKlass(creator, "마감", 10);
            assertThat(changeStatus(creator, klassId, "CLOSED").getStatusCode())
                    .isEqualTo(HttpStatus.OK);

            ResponseEntity<String> response = apply(tokenOf(USER), klassId);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(errorCode(response)).isEqualTo("KLASS_NOT_OPEN");
        }

        @Test
        @DisplayName("없는 강의에 신청하면 404")
        void rejectsMissingKlass() {
            ResponseEntity<String> response = apply(tokenOf(USER), 999_999L);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(errorCode(response)).isEqualTo("KLASS_NOT_FOUND");
        }
    }

    // ── ④ 개설자 차단 (FR-19, 정본에 없는 신규 요건) ────────────────────────

    @Nested
    @DisplayName("개설자 본인 차단 (FR-19)")
    class SelfEnrollment {

        @Test
        @DisplayName("N-1 개설자가 자기 강의에 신청하면 403 SELF_ENROLLMENT_FORBIDDEN")
        void ownerCannotApply() {
            String creator = tokenOf(CREATOR);
            long klassId = openKlass(creator, "본인신청", 10);

            ResponseEntity<String> response = apply(creator, klassId);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(errorCode(response)).isEqualTo("SELF_ENROLLMENT_FORBIDDEN");
            assertThat(enrollmentCountOf(klassId)).isZero();
        }

        @Test
        @DisplayName("N-2 개설자가 자기 강의 대기열에 등록하면 403 — 대기열이 우회로가 되면 안 된다")
        void ownerCannotEnqueue() {
            String creator = tokenOf(CREATOR);
            long klassId = openKlass(creator, "본인대기", 1);
            applyOk(tokenOf(USER), klassId);       // 정원을 채워 대기 등록 조건을 만든다

            ResponseEntity<String> response = registerWaitlist(creator, klassId);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(errorCode(response))
                    .as("신청만 막으면 자리가 나는 순간 승격으로 좌석을 얻는 우회로가 남는다")
                    .isEqualTo("SELF_ENROLLMENT_FORBIDDEN");
        }

        @Test
        @DisplayName("다른 크리에이터의 강의에는 신청할 수 있다 — 차단은 본인 강의에만")
        void otherCreatorCanApply() {
            String creator = tokenOf(CREATOR);
            long klassId = openKlass(creator, "타인크리에이터", 10);

            assertThat(apply(tokenOf(OTHER_CREATOR), klassId).getStatusCode())
                    .isEqualTo(HttpStatus.CREATED);
        }
    }

    // ── ⑤ 취소 조건 ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("취소 조건 2관문")
    class CancellationGates {

        @Test
        @DisplayName("#9 취소 가능 기간 0일이면 확정 후 취소가 409 — 카운터 불변")
        void rejectsAfterPeriod() {
            String creator = tokenOf(CREATOR);
            String student = tokenOf(USER);
            // 기간 0 이면 확정 시각을 지나는 순간 이미 초과다. 시계를 옮기지 않고 만든 조건
            long klassId = openKlass(creator, "기간0", 10, 0,
                    LocalDate.now(clock).plusMonths(1), LocalDate.now(clock).plusMonths(3));

            long enrollmentId = applyOk(student, klassId);
            assertThat(confirm(student, enrollmentId).getStatusCode()).isEqualTo(HttpStatus.OK);

            ResponseEntity<String> response = cancel(student, enrollmentId);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(errorCode(response)).isEqualTo("CANCELLATION_PERIOD_EXPIRED");
            assertThat(enrollmentCountOf(klassId))
                    .as("거부됐으면 좌석이 반납되지 않아야 한다")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("N-3 종료된 강의는 기간이 남아 있어도 취소 불가 — 409 KLASS_ALREADY_FINISHED")
        void rejectsFinishedKlass() {
            String creator = tokenOf(CREATOR);
            String student = tokenOf(USER);
            // 종료일이 과거인 강의. ck_klass_period 는 endsOn >= startsOn 만 요구한다
            long klassId = openKlass(creator, "종료됨", 10, 365,
                    LocalDate.now(clock).minusMonths(3), LocalDate.now(clock).minusMonths(1));

            long enrollmentId = applyOk(student, klassId);
            assertThat(confirm(student, enrollmentId).getStatusCode()).isEqualTo(HttpStatus.OK);

            ResponseEntity<String> response = cancel(student, enrollmentId);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(errorCode(response))
                    .as("취소 기간이 365일 남았지만 강의가 끝났다 (FR-20)")
                    .isEqualTo("KLASS_ALREADY_FINISHED");
        }

        @Test
        @DisplayName("PENDING 은 두 관문을 면제받는다 — 기간 0, 종료된 강의여도 취소된다")
        void pendingIsExempt() {
            String creator = tokenOf(CREATOR);
            String student = tokenOf(USER);
            long klassId = openKlass(creator, "면제", 10, 0,
                    LocalDate.now(clock).minusMonths(3), LocalDate.now(clock).minusMonths(1));

            long enrollmentId = applyOk(student, klassId);

            assertThat(cancel(student, enrollmentId).getStatusCode())
                    .as("결제 전이라 환불할 것이 없다. 막으면 좌석만 영구히 묶인다")
                    .isEqualTo(HttpStatus.OK);
            assertThat(enrollmentCountOf(klassId)).isZero();
        }

        @Test
        @DisplayName("#20 취소된 신청을 다시 취소하면 409")
        void rejectsDoubleCancel() {
            String creator = tokenOf(CREATOR);
            String student = tokenOf(USER);
            long klassId = openKlass(creator, "이중취소", 10);

            long enrollmentId = applyOk(student, klassId);
            assertThat(cancel(student, enrollmentId).getStatusCode()).isEqualTo(HttpStatus.OK);

            ResponseEntity<String> second = cancel(student, enrollmentId);

            assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(errorCode(second)).isEqualTo("INVALID_ENROLLMENT_STATUS_TRANSITION");
            assertThat(enrollmentCountOf(klassId))
                    .as("두 번 반납되면 카운터가 음수가 되고 500 이 난다")
                    .isZero();
        }
    }

    // ── ⑥ 결제 확정 ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("결제 확정")
    class Confirmation {

        @Test
        @DisplayName("#10 CLOSED 로 전환된 뒤에도 기존 PENDING 은 확정할 수 있다")
        void confirmsAfterKlassClosed() {
            String creator = tokenOf(CREATOR);
            String student = tokenOf(USER);
            long klassId = openKlass(creator, "마감후확정", 10);
            long enrollmentId = applyOk(student, klassId);

            assertThat(changeStatus(creator, klassId, "CLOSED").getStatusCode())
                    .isEqualTo(HttpStatus.OK);

            assertThat(confirm(student, enrollmentId).getStatusCode())
                    .as("마감이 막는 것은 신규 신청이지 이미 한 신청의 후속 처리가 아니다")
                    .isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("#20 확정된 신청을 다시 확정하면 409")
        void rejectsDoubleConfirm() {
            String creator = tokenOf(CREATOR);
            String student = tokenOf(USER);
            long klassId = openKlass(creator, "이중확정", 10);
            long enrollmentId = applyOk(student, klassId);

            assertThat(confirm(student, enrollmentId).getStatusCode()).isEqualTo(HttpStatus.OK);
            ResponseEntity<String> second = confirm(student, enrollmentId);

            assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(errorCode(second)).isEqualTo("INVALID_ENROLLMENT_STATUS_TRANSITION");
        }

        @Test
        @DisplayName("#33 타인의 신청을 확정하면 403")
        void rejectsOtherUsersEnrollment() {
            String creator = tokenOf(CREATOR);
            long klassId = openKlass(creator, "타인확정", 10);
            long enrollmentId = applyOk(tokenOf(USER), klassId);

            ResponseEntity<String> response = confirm(tokenOf(OTHER_CREATOR), enrollmentId);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(errorCode(response)).isEqualTo("NOT_ENROLLMENT_OWNER");
        }

        @Test
        @DisplayName("#32 타인의 신청을 취소하면 403 이고 좌석이 반납되지 않는다")
        void rejectsOtherUsersCancel() {
            String creator = tokenOf(CREATOR);
            long klassId = openKlass(creator, "타인취소", 10);
            long enrollmentId = applyOk(tokenOf(USER), klassId);

            ResponseEntity<String> response = cancel(tokenOf(OTHER_CREATOR), enrollmentId);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(enrollmentCountOf(klassId)).isEqualTo(1);
        }
    }

    // ── ⑦ 대기열 ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("대기열")
    class Waitlist {

        @Test
        @DisplayName("#5 취소가 대기자를 승격한다 — 좌석 순변화 0")
        void cancelPromotesFirstWaiter() {
            String creator = tokenOf(CREATOR);
            long klassId = openKlass(creator, "승격", 1);
            long seated = applyOk(tokenOf(USER), klassId);

            ensureUser("waiter1", Role.ROLE_USER);
            ensureUser("waiter2", Role.ROLE_USER);
            long firstWaitlist = registerWaitlistOk(tokenOf("waiter1"), klassId);
            registerWaitlistOk(tokenOf("waiter2"), klassId);

            assertThat(cancel(tokenOf(USER), seated).getStatusCode()).isEqualTo(HttpStatus.OK);

            assertThat(enrollmentCountOf(klassId))
                    .as("반납(-1)과 승격(+1)이 상쇄돼야 한다 — 틈이 생기면 신규 신청자가 채간다")
                    .isEqualTo(1);
            assertThat(waitlistStatusOf(firstWaitlist)).isEqualTo("PROMOTED");
            assertThat(promotedEnrollmentCountOf(klassId))
                    .as("승격으로 생긴 신청은 source 가 WAITLIST 다")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("#12 CLOSED 강의의 취소는 승격하지 않는다 — 좌석이 빈 채로 남는다")
        void closedKlassDoesNotPromote() {
            String creator = tokenOf(CREATOR);
            long klassId = openKlass(creator, "마감승격없음", 1);
            long seated = applyOk(tokenOf(USER), klassId);

            ensureUser("waiter3", Role.ROLE_USER);
            long waitlistId = registerWaitlistOk(tokenOf("waiter3"), klassId);

            assertThat(changeStatus(creator, klassId, "CLOSED").getStatusCode())
                    .isEqualTo(HttpStatus.OK);
            assertThat(cancel(tokenOf(USER), seated).getStatusCode()).isEqualTo(HttpStatus.OK);

            assertThat(enrollmentCountOf(klassId))
                    .as("마감 후 반납된 좌석은 빈 채로 남는다 — 명단 확정을 위한 의도된 선택")
                    .isZero();
            assertThat(waitlistStatusOf(waitlistId))
                    .as("#38 마감 시 잔여 대기자는 이미 정리됐다")
                    .isEqualTo("CANCELLED");
        }

        @Test
        @DisplayName("#38 CLOSED 전환이 잔여 대기자를 전부 정리한다 — 유령 행이 남지 않는다")
        void closingCancelsAllWaiting() {
            String creator = tokenOf(CREATOR);
            long klassId = openKlass(creator, "대기정리", 1);
            applyOk(tokenOf(USER), klassId);

            List<Long> waitlistIds = new ArrayList<>();
            for (int i = 4; i <= 6; i++) {
                ensureUser("waiter" + i, Role.ROLE_USER);
                waitlistIds.add(registerWaitlistOk(tokenOf("waiter" + i), klassId));
            }

            assertThat(changeStatus(creator, klassId, "CLOSED").getStatusCode())
                    .isEqualTo(HttpStatus.OK);

            assertThat(waitlistIds).allSatisfy(id -> assertThat(waitlistStatusOf(id))
                    .as("CLOSED 에서는 승격이 중단되고 CLOSED → OPEN 도 봉쇄돼 있어, "
                            + "남겨두면 영구히 승격되지 않는다")
                    .isEqualTo("CANCELLED"));
        }

        @Test
        @DisplayName("#40 자리가 있으면 대기 등록이 409 — 영구히 기다리게 되는 것을 막는다")
        void rejectsWhenSeatAvailable() {
            String creator = tokenOf(CREATOR);
            long klassId = openKlass(creator, "자리있음", 10);

            ResponseEntity<String> response = registerWaitlist(tokenOf(USER), klassId);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(errorCode(response)).isEqualTo("WAITLIST_SEAT_AVAILABLE");
        }

        @Test
        @DisplayName("#23 이미 신청한 사용자는 대기 등록이 409 — 순번을 차지하면 안 된다")
        void rejectsAlreadyEnrolled() {
            String creator = tokenOf(CREATOR);
            String student = tokenOf(USER);
            long klassId = openKlass(creator, "이미신청", 1);
            applyOk(student, klassId);

            ResponseEntity<String> response = registerWaitlist(student, klassId);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(errorCode(response)).isEqualTo("DUPLICATE_ENROLLMENT");
        }

        @Test
        @DisplayName("#35 #36 대기 포기 후 같은 강의에 재대기할 수 있다")
        void reEnqueuesAfterGivingUp() {
            String creator = tokenOf(CREATOR);
            long klassId = openKlass(creator, "재대기", 1);
            applyOk(tokenOf(USER), klassId);

            ensureUser("waiter7", Role.ROLE_USER);
            String waiter = tokenOf("waiter7");
            long first = registerWaitlistOk(waiter, klassId);

            assertThat(giveUpWaitlist(waiter, first).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(waitlistStatusOf(first)).isEqualTo("CANCELLED");

            long second = registerWaitlistOk(waiter, klassId);

            assertThat(second).isNotEqualTo(first);
            assertThat(positionOf(second))
                    .as("포기한 순번은 gap 으로 남고 재사용하지 않는다")
                    .isGreaterThan(positionOf(first));
        }

        @Test
        @DisplayName("#28 승격된 대기를 포기하면 409 — 배정된 좌석이 주인 없이 남으면 안 된다")
        void rejectsGivingUpPromoted() {
            String creator = tokenOf(CREATOR);
            long klassId = openKlass(creator, "승격후포기", 1);
            long seated = applyOk(tokenOf(USER), klassId);

            ensureUser("waiter8", Role.ROLE_USER);
            String waiter = tokenOf("waiter8");
            long waitlistId = registerWaitlistOk(waiter, klassId);

            assertThat(cancel(tokenOf(USER), seated).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(waitlistStatusOf(waitlistId)).isEqualTo("PROMOTED");

            ResponseEntity<String> response = giveUpWaitlist(waiter, waitlistId);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(errorCode(response)).isEqualTo("WAITLIST_NOT_WAITING");
        }

        @Test
        @DisplayName("#21 비활성 계정 대기자는 건너뛰고 다음 순번을 승격한다")
        void skipsDisabledWaiter() {
            String creator = tokenOf(CREATOR);
            long klassId = openKlass(creator, "부적격건너뛰기", 1);
            long seated = applyOk(tokenOf(USER), klassId);

            ensureUser("disabled1", Role.ROLE_USER);
            ensureUser("waiter9", Role.ROLE_USER);
            long disabledWaitlist = registerWaitlistOk(tokenOf("disabled1"), klassId);
            long eligibleWaitlist = registerWaitlistOk(tokenOf("waiter9"), klassId);

            // 대기 등록 후 계정을 비활성화한다 — 승격 시점에 부적격이 되는 상황
            jdbcTemplate.update("update users set is_enabled = false where username = 'disabled1'");

            assertThat(cancel(tokenOf(USER), seated).getStatusCode()).isEqualTo(HttpStatus.OK);

            assertThat(waitlistStatusOf(disabledWaitlist))
                    .as("부적격 대기는 CANCELLED 로 정리된다")
                    .isEqualTo("CANCELLED");
            assertThat(waitlistStatusOf(eligibleWaitlist))
                    .as("다음 적격 대기자가 승격돼야 한다 — 순변화 0 이 유지된다")
                    .isEqualTo("PROMOTED");
            assertThat(enrollmentCountOf(klassId)).isEqualTo(1);
        }

        private long registerWaitlistOk(String token, long klassId) {
            ResponseEntity<String> response = registerWaitlist(token, klassId);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            return json(response).path("data").path("id").asLong();
        }

        private String waitlistStatusOf(long waitlistId) {
            return jdbcTemplate.queryForObject(
                    "select status from waitlist where id = ?", String.class, waitlistId);
        }

        private int positionOf(long waitlistId) {
            return jdbcTemplate.queryForObject(
                    "select position from waitlist where id = ?", Integer.class, waitlistId);
        }

        private int promotedEnrollmentCountOf(long klassId) {
            return jdbcTemplate.queryForObject(
                    "select count(*) from enrollment where klass_id = ? and source = 'WAITLIST'",
                    Integer.class, klassId);
        }
    }

    // ── ⑧ 권한 게이트 (L3 로는 검증 불가) ───────────────────────────────────

    @Nested
    @DisplayName("권한 게이트")
    class Authorization {

        @Test
        @DisplayName("#17 다른 크리에이터의 수강생 명단은 403 — 권한만으로는 막히지 않는 지점")
        void otherCreatorCannotSeeRoster() {
            String creator = tokenOf(CREATOR);
            long klassId = openKlass(creator, "명단소유권", 10);

            ResponseEntity<String> response = exchange(HttpMethod.GET,
                    "/v1/klasses/" + klassId + "/enrollments", null, tokenOf(OTHER_CREATOR));

            assertThat(response.getStatusCode())
                    .as("hasRole 은 통과한다. 소유권 검사가 없으면 남의 명단이 새어나간다")
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(errorCode(response)).isEqualTo("NOT_KLASS_OWNER");
        }

        @Test
        @DisplayName("ROLE_USER 로는 수강생 명단을 볼 수 없다 — hasRole 이 실제로 적용되는가")
        void plainUserCannotSeeRoster() {
            String creator = tokenOf(CREATOR);
            long klassId = openKlass(creator, "명단권한", 10);

            ResponseEntity<String> response = exchange(HttpMethod.GET,
                    "/v1/klasses/" + klassId + "/enrollments", null, tokenOf(USER));

            assertThat(response.getStatusCode())
                    .as("SecurityConfig 에 이 경로를 명시하지 않으면 authenticated() 로 "
                            + "떨어져 200 이 나온다")
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("같은 경로의 POST(신청)는 ROLE_USER 도 할 수 있다 — 메서드를 함께 지정한 이유")
        void plainUserCanApplyOnSamePath() {
            String creator = tokenOf(CREATOR);
            long klassId = openKlass(creator, "같은경로POST", 10);

            assertThat(apply(tokenOf(USER), klassId).getStatusCode())
                    .isEqualTo(HttpStatus.CREATED);
        }

        @Test
        @DisplayName("토큰이 없으면 신청은 401")
        void rejectsAnonymous() {
            String creator = tokenOf(CREATOR);
            long klassId = openKlass(creator, "무인증", 10);

            assertThat(apply(null, klassId).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("타인의 신청 상세는 403")
        void otherUserCannotSeeDetail() {
            String creator = tokenOf(CREATOR);
            long klassId = openKlass(creator, "상세소유권", 10);
            long enrollmentId = applyOk(tokenOf(USER), klassId);

            ResponseEntity<String> response = exchange(HttpMethod.GET,
                    "/v1/enrollments/" + enrollmentId, null, tokenOf(OTHER_CREATOR));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(errorCode(response)).isEqualTo("NOT_ENROLLMENT_OWNER");
        }
    }

    // ── ⑨ 강의 관리와의 연동 ────────────────────────────────────────────────

    @Nested
    @DisplayName("강의 관리 연동")
    class KlassInteraction {

        @Test
        @DisplayName("#19 신청자가 있으면 OPEN → DRAFT 는 409 — 카운터가 처음으로 유효해진 지점")
        void rejectsUnpublishWithEnrollments() {
            String creator = tokenOf(CREATOR);
            long klassId = openKlass(creator, "초안복귀거부", 10);
            applyOk(tokenOf(USER), klassId);

            ResponseEntity<String> response = changeStatus(creator, klassId, "DRAFT");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(errorCode(response))
                    .isEqualTo("INVALID_KLASS_STATUS_TRANSITION");
        }

        @Test
        @DisplayName("#14 정원을 점유 인원보다 작게 줄이려면 409 — DRAFT 에서만 수정 가능하므로 도달 불가")
        void capacityGuardIsUnreachableWhileOpen() {
            String creator = tokenOf(CREATOR);
            long klassId = openKlass(creator, "정원축소", 10);
            applyOk(tokenOf(USER), klassId);

            // OPEN 상태에서는 제목 외 필드가 무시된다 (D-28). capacity 1 을 보내도 200 이고
            // 실제 값은 바뀌지 않는다 — 그래서 CAPACITY_BELOW_ENROLLMENT 에 도달할 수 없다
            ResponseEntity<String> response = exchange(HttpMethod.PUT, "/v1/klasses/" + klassId,
                    """
                    {
                      "title": "정원축소", "description": "내용", "price": 50000, "capacity": 1,
                      "startsOn": "%s", "endsOn": "%s", "cancellationPeriodDays": 7
                    }""".formatted(LocalDate.now(clock).plusMonths(1),
                            LocalDate.now(clock).plusMonths(3)), creator);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(json(response).path("data").path("capacity").asInt())
                    .as("공개된 강의는 제목만 바뀐다. 이 경로가 D-33 의 '도달 불가' 근거다")
                    .isEqualTo(10);
        }

        @Test
        @DisplayName("#37 DRAFT → CLOSED 개설 철회는 신청자가 없어 안전하다")
        void withdrawsDraft() {
            String creator = tokenOf(CREATOR);
            ResponseEntity<String> draft = exchange(HttpMethod.POST, "/v1/klasses", """
                    {
                      "title": "철회", "description": "내용", "price": 10000, "capacity": 10,
                      "startsOn": "2027-01-01", "endsOn": "2027-03-01",
                      "cancellationPeriodDays": 7
                    }""", creator);
            long klassId = json(draft).path("data").path("id").asLong();

            assertThat(changeStatus(creator, klassId, "CLOSED").getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }
    }

    // ── ⑩ 조회 ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("조회")
    class Queries {

        @Test
        @DisplayName("내 신청 목록은 본인 것만 보이고 강의 제목이 함께 온다")
        void listsOwnEnrollments() {
            String creator = tokenOf(CREATOR);
            String student = tokenOf(USER);
            long klassId = openKlass(creator, "목록조회대상", 10);
            applyOk(student, klassId);

            ResponseEntity<String> response = exchange(HttpMethod.GET,
                    "/v1/enrollments/me", null, student);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode items = json(response).path("data").path("items");
            assertThat(items.size()).isPositive();
            assertThat(items.get(0).path("klassTitle").asString())
                    .as("fetch join 이 빠지면 open-in-view: false 라 여기서 터진다")
                    .isNotBlank();
        }

        @Test
        @DisplayName("크리에이터는 자기 강의의 수강생 명단을 본다 — username 이 실린다")
        void ownerSeesRoster() {
            String creator = tokenOf(CREATOR);
            long klassId = openKlass(creator, "명단조회", 10);
            applyOk(tokenOf(USER), klassId);

            ResponseEntity<String> response = exchange(HttpMethod.GET,
                    "/v1/klasses/" + klassId + "/enrollments", null, creator);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(json(response).path("data").path("items").get(0).path("username")
                    .asString())
                    .isEqualTo(USER);
        }

        @Test
        @DisplayName("내 대기 목록으로 waitlistId 를 다시 찾을 수 있다 — 포기 API 의 유일한 경로")
        void listsOwnWaitlists() {
            String creator = tokenOf(CREATOR);
            long klassId = openKlass(creator, "대기목록", 1);
            applyOk(tokenOf(USER), klassId);

            ensureUser("waiter10", Role.ROLE_USER);
            String waiter = tokenOf("waiter10");
            assertThat(registerWaitlist(waiter, klassId).getStatusCode())
                    .isEqualTo(HttpStatus.CREATED);

            ResponseEntity<String> response = exchange(HttpMethod.GET,
                    "/v1/waitlists/me", null, waiter);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(json(response).path("data").path("items").get(0).path("id").asLong())
                    .isPositive();
        }

        @Test
        @DisplayName("size 가 100 을 넘으면 400")
        void rejectsOutOfRangeSize() {
            ResponseEntity<String> response = exchange(HttpMethod.GET,
                    "/v1/enrollments/me?size=101", null, tokenOf(USER));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    // ── ⑪ 정합성 검증 — 반드시 마지막에 실행한다 ───────────────────────────

    /**
     * ERD 정본 §5.1 의 정합성 검증. <b>위 시나리오를 모두 수행한 뒤 돌린다.</b>
     *
     * <p>JUnit 5 는 메서드 순서를 보장하지 않으므로 이 클래스는 <b>같은 인메모리 DB 에
     * 누적된 상태</b>를 검사한다 — 어떤 순서로 실행됐든 마지막에 남은 데이터가 정합적이면
     * 그것으로 충분하다. 순서를 강제하려 {@code @TestMethodOrder} 를 쓰면 테스트 간 결합이
     * 생기고, 한 건이 깨질 때 원인 추적이 어려워진다.
     */
    @Nested
    @DisplayName("정합성 검증 (ERD 정본 §5.1)")
    class Integrity {

        @Test
        @DisplayName("#41 카운터가 실제 좌석 점유 행 수와 어긋난 강의가 없다")
        void counterMatchesActualRows() {
            List<String> drifted = jdbcTemplate.query("""
                    select k.id, k.title, k.enrollment_count, coalesce(e.actual, 0) as actual
                      from klass k
                      left join (
                           select klass_id, count(*) as actual
                             from enrollment
                            where status in ('PENDING','CONFIRMED')
                            group by klass_id) e
                        on e.klass_id = k.id
                     where k.enrollment_count <> coalesce(e.actual, 0)
                    """, (rs, i) -> "klassId=%d '%s' counter=%d actual=%d".formatted(
                            rs.getLong("id"), rs.getString("title"),
                            rs.getInt("enrollment_count"), rs.getInt("actual")));

            assertThat(drifted)
                    .as("카운터가 어긋나면 정원 검사가 무의미해진다. "
                            + "ck_klass_count 는 상한만 보므로 이것이 유일한 전수 검증이다")
                    .isEmpty();
        }

        @Test
        @DisplayName("카운터가 정원을 넘은 강의가 없다 — ck_klass_count 가 실제로 지켜졌는가")
        void counterNeverExceedsCapacity() {
            Integer violations = jdbcTemplate.queryForObject(
                    "select count(*) from klass where enrollment_count > capacity "
                            + "or enrollment_count < 0", Integer.class);

            assertThat(violations).isZero();
        }

        @Test
        @DisplayName("활성 중복 신청이 없다 — uq_enrollment_active 가 실제로 지켜졌는가")
        void noDuplicateActiveEnrollment() {
            Integer duplicates = jdbcTemplate.queryForObject("""
                    select count(*) from (
                        select klass_id, user_id
                          from enrollment
                         where status in ('PENDING','CONFIRMED')
                         group by klass_id, user_id
                        having count(*) > 1)
                    """, Integer.class);

            assertThat(duplicates).isZero();
        }

        /**
         * R-01 의 유일한 완화책 — <b>만료된 좌석을 세어 본다.</b>
         *
         * <p>만료 회수를 구현하지 않기로 했으므로(D-32) 결제하지 않은 신청이 좌석을 영구히
         * 붙잡는다. 0 이어야 하는 값이 아니라 <b>관측해야 하는 값</b>이다 — 이 수가 곧
         * "회수되지 못한 좌석"이며, 완료 보고서에 실제 값을 기록한다.
         */
        @Test
        @DisplayName("만료된 PENDING 을 세는 관측 쿼리가 동작한다 (R-01)")
        void countsExpiredPendingSeats() {
            Integer expired = jdbcTemplate.queryForObject(
                    "select count(*) from enrollment "
                            + "where status = 'PENDING' and expires_at <= current_timestamp",
                    Integer.class);

            assertThat(expired)
                    .as("만료 회수 배치가 없으므로 이 값은 0 이 아닐 수 있다. "
                            + "쿼리가 동작하는지만 확인한다 — 외부 배치가 붙으면 이만큼 회수된다")
                    .isNotNull()
                    .isGreaterThanOrEqualTo(0);
        }

        @Test
        @DisplayName("상태와 타임스탬프가 어긋난 신청이 없다 — CHECK 제약 3종이 지켜졌는가")
        void timestampsMatchStatus() {
            Integer violations = jdbcTemplate.queryForObject("""
                    select count(*) from enrollment
                     where (status = 'PENDING'  and expires_at is null)
                        or (status <> 'PENDING' and expires_at is not null)
                        or (status = 'CONFIRMED' and confirmed_at is null)
                        or (status = 'CANCELLED' and cancelled_at is null)
                    """, Integer.class);

            assertThat(violations)
                    .as("도메인이 expires_at 을 NULL 로 만들지 않으면 CHECK 위반으로 500 이 난다")
                    .isZero();
        }
    }
}
