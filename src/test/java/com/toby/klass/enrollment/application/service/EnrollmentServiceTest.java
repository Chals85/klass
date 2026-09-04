package com.toby.klass.enrollment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

import com.toby.klass.common.domain.error.BusinessException;
import com.toby.klass.common.domain.error.ErrorCode;
import com.toby.klass.enrollment.application.EnrollmentProperties;
import com.toby.klass.enrollment.application.dto.ApplyEnrollmentCommand;
import com.toby.klass.enrollment.application.dto.CancelEnrollmentCommand;
import com.toby.klass.enrollment.application.dto.ConfirmEnrollmentCommand;
import com.toby.klass.enrollment.application.dto.EnrollmentResult;
import com.toby.klass.enrollment.application.dto.GiveUpWaitlistCommand;
import com.toby.klass.enrollment.application.dto.RegisterWaitlistCommand;
import com.toby.klass.enrollment.application.dto.WaitlistResult;
import com.toby.klass.enrollment.application.port.out.EnrollmentCommandPort;
import com.toby.klass.enrollment.application.port.out.EnrollmentQueryPort;
import com.toby.klass.enrollment.domain.CancelReason;
import com.toby.klass.enrollment.domain.Enrollment;
import com.toby.klass.enrollment.domain.EnrollmentSource;
import com.toby.klass.enrollment.domain.EnrollmentStatus;
import com.toby.klass.enrollment.domain.error.EnrollmentError;
import com.toby.klass.klass.application.port.out.KlassQueryPort;
import com.toby.klass.klass.domain.Klass;
import com.toby.klass.klass.domain.KlassStatus;
import com.toby.klass.klass.domain.error.KlassError;
import com.toby.klass.user.application.port.out.UserQueryPort;
import com.toby.klass.user.domain.Role;
import com.toby.klass.user.domain.User;
import com.toby.klass.waitlist.application.port.out.WaitlistCommandPort;
import com.toby.klass.waitlist.application.port.out.WaitlistQueryPort;
import com.toby.klass.waitlist.domain.Waitlist;
import com.toby.klass.waitlist.domain.WaitlistStatus;
import com.toby.klass.waitlist.domain.error.WaitlistError;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 좌석 서비스 조립 검증 (L2).
 *
 * <h2>여기서만 검증할 수 있는 것</h2>
 * <ol>
 *   <li><b>검사 순서</b> — 개설자 검사가 중복 검사보다 먼저인지. 뒤바뀌면 개설자에게
 *       "이미 신청했다"라는 엉뚱한 메시지가 나간다 (FR-19)</li>
 *   <li><b>승격의 순변화 0</b> — 반납과 재점유가 상쇄되는지. 도메인 테스트는 카운터
 *       증감을 각각 볼 뿐 두 도메인에 걸친 조립을 보지 못한다</li>
 *   <li><b>락 조회를 쓰는지</b> — 무락 조회를 쓰면 동시성이 통째로 무너지는데,
 *       단일 스레드 통합 테스트로는 드러나지 않는다</li>
 * </ol>
 *
 * <p>Design Ref: enrollment-management §4.3 · §9.3
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EnrollmentService — 좌석 유스케이스 조립")
class EnrollmentServiceTest {

    private static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 11, 1, 15, 30);
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 10, 1, 10, 0);
    private static final LocalDate STARTS_ON = LocalDate.of(2026, 11, 1);
    private static final LocalDate ENDS_ON = LocalDate.of(2026, 12, 31);

    private static final Long KLASS_ID = 100L;
    private static final Long CREATOR_ID = 1L;
    private static final Long STUDENT_ID = 2L;
    private static final Long OTHER_ID = 3L;
    private static final Long ENROLLMENT_ID = 500L;
    private static final Long WAITLIST_ID = 900L;

    private static final int DEFAULT_PERIOD_DAYS = 7;
    private static final Duration DIRECT_EXPIRY = Duration.ofMinutes(30);
    private static final Duration WAITLIST_EXPIRY = Duration.ofMinutes(10);
    private static final int REAP_BATCH_SIZE = 200;

    @Mock
    private KlassQueryPort klassQueryPort;

    @Mock
    private EnrollmentCommandPort enrollmentCommandPort;

    @Mock
    private EnrollmentQueryPort enrollmentQueryPort;

    @Mock
    private WaitlistCommandPort waitlistCommandPort;

    @Mock
    private WaitlistQueryPort waitlistQueryPort;

    @Mock
    private UserQueryPort userQueryPort;

    private final Clock clock = Clock.fixed(
            FIXED_NOW.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());

    private EnrollmentService service;

    @BeforeEach
    void setUp() {
        service = new EnrollmentService(klassQueryPort, enrollmentCommandPort,
                enrollmentQueryPort, waitlistCommandPort, waitlistQueryPort, userQueryPort,
                new EnrollmentProperties(DEFAULT_PERIOD_DAYS,
                        new EnrollmentProperties.PendingExpiry(DIRECT_EXPIRY, WAITLIST_EXPIRY),
                        REAP_BATCH_SIZE),
                clock);
    }

    // ── 픽스처 ───────────────────────────────────────────────────────────────

    private static User user(Long id, String username, Role role) {
        User user = User.register(username, "hashed", Set.of(role), CREATED_AT);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private static User creator() {
        return user(CREATOR_ID, "creator", Role.ROLE_CREATOR);
    }

    private static User student() {
        return user(STUDENT_ID, "student", Role.ROLE_USER);
    }

    /** 정원 {@code capacity}, 좌석 {@code occupied} 개가 찬 강의. */
    private static Klass klass(KlassStatus status, int capacity, int occupied) {
        Klass klass = Klass.open(creator(), "스프링 부트 입문", "내용",
                new BigDecimal("50000"), capacity, STARTS_ON, ENDS_ON, null, CREATED_AT);
        ReflectionTestUtils.setField(klass, "id", KLASS_ID);
        if (status != KlassStatus.DRAFT) {
            klass.publish(CREATED_AT);
        }
        for (int i = 0; i < occupied; i++) {
            klass.occupySeat();
        }
        if (status == KlassStatus.CLOSED) {
            klass.close(CREATED_AT);
        }
        return klass;
    }

    private static Klass openKlass() {
        return klass(KlassStatus.OPEN, 10, 0);
    }

    /**
     * {@code PENDING} 신청. 만료 시각은 {@code CREATED_AT} 기준이라 <b>이미 지났다</b> —
     * {@code FIXED_NOW} 가 한 달 뒤이기 때문이다. 취소는 만료와 무관하므로 그쪽 테스트가 쓴다.
     */
    private static Enrollment enrollment(Klass klass, User who) {
        Enrollment enrollment = Enrollment.apply(klass, who, EnrollmentSource.DIRECT,
                CREATED_AT, CREATED_AT.plus(DIRECT_EXPIRY));
        ReflectionTestUtils.setField(enrollment, "id", ENROLLMENT_ID);
        return enrollment;
    }

    /**
     * 아직 결제할 수 있는 {@code PENDING}. 만료가 {@code FIXED_NOW} 이후다.
     *
     * <p>확정 테스트가 이것을 써야 한다 — {@link #enrollment} 는 이미 만료돼 있어
     * {@code ENROLLMENT_EXPIRED} 로 떨어진다.
     */
    private static Enrollment unexpiredEnrollment(Klass klass, User who) {
        Enrollment enrollment = Enrollment.apply(klass, who, EnrollmentSource.DIRECT,
                FIXED_NOW.minusMinutes(1), FIXED_NOW.plus(DIRECT_EXPIRY));
        ReflectionTestUtils.setField(enrollment, "id", ENROLLMENT_ID);
        return enrollment;
    }

    private static Waitlist waitlist(Klass klass, User who, int position) {
        Waitlist waitlist = Waitlist.enqueue(klass, who, position, CREATED_AT);
        ReflectionTestUtils.setField(waitlist, "id", WAITLIST_ID);
        return waitlist;
    }

    private static ErrorCode errorCodeOf(Throwable e) {
        return ((BusinessException) e).errorCode();
    }

    private void givenSaveEchoes() {
        given(enrollmentCommandPort.save(any(Enrollment.class)))
                .willAnswer(i -> i.getArgument(0));
    }

    // ── 신청 ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("수강 신청")
    class Apply {

        @Test
        @DisplayName("PENDING 으로 저장하고 좌석을 점유한다. 만료는 direct 기한이다")
        void appliesAndOccupies() {
            Klass klass = openKlass();
            given(klassQueryPort.findWithLockById(KLASS_ID)).willReturn(Optional.of(klass));
            given(userQueryPort.findById(STUDENT_ID)).willReturn(Optional.of(student()));
            givenSaveEchoes();

            EnrollmentResult result = service.apply(
                    new ApplyEnrollmentCommand(KLASS_ID, STUDENT_ID));

            assertThat(result.status()).isEqualTo(EnrollmentStatus.PENDING);
            assertThat(result.source()).isEqualTo(EnrollmentSource.DIRECT);
            assertThat(result.createdAt()).isEqualTo(FIXED_NOW);
            assertThat(result.expiresAt())
                    .as("direct 기한이어야 한다 — waitlist 기한을 쓰면 30분이 10분이 된다")
                    .isEqualTo(FIXED_NOW.plus(DIRECT_EXPIRY));
            assertThat(klass.getEnrollmentCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("락 조회를 쓴다 — 무락 조회는 부르지 않는다")
        void usesLockFetch() {
            given(klassQueryPort.findWithLockById(KLASS_ID)).willReturn(Optional.of(openKlass()));
            given(userQueryPort.findById(STUDENT_ID)).willReturn(Optional.of(student()));
            givenSaveEchoes();

            service.apply(new ApplyEnrollmentCommand(KLASS_ID, STUDENT_ID));

            then(klassQueryPort).should().findWithLockById(KLASS_ID);
            then(klassQueryPort).should(never()).findById(anyLong());
        }

        @Test
        @DisplayName("없는 강의는 404")
        void rejectsMissingKlass() {
            given(klassQueryPort.findWithLockById(KLASS_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.apply(
                    new ApplyEnrollmentCommand(KLASS_ID, STUDENT_ID)))
                    .extracting(EnrollmentServiceTest::errorCodeOf)
                    .isEqualTo(KlassError.KLASS_NOT_FOUND);
        }

        @Test
        @DisplayName("DRAFT 와 CLOSED 는 KLASS_NOT_OPEN — 초안을 404 로 감추지 않는다")
        void rejectsNonOpen() {
            for (KlassStatus status : new KlassStatus[] {KlassStatus.DRAFT, KlassStatus.CLOSED}) {
                given(klassQueryPort.findWithLockById(KLASS_ID))
                        .willReturn(Optional.of(klass(status, 10, 0)));

                assertThatThrownBy(() -> service.apply(
                        new ApplyEnrollmentCommand(KLASS_ID, STUDENT_ID)))
                        .as(status.name())
                        .extracting(EnrollmentServiceTest::errorCodeOf)
                        .isEqualTo(EnrollmentError.KLASS_NOT_OPEN);
            }
        }

        @Test
        @DisplayName("개설자 본인은 403 SELF_ENROLLMENT_FORBIDDEN (FR-19)")
        void rejectsOwner() {
            given(klassQueryPort.findWithLockById(KLASS_ID)).willReturn(Optional.of(openKlass()));

            assertThatThrownBy(() -> service.apply(
                    new ApplyEnrollmentCommand(KLASS_ID, CREATOR_ID)))
                    .extracting(EnrollmentServiceTest::errorCodeOf)
                    .isEqualTo(EnrollmentError.SELF_ENROLLMENT_FORBIDDEN);
        }

        @Test
        @DisplayName("개설자 검사가 중복 검사보다 먼저다 — 중복 조회를 아예 하지 않는다")
        void ownerCheckPrecedesDuplicateCheck() {
            given(klassQueryPort.findWithLockById(KLASS_ID)).willReturn(Optional.of(openKlass()));

            assertThatThrownBy(() -> service.apply(
                    new ApplyEnrollmentCommand(KLASS_ID, CREATOR_ID)))
                    .isInstanceOf(BusinessException.class);

            then(enrollmentQueryPort).should(never()).existsActive(anyLong(), anyLong());
        }

        @Test
        @DisplayName("활성 신청이 있으면 409 DUPLICATE_ENROLLMENT")
        void rejectsDuplicate() {
            given(klassQueryPort.findWithLockById(KLASS_ID)).willReturn(Optional.of(openKlass()));
            given(enrollmentQueryPort.existsActive(KLASS_ID, STUDENT_ID)).willReturn(true);

            assertThatThrownBy(() -> service.apply(
                    new ApplyEnrollmentCommand(KLASS_ID, STUDENT_ID)))
                    .extracting(EnrollmentServiceTest::errorCodeOf)
                    .isEqualTo(EnrollmentError.DUPLICATE_ENROLLMENT);
        }

        @Test
        @DisplayName("정원이 찼으면 409 이고 신청은 저장되지 않는다")
        void rejectsWhenFull() {
            Klass full = klass(KlassStatus.OPEN, 2, 2);
            given(klassQueryPort.findWithLockById(KLASS_ID)).willReturn(Optional.of(full));

            assertThatThrownBy(() -> service.apply(
                    new ApplyEnrollmentCommand(KLASS_ID, STUDENT_ID)))
                    .extracting(EnrollmentServiceTest::errorCodeOf)
                    .isEqualTo(KlassError.KLASS_CAPACITY_FULL);

            then(enrollmentCommandPort).should(never()).save(any());
            assertThat(full.getEnrollmentCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("정원 초과여도 대기열로 자동 분기하지 않는다 — 요청하지 않은 등록은 월권이다")
        void doesNotAutoEnqueue() {
            given(klassQueryPort.findWithLockById(KLASS_ID))
                    .willReturn(Optional.of(klass(KlassStatus.OPEN, 1, 1)));

            assertThatThrownBy(() -> service.apply(
                    new ApplyEnrollmentCommand(KLASS_ID, STUDENT_ID)))
                    .isInstanceOf(BusinessException.class);

            then(waitlistCommandPort).shouldHaveNoInteractions();
        }
    }

    // ── 결제 확정 ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("결제 확정")
    class Confirm {

        @Test
        @DisplayName("CONFIRMED 로 전이하고 klass 락을 잡지 않는다 (§4.1 예외)")
        void confirmsWithoutKlassLock() {
            Klass klass = klass(KlassStatus.OPEN, 10, 1);
            Enrollment enrollment = unexpiredEnrollment(klass, student());
            given(enrollmentQueryPort.findWithLockById(ENROLLMENT_ID))
                    .willReturn(Optional.of(enrollment));

            EnrollmentResult result = service.confirm(
                    new ConfirmEnrollmentCommand(ENROLLMENT_ID, STUDENT_ID));

            assertThat(result.status()).isEqualTo(EnrollmentStatus.CONFIRMED);
            assertThat(result.confirmedAt()).isEqualTo(FIXED_NOW);
            assertThat(result.expiresAt()).isNull();
            then(klassQueryPort).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("좌석 점유 수는 변하지 않는다 — PENDING 이 이미 점유하고 있었다")
        void doesNotChangeSeatCount() {
            Klass klass = klass(KlassStatus.OPEN, 10, 1);
            given(enrollmentQueryPort.findWithLockById(ENROLLMENT_ID))
                    .willReturn(Optional.of(unexpiredEnrollment(klass, student())));

            service.confirm(new ConfirmEnrollmentCommand(ENROLLMENT_ID, STUDENT_ID));

            assertThat(klass.getEnrollmentCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("없는 신청은 404")
        void rejectsMissing() {
            given(enrollmentQueryPort.findWithLockById(ENROLLMENT_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.confirm(
                    new ConfirmEnrollmentCommand(ENROLLMENT_ID, STUDENT_ID)))
                    .extracting(EnrollmentServiceTest::errorCodeOf)
                    .isEqualTo(EnrollmentError.ENROLLMENT_NOT_FOUND);
        }

        @Test
        @DisplayName("타인의 신청은 403")
        void rejectsOtherUsers() {
            given(enrollmentQueryPort.findWithLockById(ENROLLMENT_ID))
                    .willReturn(Optional.of(enrollment(openKlass(), student())));

            assertThatThrownBy(() -> service.confirm(
                    new ConfirmEnrollmentCommand(ENROLLMENT_ID, OTHER_ID)))
                    .extracting(EnrollmentServiceTest::errorCodeOf)
                    .isEqualTo(EnrollmentError.NOT_ENROLLMENT_OWNER);
        }
    }

    // ── 취소 ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("수강 취소")
    class Cancel {

        private void givenCancelTarget(Klass klass, Enrollment enrollment) {
            given(enrollmentQueryPort.findKlassIdById(ENROLLMENT_ID))
                    .willReturn(Optional.of(KLASS_ID));
            given(klassQueryPort.findWithLockById(KLASS_ID)).willReturn(Optional.of(klass));
            given(enrollmentQueryPort.findWithLockById(ENROLLMENT_ID))
                    .willReturn(Optional.of(enrollment));
        }

        @Test
        @DisplayName("CANCELLED 로 전이하고 좌석을 반납한다")
        void cancelsAndReleases() {
            Klass klass = klass(KlassStatus.OPEN, 10, 1);
            givenCancelTarget(klass, enrollment(klass, student()));

            EnrollmentResult result = service.cancel(
                    new CancelEnrollmentCommand(ENROLLMENT_ID, STUDENT_ID));

            assertThat(result.status()).isEqualTo(EnrollmentStatus.CANCELLED);
            assertThat(result.cancelledAt()).isEqualTo(FIXED_NOW);
            assertThat(klass.getEnrollmentCount()).isZero();
        }

        @Test
        @DisplayName("락 순서를 지킨다 — 소속 강의를 무락으로 먼저 알아낸 뒤 klass 를 잠근다")
        void locksKlassBeforeEnrollment() {
            Klass klass = klass(KlassStatus.OPEN, 10, 1);
            givenCancelTarget(klass, enrollment(klass, student()));

            service.cancel(new CancelEnrollmentCommand(ENROLLMENT_ID, STUDENT_ID));

            var order = org.mockito.Mockito.inOrder(enrollmentQueryPort, klassQueryPort);
            order.verify(enrollmentQueryPort).findKlassIdById(ENROLLMENT_ID);
            order.verify(klassQueryPort).findWithLockById(KLASS_ID);
            order.verify(enrollmentQueryPort).findWithLockById(ENROLLMENT_ID);
        }

        @Test
        @DisplayName("없는 신청은 0번에서 404 — klass 락을 잡지 않는다")
        void rejectsMissingBeforeLocking() {
            given(enrollmentQueryPort.findKlassIdById(ENROLLMENT_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.cancel(
                    new CancelEnrollmentCommand(ENROLLMENT_ID, STUDENT_ID)))
                    .extracting(EnrollmentServiceTest::errorCodeOf)
                    .isEqualTo(EnrollmentError.ENROLLMENT_NOT_FOUND);

            then(klassQueryPort).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("타인의 신청은 403 이고 좌석이 반납되지 않는다")
        void rejectsOtherUsers() {
            Klass klass = klass(KlassStatus.OPEN, 10, 1);
            givenCancelTarget(klass, enrollment(klass, student()));

            assertThatThrownBy(() -> service.cancel(
                    new CancelEnrollmentCommand(ENROLLMENT_ID, OTHER_ID)))
                    .extracting(EnrollmentServiceTest::errorCodeOf)
                    .isEqualTo(EnrollmentError.NOT_ENROLLMENT_OWNER);

            assertThat(klass.getEnrollmentCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("취소가 거부되면 좌석도 그대로다 — 거부와 반납이 함께 롤백되지 않으면 카운터가 어긋난다")
        void rejectedCancelKeepsSeat() {
            Klass klass = klass(KlassStatus.OPEN, 10, 1);
            Enrollment enrollment = enrollment(klass, student());
            enrollment.cancel(CREATED_AT, STARTS_ON, klass.cancellationPolicy(DEFAULT_PERIOD_DAYS));
            givenCancelTarget(klass, enrollment);

            assertThatThrownBy(() -> service.cancel(
                    new CancelEnrollmentCommand(ENROLLMENT_ID, STUDENT_ID)))
                    .extracting(EnrollmentServiceTest::errorCodeOf)
                    .isEqualTo(EnrollmentError.INVALID_ENROLLMENT_STATUS_TRANSITION);

            assertThat(klass.getEnrollmentCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("전역 기본 취소 기간이 강의 정책에 반영된다")
        void appliesDefaultCancellationPeriod() {
            Klass klass = klass(KlassStatus.OPEN, 10, 1);
            Enrollment enrollment = enrollment(klass, student());
            enrollment.confirm(CREATED_AT.plusMinutes(1));
            givenCancelTarget(klass, enrollment);

            // 확정 10/1 + 기본 7일 = 10/8. 지금은 11/1 이라 기간을 넘겼다
            assertThatThrownBy(() -> service.cancel(
                    new CancelEnrollmentCommand(ENROLLMENT_ID, STUDENT_ID)))
                    .as("강의가 cancellationPeriodDays 를 지정하지 않았으므로 전역 7일이 쓰여야 한다")
                    .extracting(EnrollmentServiceTest::errorCodeOf)
                    .isEqualTo(EnrollmentError.CANCELLATION_PERIOD_EXPIRED);
        }
    }

    // ── 승격 ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("취소에 따른 대기열 승격")
    class Promotion {

        private Klass klass;

        @BeforeEach
        void givenCancellableEnrollment() {
            klass = klass(KlassStatus.OPEN, 10, 1);
            given(enrollmentQueryPort.findKlassIdById(ENROLLMENT_ID))
                    .willReturn(Optional.of(KLASS_ID));
            given(klassQueryPort.findWithLockById(KLASS_ID)).willReturn(Optional.of(klass));
            given(enrollmentQueryPort.findWithLockById(ENROLLMENT_ID))
                    .willReturn(Optional.of(enrollment(klass, student())));
        }

        private void cancel() {
            service.cancel(new CancelEnrollmentCommand(ENROLLMENT_ID, STUDENT_ID));
        }

        @Test
        @DisplayName("1순위를 승격하고 좌석 순변화가 0 이다")
        void promotesFirstWithZeroNetChange() {
            User waiter = user(OTHER_ID, "waiter", Role.ROLE_USER);
            given(waitlistQueryPort.findNextWaitingWithLock(KLASS_ID, 0))
                    .willReturn(Optional.of(waitlist(klass, waiter, 1)));
            givenSaveEchoes();

            cancel();

            assertThat(klass.getEnrollmentCount())
                    .as("반납(-1)과 승격 점유(+1)가 상쇄돼야 한다. 틈이 생기면 일반 신청자가 채간다")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("승격 신청은 source 가 WAITLIST 이고 만료가 짧다")
        void promotedEnrollmentUsesWaitlistExpiry() {
            User waiter = user(OTHER_ID, "waiter", Role.ROLE_USER);
            Waitlist target = waitlist(klass, waiter, 1);
            given(waitlistQueryPort.findNextWaitingWithLock(KLASS_ID, 0))
                    .willReturn(Optional.of(target));
            givenSaveEchoes();

            cancel();

            var captor = org.mockito.ArgumentCaptor.forClass(Enrollment.class);
            then(enrollmentCommandPort).should().save(captor.capture());

            assertThat(captor.getValue().getSource()).isEqualTo(EnrollmentSource.WAITLIST);
            assertThat(captor.getValue().getExpiresAt())
                    .as("승격은 이미 기다리던 상태라 짧게 잡아 뒷 순번을 오래 붙잡지 않는다")
                    .isEqualTo(FIXED_NOW.plus(WAITLIST_EXPIRY));
            assertThat(target.getStatus()).isEqualTo(WaitlistStatus.PROMOTED);
            assertThat(target.getPromotedAt()).isEqualTo(FIXED_NOW);
        }

        @Test
        @DisplayName("대기자가 없으면 좌석은 빈 채로 남는다 — 순변화 -1")
        void leavesSeatEmptyWhenNoWaiter() {
            given(waitlistQueryPort.findNextWaitingWithLock(KLASS_ID, 0))
                    .willReturn(Optional.empty());

            cancel();

            assertThat(klass.getEnrollmentCount()).isZero();
            then(enrollmentCommandPort).should(never()).save(any());
        }

        @Test
        @DisplayName("1순위가 비활성 계정이면 정리하고 2순위를 승격한다")
        void skipsDisabledUser() {
            User disabled = user(OTHER_ID, "disabled", Role.ROLE_USER);
            ReflectionTestUtils.setField(disabled, "isEnabled", false);
            Waitlist first = waitlist(klass, disabled, 1);
            Waitlist second = Waitlist.enqueue(klass, user(4L, "second", Role.ROLE_USER), 2, CREATED_AT);

            given(waitlistQueryPort.findNextWaitingWithLock(KLASS_ID, 0))
                    .willReturn(Optional.of(first));
            given(waitlistQueryPort.findNextWaitingWithLock(KLASS_ID, 1))
                    .willReturn(Optional.of(second));
            givenSaveEchoes();

            cancel();

            assertThat(first.getStatus()).isEqualTo(WaitlistStatus.CANCELLED);
            assertThat(second.getStatus()).isEqualTo(WaitlistStatus.PROMOTED);
            assertThat(klass.getEnrollmentCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("이미 활성 신청이 있는 대기자는 건너뛴다")
        void skipsAlreadyEnrolled() {
            User waiter = user(OTHER_ID, "waiter", Role.ROLE_USER);
            Waitlist first = waitlist(klass, waiter, 1);
            given(waitlistQueryPort.findNextWaitingWithLock(KLASS_ID, 0))
                    .willReturn(Optional.of(first));
            given(enrollmentQueryPort.existsActive(KLASS_ID, OTHER_ID)).willReturn(true);
            given(waitlistQueryPort.findNextWaitingWithLock(KLASS_ID, 1))
                    .willReturn(Optional.empty());

            cancel();

            assertThat(first.getStatus()).isEqualTo(WaitlistStatus.CANCELLED);
            assertThat(klass.getEnrollmentCount()).isZero();
        }

        @Test
        @DisplayName("개설자가 대기열에 있으면 건너뛴다 — FR-19 의 세 번째 지점")
        void skipsKlassOwner() {
            Waitlist ownerWaiting = waitlist(klass, creator(), 1);
            given(waitlistQueryPort.findNextWaitingWithLock(KLASS_ID, 0))
                    .willReturn(Optional.of(ownerWaiting));
            given(waitlistQueryPort.findNextWaitingWithLock(KLASS_ID, 1))
                    .willReturn(Optional.empty());

            cancel();

            assertThat(ownerWaiting.getStatus())
                    .as("이 검사가 없으면 대기열이 신청 차단의 우회로가 된다")
                    .isEqualTo(WaitlistStatus.CANCELLED);
            then(enrollmentCommandPort).should(never()).save(any());
        }

        @Test
        @DisplayName("한 번에 1건만 승격한다 — 대기자가 여럿이어도")
        void promotesOnlyOne() {
            given(waitlistQueryPort.findNextWaitingWithLock(KLASS_ID, 0))
                    .willReturn(Optional.of(waitlist(klass, user(OTHER_ID, "w1", Role.ROLE_USER), 1)));
            givenSaveEchoes();

            cancel();

            then(enrollmentCommandPort).should().save(any());
            then(waitlistQueryPort).should(never()).findNextWaitingWithLock(KLASS_ID, 1);
        }
    }

    @Nested
    @DisplayName("CLOSED 강의의 취소는 승격하지 않는다")
    class ClosedKlassCancel {

        @Test
        @DisplayName("좌석만 반납되고 대기열을 조회조차 하지 않는다")
        void doesNotPromoteWhenClosed() {
            Klass closed = klass(KlassStatus.CLOSED, 10, 1);
            given(enrollmentQueryPort.findKlassIdById(ENROLLMENT_ID))
                    .willReturn(Optional.of(KLASS_ID));
            given(klassQueryPort.findWithLockById(KLASS_ID)).willReturn(Optional.of(closed));
            given(enrollmentQueryPort.findWithLockById(ENROLLMENT_ID))
                    .willReturn(Optional.of(enrollment(closed, student())));

            service.cancel(new CancelEnrollmentCommand(ENROLLMENT_ID, STUDENT_ID));

            assertThat(closed.getEnrollmentCount())
                    .as("마감 후 반납된 좌석은 빈 채로 남는다 — 명단 확정을 위한 의도된 선택이다")
                    .isZero();
            then(waitlistQueryPort).should(never())
                    .findNextWaitingWithLock(anyLong(), anyInt());
        }
    }

    // ── 대기열 등록 ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("대기열 등록")
    class RegisterWaitlist {

        private Klass full() {
            return klass(KlassStatus.OPEN, 2, 2);
        }

        @Test
        @DisplayName("WAITING 으로 저장하고 순번은 max + 1 이다")
        void enqueuesWithNextPosition() {
            given(klassQueryPort.findWithLockById(KLASS_ID)).willReturn(Optional.of(full()));
            given(userQueryPort.findById(STUDENT_ID)).willReturn(Optional.of(student()));
            given(waitlistQueryPort.maxPosition(KLASS_ID)).willReturn(3);
            given(waitlistCommandPort.save(any(Waitlist.class)))
                    .willAnswer(i -> i.getArgument(0));

            WaitlistResult result = service.register(
                    new RegisterWaitlistCommand(KLASS_ID, STUDENT_ID));

            assertThat(result.status()).isEqualTo(WaitlistStatus.WAITING);
            assertThat(result.position())
                    .as("취소된 순번은 gap 으로 남으므로 실제 대기 인원수와 다를 수 있다")
                    .isEqualTo(4);
            assertThat(result.createdAt()).isEqualTo(FIXED_NOW);
        }

        @Test
        @DisplayName("자리가 있으면 409 — 승격 트리거가 없어 영구히 기다리게 된다")
        void rejectsWhenSeatAvailable() {
            given(klassQueryPort.findWithLockById(KLASS_ID))
                    .willReturn(Optional.of(klass(KlassStatus.OPEN, 10, 1)));

            assertThatThrownBy(() -> service.register(
                    new RegisterWaitlistCommand(KLASS_ID, STUDENT_ID)))
                    .extracting(EnrollmentServiceTest::errorCodeOf)
                    .isEqualTo(WaitlistError.WAITLIST_SEAT_AVAILABLE);
        }

        @Test
        @DisplayName("개설자 본인은 403 — 신청과 같은 코드다")
        void rejectsOwner() {
            given(klassQueryPort.findWithLockById(KLASS_ID)).willReturn(Optional.of(full()));

            assertThatThrownBy(() -> service.register(
                    new RegisterWaitlistCommand(KLASS_ID, CREATOR_ID)))
                    .extracting(EnrollmentServiceTest::errorCodeOf)
                    .isEqualTo(EnrollmentError.SELF_ENROLLMENT_FORBIDDEN);
        }

        @Test
        @DisplayName("이미 활성 신청이 있으면 409 — 이것이 없으면 CONFIRMED 인 사람이 순번을 차지한다")
        void rejectsAlreadyEnrolled() {
            given(klassQueryPort.findWithLockById(KLASS_ID)).willReturn(Optional.of(full()));
            given(enrollmentQueryPort.existsActive(KLASS_ID, STUDENT_ID)).willReturn(true);

            assertThatThrownBy(() -> service.register(
                    new RegisterWaitlistCommand(KLASS_ID, STUDENT_ID)))
                    .extracting(EnrollmentServiceTest::errorCodeOf)
                    .isEqualTo(EnrollmentError.DUPLICATE_ENROLLMENT);
        }

        @Test
        @DisplayName("이미 대기 중이면 409")
        void rejectsDuplicateWaiting() {
            given(klassQueryPort.findWithLockById(KLASS_ID)).willReturn(Optional.of(full()));
            given(waitlistQueryPort.existsWaiting(KLASS_ID, STUDENT_ID)).willReturn(true);

            assertThatThrownBy(() -> service.register(
                    new RegisterWaitlistCommand(KLASS_ID, STUDENT_ID)))
                    .extracting(EnrollmentServiceTest::errorCodeOf)
                    .isEqualTo(WaitlistError.DUPLICATE_WAITLIST);
        }

        @Test
        @DisplayName("모집 중이 아니면 409")
        void rejectsNonOpen() {
            given(klassQueryPort.findWithLockById(KLASS_ID))
                    .willReturn(Optional.of(klass(KlassStatus.CLOSED, 2, 2)));

            assertThatThrownBy(() -> service.register(
                    new RegisterWaitlistCommand(KLASS_ID, STUDENT_ID)))
                    .extracting(EnrollmentServiceTest::errorCodeOf)
                    .isEqualTo(EnrollmentError.KLASS_NOT_OPEN);
        }
    }

    // ── 대기 포기 ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("대기 포기")
    class GiveUp {

        @Test
        @DisplayName("CANCELLED 로 전이하고 klass 락을 잡지 않는다 (§4.1 예외)")
        void givesUpWithoutKlassLock() {
            Waitlist target = waitlist(openKlass(), student(), 1);
            given(waitlistQueryPort.findWithLockById(WAITLIST_ID)).willReturn(Optional.of(target));

            WaitlistResult result = service.giveUp(
                    new GiveUpWaitlistCommand(WAITLIST_ID, STUDENT_ID));

            assertThat(result.status()).isEqualTo(WaitlistStatus.CANCELLED);
            then(klassQueryPort).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("없는 대기는 404")
        void rejectsMissing() {
            given(waitlistQueryPort.findWithLockById(WAITLIST_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.giveUp(
                    new GiveUpWaitlistCommand(WAITLIST_ID, STUDENT_ID)))
                    .extracting(EnrollmentServiceTest::errorCodeOf)
                    .isEqualTo(WaitlistError.WAITLIST_NOT_FOUND);
        }

        @Test
        @DisplayName("타인의 대기는 403")
        void rejectsOtherUsers() {
            given(waitlistQueryPort.findWithLockById(WAITLIST_ID))
                    .willReturn(Optional.of(waitlist(openKlass(), student(), 1)));

            assertThatThrownBy(() -> service.giveUp(
                    new GiveUpWaitlistCommand(WAITLIST_ID, OTHER_ID)))
                    .extracting(EnrollmentServiceTest::errorCodeOf)
                    .isEqualTo(WaitlistError.NOT_WAITLIST_OWNER);
        }

        @Test
        @DisplayName("이미 승격됐으면 409 — 배정된 좌석이 주인 없이 남으면 안 된다")
        void rejectsPromoted() {
            Waitlist promoted = waitlist(openKlass(), student(), 1);
            promoted.promote(CREATED_AT);
            given(waitlistQueryPort.findWithLockById(WAITLIST_ID))
                    .willReturn(Optional.of(promoted));

            assertThatThrownBy(() -> service.giveUp(
                    new GiveUpWaitlistCommand(WAITLIST_ID, STUDENT_ID)))
                    .extracting(EnrollmentServiceTest::errorCodeOf)
                    .isEqualTo(WaitlistError.WAITLIST_NOT_WAITING);
        }
    }

    // ── 마감 시 정리 ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("강의 마감 시 잔여 대기자 정리")
    class CancelRemaining {

        @Test
        @DisplayName("WAITING 을 전부 CANCELLED 로 바꾼다")
        void cancelsAllWaiting() {
            Klass klass = openKlass();
            List<Waitlist> waiting = List.of(
                    Waitlist.enqueue(klass, user(OTHER_ID, "w1", Role.ROLE_USER), 1, CREATED_AT),
                    Waitlist.enqueue(klass, user(4L, "w2", Role.ROLE_USER), 2, CREATED_AT));
            given(waitlistQueryPort.findAllWaiting(KLASS_ID)).willReturn(waiting);

            service.cancelRemaining(KLASS_ID);

            assertThat(waiting)
                    .as("남겨두면 영구히 승격되지 않는 유령 행이 된다")
                    .allSatisfy(w -> assertThat(w.getStatus())
                            .isEqualTo(WaitlistStatus.CANCELLED));
        }

        @Test
        @DisplayName("대기가 없으면 아무 일도 하지 않는다 — 호출자가 유무를 알 필요가 없다")
        void noopWhenEmpty() {
            given(waitlistQueryPort.findAllWaiting(KLASS_ID)).willReturn(List.of());

            service.cancelRemaining(KLASS_ID);

            then(waitlistCommandPort).shouldHaveNoInteractions();
        }
    }

    // ── 만료 회수 ────────────────────────────────────────────────────────────

    /**
     * 만료 회수 (L2).
     *
     * <h4>여기서만 검증할 수 있는 것</h4>
     * <ol>
     *   <li><b>락 순서</b> — {@code klass} 를 {@code enrollment} 보다 먼저 잠그는가.
     *       뒤집히면 기존 취소 트랜잭션과 데드락이 생기는데 단일 스레드 테스트로는
     *       드러나지 않는다</li>
     *   <li><b>재확인</b> — 락을 잡은 뒤 다시 보는가. 없으면 <b>배치가 확정된 신청을
     *       취소한다</b> (Plan R-2)</li>
     *   <li><b>승격의 순변화</b> — 반납과 재점유가 상쇄되는가</li>
     * </ol>
     *
     * <p>Design Ref: pending-expiry-reaper §5.3 · §8.4
     */
    @Nested
    @DisplayName("만료 회수")
    class ReapExpired {

        private void givenReapTarget(Klass klass, Enrollment enrollment) {
            given(enrollmentQueryPort.findKlassIdById(ENROLLMENT_ID))
                    .willReturn(Optional.of(KLASS_ID));
            given(klassQueryPort.findWithLockById(KLASS_ID)).willReturn(Optional.of(klass));
            given(enrollmentQueryPort.findWithLockById(ENROLLMENT_ID))
                    .willReturn(Optional.of(enrollment));
        }

        @Test
        @DisplayName("만료 건을 회수하고 좌석을 반납한다")
        void reapsAndReleasesSeat() {
            Klass klass = klass(KlassStatus.OPEN, 10, 1);
            Enrollment target = enrollment(klass, student());
            givenReapTarget(klass, target);
            given(waitlistQueryPort.findNextWaitingWithLock(KLASS_ID, 0))
                    .willReturn(Optional.empty());

            assertThat(service.reapExpired(ENROLLMENT_ID)).isTrue();

            assertThat(target.getStatus()).isEqualTo(EnrollmentStatus.CANCELLED);
            assertThat(target.getCancelReason())
                    .as("사용자 취소와 구분돼야 만료율을 셀 수 있다")
                    .isEqualTo(CancelReason.EXPIRED);
            assertThat(target.getCancelledAt()).isEqualTo(FIXED_NOW);
            assertThat(klass.getEnrollmentCount()).isZero();
        }

        @Test
        @DisplayName("락 순서를 지킨다 — 소속 강의를 무락으로 알아낸 뒤 klass, 그다음 enrollment")
        void locksKlassBeforeEnrollment() {
            Klass klass = klass(KlassStatus.OPEN, 10, 1);
            givenReapTarget(klass, enrollment(klass, student()));
            given(waitlistQueryPort.findNextWaitingWithLock(KLASS_ID, 0))
                    .willReturn(Optional.empty());

            service.reapExpired(ENROLLMENT_ID);

            InOrder order = inOrder(enrollmentQueryPort, klassQueryPort);
            order.verify(enrollmentQueryPort).findKlassIdById(ENROLLMENT_ID);
            order.verify(klassQueryPort).findWithLockById(KLASS_ID);
            order.verify(enrollmentQueryPort).findWithLockById(ENROLLMENT_ID);
        }

        /**
         * <b>이 테스트가 R-2 를 지킨다.</b> 후보 조회는 락 없이 하므로 그 사이 사용자가
         * 결제를 마쳤을 수 있다. 재확인이 없으면 배치가 확정된 좌석을 빼앗는다.
         */
        @Test
        @DisplayName("재확인 — 그 사이 결제됐으면 손대지 않는다")
        void skipsWhenAlreadyConfirmed() {
            Klass klass = klass(KlassStatus.OPEN, 10, 1);
            Enrollment target = unexpiredEnrollment(klass, student());
            target.confirm(FIXED_NOW.minusMinutes(1));
            givenReapTarget(klass, target);

            assertThat(service.reapExpired(ENROLLMENT_ID)).isFalse();

            assertThat(target.getStatus()).isEqualTo(EnrollmentStatus.CONFIRMED);
            assertThat(klass.getEnrollmentCount())
                    .as("좌석이 반납되면 확정된 수강생이 명단에서 밀려난다")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("재확인 — 그 사이 사용자가 취소했으면 손대지 않는다")
        void skipsWhenAlreadyCancelled() {
            Klass klass = klass(KlassStatus.OPEN, 10, 1);
            Enrollment target = enrollment(klass, student());
            target.cancel(CREATED_AT, STARTS_ON, klass.cancellationPolicy(DEFAULT_PERIOD_DAYS));
            givenReapTarget(klass, target);

            assertThat(service.reapExpired(ENROLLMENT_ID)).isFalse();

            assertThat(target.getCancelReason())
                    .as("사용자 취소가 만료로 덮이면 안 된다")
                    .isEqualTo(CancelReason.USER);
            assertThat(klass.getEnrollmentCount())
                    .as("좌석이 두 번 반납되면 카운터가 실제보다 작아진다")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("재확인 — 아직 기한이 남았으면 손대지 않는다")
        void skipsWhenNotYetExpired() {
            Klass klass = klass(KlassStatus.OPEN, 10, 1);
            Enrollment target = unexpiredEnrollment(klass, student());
            givenReapTarget(klass, target);

            assertThat(service.reapExpired(ENROLLMENT_ID)).isFalse();

            assertThat(target.getStatus()).isEqualTo(EnrollmentStatus.PENDING);
        }

        @Test
        @DisplayName("대상이 사라졌으면 false 다 — 예외가 아니다")
        void returnsFalseWhenGone() {
            given(enrollmentQueryPort.findKlassIdById(ENROLLMENT_ID))
                    .willReturn(Optional.empty());

            assertThat(service.reapExpired(ENROLLMENT_ID))
                    .as("배치 루프가 예외로 멈추면 남은 대상이 미처리로 남는다")
                    .isFalse();
            then(klassQueryPort).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("OPEN 이면 대기 1순위를 승격하고 좌석 순변화가 0 이다")
        void promotesOnOpenKlass() {
            Klass klass = klass(KlassStatus.OPEN, 10, 1);
            givenReapTarget(klass, enrollment(klass, student()));
            given(waitlistQueryPort.findNextWaitingWithLock(KLASS_ID, 0))
                    .willReturn(Optional.of(waitlist(klass, user(OTHER_ID, "waiter",
                            Role.ROLE_USER), 1)));
            givenSaveEchoes();

            service.reapExpired(ENROLLMENT_ID);

            assertThat(klass.getEnrollmentCount())
                    .as("반납(-1)과 승격 점유(+1)가 상쇄된다 — 틈이 생기면 일반 신청자가 채간다")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("CLOSED 면 승격하지 않고 좌석이 빈 채로 남는다 — 순변화 -1")
        void doesNotPromoteOnClosedKlass() {
            Klass klass = klass(KlassStatus.CLOSED, 10, 1);
            givenReapTarget(klass, enrollment(klass, student()));

            assertThat(service.reapExpired(ENROLLMENT_ID))
                    .as("마감 강의여도 회수 자체는 한다 — 명단이 정확해야 한다")
                    .isTrue();

            assertThat(klass.getEnrollmentCount()).isZero();
            then(waitlistQueryPort).should(never()).findNextWaitingWithLock(any(), anyInt());
        }

        @Test
        @DisplayName("후보 조회가 설정된 상한을 포트에 넘긴다")
        void passesConfiguredBatchSize() {
            given(enrollmentQueryPort.findExpiredIds(FIXED_NOW, REAP_BATCH_SIZE))
                    .willReturn(List.of(ENROLLMENT_ID));

            assertThat(service.findExpiredTargets()).containsExactly(ENROLLMENT_ID);

            then(enrollmentQueryPort).should()
                    .findExpiredIds(FIXED_NOW, REAP_BATCH_SIZE);
        }
    }

}
