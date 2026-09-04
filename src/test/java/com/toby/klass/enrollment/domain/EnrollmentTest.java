package com.toby.klass.enrollment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toby.klass.common.domain.error.BusinessException;
import com.toby.klass.enrollment.domain.error.EnrollmentError;
import com.toby.klass.klass.domain.CancellationPolicy;
import com.toby.klass.klass.domain.Klass;
import com.toby.klass.klass.domain.KlassFixture;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 수강 신청 상태 전이와 판별 (L1).
 *
 * <h2>이 클래스가 지키는 것</h2>
 * <ol>
 *   <li><b>{@code expires_at} 을 NULL 로 만드는가</b> — {@code ck_enrollment_pending} 이
 *       강제한다. 빠뜨리면 CHECK 위반으로 500 이 나는데, 도메인 테스트가 없으면 통합
 *       테스트까지 가서야 드러난다</li>
 *   <li><b>취소 판정의 경계</b> — 이 모듈의 핵심 위험이다. "며칠 지났나"라는 연속량을
 *       boolean 으로 자르므로 버그는 항상 자르는 지점에서 난다</li>
 *   <li><b>두 관문의 순서</b> — 강의 종료가 기간 초과보다 먼저다. 사용자에게 해야 할
 *       이야기가 다르기 때문이다</li>
 * </ol>
 *
 * <p>Design Ref: enrollment-management §3.2.1 · §9.2, FR-11 · FR-20
 */
@DisplayName("Enrollment — 상태 전이와 취소 판정")
class EnrollmentTest {

    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 10, 1, 10, 0);
    private static final LocalDateTime EXPIRES_AT = CREATED_AT.plusMinutes(30);

    /** 강의 진행 중인 날. {@code ENDS_ON} 은 12/31 이다. */
    private static final LocalDate DURING_KLASS = LocalDate.of(2026, 11, 1);

    private static final CancellationPolicy POLICY =
            new CancellationPolicy(KlassFixture.ENDS_ON, 7);

    private static final Klass KLASS = KlassFixture.open();

    private static Enrollment pending() {
        return Enrollment.apply(KLASS, KlassFixture.student(), EnrollmentSource.DIRECT,
                CREATED_AT, EXPIRES_AT);
    }

    /** 결제 확정된 신청. 확정 시각은 {@code CREATED_AT} 에서 10분 뒤다. */
    private static Enrollment confirmed() {
        Enrollment enrollment = pending();
        enrollment.confirm(CREATED_AT.plusMinutes(10));
        return enrollment;
    }

    private static Enrollment cancelled() {
        Enrollment enrollment = pending();
        enrollment.cancel(CREATED_AT.plusMinutes(1), DURING_KLASS, POLICY);
        return enrollment;
    }

    /** {@code confirmed()} 의 확정 시각. 취소 기간 기산점이다. */
    private static LocalDateTime confirmedAt() {
        return CREATED_AT.plusMinutes(10);
    }

    @Nested
    @DisplayName("생성")
    class Creation {

        @Test
        @DisplayName("PENDING 으로 시작하고 좌석을 점유한다")
        void startsAsPending() {
            Enrollment enrollment = pending();

            assertThat(enrollment.getStatus()).isEqualTo(EnrollmentStatus.PENDING);
            assertThat(enrollment.getExpiresAt()).isEqualTo(EXPIRES_AT);
            assertThat(enrollment.getConfirmedAt()).isNull();
            assertThat(enrollment.getCancelledAt()).isNull();
            assertThat(enrollment.isSeatOccupying())
                    .as("결제 전이어도 자리는 이미 잡고 있다 — 카운터가 이 정의를 따른다")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("결제 확정")
    class Confirm {

        @Test
        @DisplayName("PENDING → CONFIRMED. 확정 시각이 기록되고 만료 시각이 사라진다")
        void confirmsPending() {
            Enrollment enrollment = pending();
            LocalDateTime now = CREATED_AT.plusMinutes(10);

            enrollment.confirm(now);

            assertThat(enrollment.getStatus()).isEqualTo(EnrollmentStatus.CONFIRMED);
            assertThat(enrollment.getConfirmedAt()).isEqualTo(now);
            assertThat(enrollment.getExpiresAt())
                    .as("ck_enrollment_pending 이 'PENDING 이 아니면 NULL' 을 강제한다. "
                            + "남겨두면 CHECK 위반으로 500 이 난다")
                    .isNull();
        }

        @Test
        @DisplayName("확정해도 좌석 점유는 그대로다 — 카운터가 변하지 않는 근거")
        void stillOccupiesSeat() {
            assertThat(confirmed().isSeatOccupying())
                    .as("PENDING 이 이미 점유하고 있었으므로 확정은 klass 락이 필요 없다")
                    .isTrue();
        }

        @Test
        @DisplayName("만료 시각 직전까지는 확정할 수 있다")
        void allowsJustBeforeExpiry() {
            Enrollment enrollment = pending();

            assertThatCode(() -> enrollment.confirm(EXPIRES_AT.minusSeconds(1)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("만료 시각 정각은 이미 만료다 — 경계를 배제한다")
        void rejectsAtExpiryBoundary() {
            Enrollment enrollment = pending();

            assertThatThrownBy(() -> enrollment.confirm(EXPIRES_AT))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(EnrollmentError.ENROLLMENT_EXPIRED);
        }

        @Test
        @DisplayName("만료 회수 배치가 없어도 만료된 신청은 결제되지 않는다 — 유일한 만료 방어선")
        void rejectsLongExpired() {
            Enrollment enrollment = pending();

            assertThatThrownBy(() -> enrollment.confirm(EXPIRES_AT.plusDays(30)))
                    .as("D-32 로 회수 배치를 만들지 않으므로 이 검사가 유일하게 남는다")
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(EnrollmentError.ENROLLMENT_EXPIRED);
        }

        @Test
        @DisplayName("이미 CONFIRMED 면 거부한다 — 되돌리기도 재확정도 없다")
        void rejectsAlreadyConfirmed() {
            Enrollment enrollment = confirmed();

            assertThatThrownBy(() -> enrollment.confirm(CREATED_AT.plusMinutes(20)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(EnrollmentError.INVALID_ENROLLMENT_STATUS_TRANSITION);
        }

        @Test
        @DisplayName("CANCELLED 는 종착이라 확정할 수 없다")
        void rejectsCancelled() {
            Enrollment enrollment = cancelled();

            assertThatThrownBy(() -> enrollment.confirm(CREATED_AT.plusMinutes(20)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(EnrollmentError.INVALID_ENROLLMENT_STATUS_TRANSITION);
        }
    }

    @Nested
    @DisplayName("취소 — PENDING 은 두 관문을 면제받는다")
    class CancelPending {

        @Test
        @DisplayName("결제 전에는 언제든 취소된다")
        void cancelsAnytime() {
            Enrollment enrollment = pending();
            LocalDateTime now = CREATED_AT.plusMinutes(5);

            enrollment.cancel(now, DURING_KLASS, POLICY);

            assertThat(enrollment.getStatus()).isEqualTo(EnrollmentStatus.CANCELLED);
            assertThat(enrollment.getCancelledAt()).isEqualTo(now);
            assertThat(enrollment.getExpiresAt())
                    .as("ck_enrollment_pending 이 NULL 을 강제한다")
                    .isNull();
            assertThat(enrollment.isSeatOccupying()).isFalse();
        }

        @Test
        @DisplayName("취소 가능 기간이 지난 시점이어도 PENDING 이면 취소된다")
        void ignoresPeriod() {
            Enrollment enrollment = pending();

            assertThatCode(() ->
                    enrollment.cancel(CREATED_AT.plusDays(999), DURING_KLASS, POLICY))
                    .as("기산점인 confirmedAt 이 null 이라 애초에 기간을 잴 수 없다")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("강의가 끝난 뒤여도 PENDING 이면 취소된다")
        void ignoresKlassEnd() {
            Enrollment enrollment = pending();

            assertThatCode(() -> enrollment.cancel(
                    CREATED_AT, KlassFixture.ENDS_ON.plusDays(1), POLICY))
                    .as("결제 전이라 환불할 돈이 없다. 막으면 좌석만 영구히 묶인다")
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("취소 — CONFIRMED 는 두 관문을 통과해야 한다")
    class CancelConfirmed {

        @Test
        @DisplayName("기간 안이고 강의 진행 중이면 취소된다")
        void cancelsWithinBothGates() {
            Enrollment enrollment = confirmed();
            LocalDateTime now = confirmedAt().plusDays(3);

            enrollment.cancel(now, DURING_KLASS, POLICY);

            assertThat(enrollment.getStatus()).isEqualTo(EnrollmentStatus.CANCELLED);
            assertThat(enrollment.getCancelledAt()).isEqualTo(now);
            assertThat(enrollment.isSeatOccupying()).isFalse();
        }

        @Test
        @DisplayName("결제 + 7일 정확히 그 시각까지 취소할 수 있다 — 경계 포함")
        void boundaryIsInclusive() {
            Enrollment enrollment = confirmed();

            assertThatCode(() ->
                    enrollment.cancel(confirmedAt().plusDays(7), DURING_KLASS, POLICY))
                    .as("배제하면 '7일 이내 취소 가능'이라 안내하고 7일째에 거부하는 셈이 된다")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("1초만 지나도 CANCELLATION_PERIOD_EXPIRED")
        void rejectsOneSecondPast() {
            Enrollment enrollment = confirmed();

            assertThatThrownBy(() -> enrollment.cancel(
                    confirmedAt().plusDays(7).plusSeconds(1), DURING_KLASS, POLICY))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(EnrollmentError.CANCELLATION_PERIOD_EXPIRED);
        }

        @Test
        @DisplayName("거부되면 상태가 그대로 남는다 — 좌석도 반납되지 않는다")
        void leavesStateUntouchedOnRejection() {
            Enrollment enrollment = confirmed();

            assertThatThrownBy(() -> enrollment.cancel(
                    confirmedAt().plusDays(30), DURING_KLASS, POLICY))
                    .isInstanceOf(BusinessException.class);

            assertThat(enrollment.getStatus()).isEqualTo(EnrollmentStatus.CONFIRMED);
            assertThat(enrollment.getCancelledAt()).isNull();
            assertThat(enrollment.isSeatOccupying())
                    .as("거부됐는데 좌석이 반납되면 카운터가 실제 행 수와 어긋난다")
                    .isTrue();
        }

        @Test
        @DisplayName("종료일 당일은 아직 취소할 수 있다")
        void allowsOnEndDate() {
            Enrollment enrollment = confirmed();

            assertThatCode(() -> enrollment.cancel(
                    confirmedAt().plusDays(1), KlassFixture.ENDS_ON, POLICY))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("종료일 다음 날부터 KLASS_ALREADY_FINISHED — 기간이 남아 있어도 거부다")
        void rejectsAfterKlassEnds() {
            Enrollment enrollment = confirmed();

            assertThatThrownBy(() -> enrollment.cancel(
                    confirmedAt().plusDays(1), KlassFixture.ENDS_ON.plusDays(1), POLICY))
                    .as("취소 기간은 6일이나 남았지만 강의가 끝났다 (FR-20)")
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(EnrollmentError.KLASS_ALREADY_FINISHED);
        }

        @Test
        @DisplayName("두 관문이 동시에 걸리면 KLASS_ALREADY_FINISHED 가 먼저다")
        void klassEndTakesPrecedence() {
            Enrollment enrollment = confirmed();

            assertThatThrownBy(() -> enrollment.cancel(
                    confirmedAt().plusDays(999), KlassFixture.ENDS_ON.plusDays(1), POLICY))
                    .as("'기간이 지났다'고 답하면 다음엔 더 빨리 요청하면 된다고 오해한다. "
                            + "강의가 끝났다면 아무리 빨라도 성립하지 않는다")
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(EnrollmentError.KLASS_ALREADY_FINISHED);
        }

        @Test
        @DisplayName("취소 기간 0 이면 확정 직후만 취소 가능하다")
        void zeroPeriodAllowsOnlyImmediate() {
            CancellationPolicy noGrace = new CancellationPolicy(KlassFixture.ENDS_ON, 0);

            assertThatCode(() -> confirmed().cancel(confirmedAt(), DURING_KLASS, noGrace))
                    .doesNotThrowAnyException();
            assertThatThrownBy(() -> confirmed().cancel(
                    confirmedAt().plusSeconds(1), DURING_KLASS, noGrace))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(EnrollmentError.CANCELLATION_PERIOD_EXPIRED);
        }
    }

    @Nested
    @DisplayName("취소 — 종착 상태")
    class CancelTerminal {

        @Test
        @DisplayName("이미 CANCELLED 면 거부한다")
        void rejectsAlreadyCancelled() {
            Enrollment enrollment = cancelled();

            assertThatThrownBy(() -> enrollment.cancel(
                    CREATED_AT.plusMinutes(5), DURING_KLASS, POLICY))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(EnrollmentError.INVALID_ENROLLMENT_STATUS_TRANSITION);
        }

        @Test
        @DisplayName("종착 판정이 두 관문보다 먼저다 — 취소된 신청에 기간 이야기를 하지 않는다")
        void terminalCheckComesFirst() {
            Enrollment enrollment = cancelled();

            assertThatThrownBy(() -> enrollment.cancel(
                    CREATED_AT.plusDays(999), KlassFixture.ENDS_ON.plusDays(1), POLICY))
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(EnrollmentError.INVALID_ENROLLMENT_STATUS_TRANSITION);
        }
    }

    @Nested
    @DisplayName("isCancellableAt — cancel 과 같은 판정을 boolean 으로")
    class CancellableProbe {

        @Test
        @DisplayName("PENDING 은 항상 true")
        void pendingIsAlwaysCancellable() {
            assertThat(pending().isCancellableAt(
                    CREATED_AT.plusDays(999), KlassFixture.ENDS_ON.plusDays(1), POLICY))
                    .isTrue();
        }

        @Test
        @DisplayName("CONFIRMED 는 두 관문을 통과할 때만 true")
        void confirmedNeedsBothGates() {
            assertThat(confirmed().isCancellableAt(
                    confirmedAt().plusDays(3), DURING_KLASS, POLICY)).isTrue();
            assertThat(confirmed().isCancellableAt(
                    confirmedAt().plusDays(8), DURING_KLASS, POLICY))
                    .as("기간 초과").isFalse();
            assertThat(confirmed().isCancellableAt(
                    confirmedAt().plusDays(1), KlassFixture.ENDS_ON.plusDays(1), POLICY))
                    .as("강의 종료").isFalse();
        }

        @Test
        @DisplayName("CANCELLED 는 false")
        void cancelledIsNotCancellable() {
            assertThat(cancelled().isCancellableAt(CREATED_AT, DURING_KLASS, POLICY))
                    .isFalse();
        }

        @Test
        @DisplayName("cancel 이 성공하는 조건과 정확히 일치한다 — 판정이 갈라지지 않는다")
        void agreesWithCancel() {
            record Case(String label, Enrollment enrollment, LocalDateTime now, LocalDate today) {
            }

            for (Case c : new Case[] {
                    new Case("PENDING 기간 무관", pending(), CREATED_AT.plusDays(99), DURING_KLASS),
                    new Case("CONFIRMED 기간 내", confirmed(), confirmedAt().plusDays(3), DURING_KLASS),
                    new Case("CONFIRMED 경계", confirmed(), confirmedAt().plusDays(7), DURING_KLASS),
                    new Case("CONFIRMED 기간 초과", confirmed(), confirmedAt().plusDays(8), DURING_KLASS),
                    new Case("CONFIRMED 강의 종료", confirmed(), confirmedAt().plusDays(1),
                            KlassFixture.ENDS_ON.plusDays(1)),
                    new Case("CANCELLED", cancelled(), CREATED_AT, DURING_KLASS)}) {

                boolean probe = c.enrollment().isCancellableAt(c.now(), c.today(), POLICY);

                boolean actuallyCancels;
                try {
                    c.enrollment().cancel(c.now(), c.today(), POLICY);
                    actuallyCancels = true;
                } catch (BusinessException e) {
                    actuallyCancels = false;
                }

                assertThat(probe)
                        .as("%s — 응답의 isCancellable 이 실제 취소 결과와 어긋나면 "
                                + "사용자가 버튼을 눌렀는데 실패한다", c.label())
                        .isEqualTo(actuallyCancels);
            }
        }
    }

    /**
     * 만료 회수 (L1).
     *
     * <h4>경계가 이 절의 전부다</h4>
     * {@code confirm} 과 {@code expire} 는 <b>정확히 반대 조건</b>에서 성립한다. 둘이 같은
     * {@code isExpiredAt} 을 쓰지 않으면 경계 한 틱에서 <b>확정도 회수도 되지 않는 행</b>이
     * 생기는데, 그 행은 좌석을 영구히 점유한다. 여기서 잡지 못하면 배치를 붙인 뒤에도
     * R-01 이 남는다.
     *
     * <p>Design Ref: pending-expiry-reaper §3.2 · §8.1
     */
    @Nested
    @DisplayName("만료 회수 — expire 와 isExpiredAt")
    class Expire {

        @Test
        @DisplayName("PENDING → CANCELLED. 원인이 EXPIRED 로 남고 만료 시각이 사라진다")
        void expiresPending() {
            Enrollment enrollment = pending();

            enrollment.expire(EXPIRES_AT.plusNanos(1));

            assertThat(enrollment.getStatus()).isEqualTo(EnrollmentStatus.CANCELLED);
            assertThat(enrollment.getCancelReason()).isEqualTo(CancelReason.EXPIRED);
            assertThat(enrollment.getCancelledAt()).isEqualTo(EXPIRES_AT.plusNanos(1));
            assertThat(enrollment.getExpiresAt())
                    .as("ck_enrollment_pending 이 PENDING 이 아니면 NULL 을 강제한다")
                    .isNull();
            assertThat(enrollment.isSeatOccupying())
                    .as("회수됐으므로 더 이상 좌석을 세지 않는다")
                    .isFalse();
        }

        @Test
        @DisplayName("CONFIRMED 는 회수하지 않는다 — 결제를 마친 좌석이다")
        void rejectsConfirmed() {
            assertThatThrownBy(() -> confirmed().expire(EXPIRES_AT.plusNanos(1)))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(EnrollmentError.INVALID_ENROLLMENT_STATUS_TRANSITION);
        }

        @Test
        @DisplayName("이미 취소된 것을 다시 회수하지 않는다 — 좌석이 두 번 반납된다")
        void rejectsAlreadyCancelled() {
            assertThatThrownBy(() -> cancelled().expire(EXPIRES_AT.plusNanos(1)))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(EnrollmentError.INVALID_ENROLLMENT_STATUS_TRANSITION);
        }

        @Test
        @DisplayName("아직 기한이 남았으면 거부한다 — 결제 기회를 뺏으면 안 된다")
        void rejectsNotYetExpired() {
            assertThatThrownBy(() -> pending().expire(EXPIRES_AT.minusNanos(1)))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(EnrollmentError.ENROLLMENT_NOT_EXPIRED);
        }

        /**
         * <b>기존 {@code confirm} 의 경계를 그대로 보존한다.</b> 원래 조건식이
         * {@code !expiresAt.isAfter(now)} 였으므로 <b>같은 시각은 이미 만료</b>다 —
         * "기한이 10:30 까지"가 아니라 "10:30 이 되면 끝"이라는 뜻이다.
         *
         * <p>포트의 후보 조회({@code expires_at <= now})와 <b>정확히 같은 경계</b>이므로,
         * 배치가 집어온 후보가 재확인에서 억울하게 걸러지는 일이 없다.
         */
        @Test
        @DisplayName("경계 — 정확히 만료 시각이면 이미 만료다")
        void sameInstantIsExpired() {
            assertThat(pending().isExpiredAt(EXPIRES_AT))
                    .as("confirm 의 !expiresAt.isAfter(now) 와 같은 경계여야 한다")
                    .isTrue();
            assertThatCode(() -> pending().expire(EXPIRES_AT))
                    .doesNotThrowAnyException();
            assertThatThrownBy(() -> pending().confirm(EXPIRES_AT))
                    .as("같은 경계에서 확정은 거부된다 — 둘이 갈라지면 안 된다")
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(EnrollmentError.ENROLLMENT_EXPIRED);
        }

        @Test
        @DisplayName("경계 — 만료 시각 1나노초 전까지는 유효하다")
        void oneNanoBeforeIsValid() {
            assertThat(pending().isExpiredAt(EXPIRES_AT.minusNanos(1))).isFalse();
            assertThatCode(() -> pending().confirm(EXPIRES_AT.minusNanos(1)))
                    .as("확정은 여기까지 허용된다")
                    .doesNotThrowAnyException();
        }

        /**
         * {@code CONFIRMED}·{@code CANCELLED} 는 {@code expires_at} 이 {@code null} 이다.
         * {@code isExpiredAt} 이 상태를 <b>먼저</b> 보지 않으면 여기서 NPE 가 난다.
         */
        @Test
        @DisplayName("종착 상태는 언제 물어도 만료가 아니다 — NPE 가 나면 안 된다")
        void terminalStatesAreNeverExpired() {
            assertThat(confirmed().isExpiredAt(EXPIRES_AT.plusYears(1))).isFalse();
            assertThat(cancelled().isExpiredAt(EXPIRES_AT.plusYears(1))).isFalse();
        }

        @Test
        @DisplayName("사용자 취소는 원인이 USER 다 — 만료와 구분된다")
        void userCancellationRecordsUserReason() {
            assertThat(cancelled().getCancelReason()).isEqualTo(CancelReason.USER);
        }
    }

    @Nested
    @DisplayName("소유권")
    class Ownership {

        @Test
        @DisplayName("신청자 본인만 참이다. null 은 거짓이다")
        void identifiesOwner() {
            Enrollment enrollment = pending();

            assertThat(enrollment.isOwnedBy(KlassFixture.STUDENT_ID)).isTrue();
            assertThat(enrollment.isOwnedBy(KlassFixture.OTHER_ID)).isFalse();
            assertThat(enrollment.isOwnedBy(null))
                    .as("순서를 뒤집어 두지 않으면 여기서 NPE 가 난다")
                    .isFalse();
        }
    }
}
