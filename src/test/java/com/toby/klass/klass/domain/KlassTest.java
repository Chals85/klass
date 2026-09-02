package com.toby.klass.klass.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toby.klass.common.domain.error.BusinessException;
import com.toby.klass.klass.domain.error.KlassError;
import com.toby.klass.user.domain.Role;
import com.toby.klass.user.domain.User;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 강의 도메인 규칙 검증 (L1).
 *
 * <h2>여기서 검증하는 것과 하지 않는 것</h2>
 * 상태 전이 가부·정원 축소 방어·가시성 판정은 <b>강의 자신이 아는 규칙</b>이므로 여기서
 * 검증한다. 반면 "요청자가 이 강의의 주인인가"를 <b>조합</b>하는 일과 그 실패가 403 인지
 * 404 인지는 서비스·컨트롤러의 몫이라 L2·L4 로 간다.
 *
 * <h2>id 를 리플렉션으로 넣는 이유</h2>
 * {@code User.id} 는 {@code @GeneratedValue} 라 영속화 전에는 {@code null} 이다.
 * {@code isOwnedBy} 는 그 id 를 비교하므로 도메인 단위 테스트에서는 직접 채워야 한다.
 * <b>필드명 문자열이라 컴파일러가 검사하지 않는다</b> — CLAUDE.md 가 지목한 "컴파일러가
 * 잡지 못하는 지점" 중 리플렉션 문자열에 해당하므로, {@code User} 의 필드명을 바꾸면
 * 이 파일이 함께 깨진다.
 *
 * <p>Design Ref: §3.2 도메인 메서드, §3.3 상태 전이, §3.4 정원 수정, §3.5 가시성, §8.2 L1
 */
class KlassTest {

    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 9, 1, 10, 0);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 2, 15, 30);
    private static final LocalDate STARTS_ON = LocalDate.of(2026, 10, 1);
    private static final LocalDate ENDS_ON = LocalDate.of(2026, 12, 31);

    private static final Long CREATOR_ID = 1L;
    private static final Long OTHER_ID = 2L;

    /** 개설자. id 는 영속화 없이 리플렉션으로 채운다 (클래스 주석 참조). */
    private static User creator(Long id) {
        User user = User.register("creator", "hashed", Set.of(Role.ROLE_CREATOR), CREATED_AT);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    /** 기본 강의. 상태는 {@code DRAFT}, 점유 인원 0. */
    private static Klass draft() {
        return Klass.open(creator(CREATOR_ID), "스프링 부트 입문", "처음 시작하는 스프링 부트",
                new BigDecimal("50000"), 30, STARTS_ON, ENDS_ON, 7, CREATED_AT);
    }

    private static Klass open() {
        Klass klass = draft();
        klass.publish(CREATED_AT);
        return klass;
    }

    private static Klass closed() {
        Klass klass = draft();
        klass.close(CREATED_AT);
        return klass;
    }

    /** 좌석이 이미 점유된 상태를 만든다. 증감 메서드가 2차 범위라 리플렉션으로 채운다. */
    private static Klass withEnrollmentCount(int count) {
        Klass klass = open();
        ReflectionTestUtils.setField(klass, "enrollmentCount", count);
        return klass;
    }

    @Nested
    @DisplayName("생성")
    class Creation {

        @Test
        @DisplayName("초안으로 시작하고 좌석은 비어 있으며 두 시각이 같다")
        void startsAsDraft() {
            Klass klass = draft();

            assertThat(klass.getStatus()).isEqualTo(KlassStatus.DRAFT);
            assertThat(klass.getEnrollmentCount()).isZero();
            // "아직 수정된 적 없음"이 이것으로 표현된다 (Design §3.1)
            assertThat(klass.getUpdatedAt()).isEqualTo(klass.getCreatedAt());
        }

        @Test
        @DisplayName("정원이 1 미만이면 생성 자체가 거부된다")
        void rejectsNonPositiveCapacity() {
            assertThatThrownBy(() -> Klass.open(creator(CREATOR_ID), "제목", "내용",
                    BigDecimal.ZERO, 0, STARTS_ON, ENDS_ON, null, CREATED_AT))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(KlassError.INVALID_KLASS_CAPACITY);
        }

        @Test
        @DisplayName("종료일이 시작일보다 빠르면 생성 자체가 거부된다")
        void rejectsInvertedPeriod() {
            assertThatThrownBy(() -> Klass.open(creator(CREATOR_ID), "제목", "내용",
                    BigDecimal.ZERO, 10, ENDS_ON, STARTS_ON, null, CREATED_AT))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(KlassError.INVALID_KLASS_PERIOD);
        }

        @Test
        @DisplayName("시작일과 종료일이 같은 하루짜리 강의는 허용된다")
        void allowsSingleDayPeriod() {
            assertThatCode(() -> Klass.open(creator(CREATOR_ID), "제목", "내용",
                    BigDecimal.ZERO, 10, STARTS_ON, STARTS_ON, null, CREATED_AT))
                    .doesNotThrowAnyException();
        }
    }

    /** Design §3.3 — 허용 3종 (ERD 정본 §4.8 화이트리스트와 일치). */
    @Nested
    @DisplayName("상태 전이 — 허용")
    class AllowedTransitions {

        @Test
        @DisplayName("DRAFT → OPEN 으로 모집을 시작한다")
        void publishFromDraft() {
            Klass klass = draft();

            klass.publish(NOW);

            assertThat(klass.getStatus()).isEqualTo(KlassStatus.OPEN);
            assertThat(klass.getUpdatedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("OPEN → CLOSED 로 모집을 마감한다")
        void closeFromOpen() {
            Klass klass = open();

            klass.close(NOW);

            assertThat(klass.getStatus()).isEqualTo(KlassStatus.CLOSED);
            assertThat(klass.getUpdatedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("DRAFT → CLOSED 로 개설을 철회한다 — 물리 삭제가 없는 설계의 유일한 정리 수단")
        void closeFromDraft() {
            Klass klass = draft();

            klass.close(NOW);

            assertThat(klass.getStatus()).isEqualTo(KlassStatus.CLOSED);
        }
    }

    /**
     * Design §3.3 — 거부 3종.
     *
     * <p>{@code OPEN → DRAFT} / {@code CLOSED → OPEN} 은 호출할 메서드 자체가 없어
     * <b>컴파일 단계에서 이미 막혀 있다</b>. 여기서 검증하는 것은 같은 메서드를 잘못된
     * 상태에서 호출한 경우다.
     */
    @Nested
    @DisplayName("상태 전이 — 거부")
    class RejectedTransitions {

        static Stream<Arguments> rejected() {
            return Stream.of(
                    Arguments.of("이미 공개된 강의를 다시 공개",
                            (Runnable) () -> open().publish(NOW)),
                    Arguments.of("마감된 강의를 다시 공개 (CLOSED → OPEN 역전이)",
                            (Runnable) () -> closed().publish(NOW)),
                    Arguments.of("이미 마감된 강의를 다시 마감",
                            (Runnable) () -> closed().close(NOW)));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("rejected")
        @DisplayName("허용되지 않는 전이는 INVALID_KLASS_STATUS_TRANSITION 이다")
        void rejectsInvalidTransition(String ignoredName, Runnable attempt) {
            assertThatThrownBy(attempt::run)
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(KlassError.INVALID_KLASS_STATUS_TRANSITION);
        }

        @Test
        @DisplayName("전이가 거부되면 상태도 updatedAt 도 바뀌지 않는다")
        void leavesStateUntouchedOnRejection() {
            Klass klass = open();

            assertThatThrownBy(() -> klass.publish(NOW)).isInstanceOf(BusinessException.class);

            assertThat(klass.getStatus()).isEqualTo(KlassStatus.OPEN);
            assertThat(klass.getUpdatedAt()).isEqualTo(CREATED_AT);
        }
    }

    /** Design §3.4 — ERD 정본 §4.8 정원 수정 규칙. */
    @Nested
    @DisplayName("정원 수정")
    class CapacityChange {

        @Test
        @DisplayName("점유 인원보다 많으면 변경된다")
        void allowsCapacityAtOrAboveEnrollment() {
            Klass klass = withEnrollmentCount(5);

            klass.changeCapacity(10, NOW);

            assertThat(klass.getCapacity()).isEqualTo(10);
            assertThat(klass.getUpdatedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("점유 인원과 같은 값까지는 줄일 수 있다")
        void allowsCapacityEqualToEnrollment() {
            Klass klass = withEnrollmentCount(5);

            klass.changeCapacity(5, NOW);

            // 예외가 안 났다는 것만 보면 changeCapacity 가 아무것도 안 해도 통과한다
            assertThat(klass.getCapacity()).isEqualTo(5);
            assertThat(klass.getUpdatedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("점유 인원보다 적게 줄이면 CAPACITY_BELOW_ENROLLMENT — CHECK 제약까지 가지 않는다")
        void rejectsCapacityBelowEnrollment() {
            Klass klass = withEnrollmentCount(5);

            assertThatThrownBy(() -> klass.changeCapacity(3, NOW))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(KlassError.CAPACITY_BELOW_ENROLLMENT);
            assertThat(klass.getCapacity()).isEqualTo(30);
        }

        @Test
        @DisplayName("정원 0 은 점유 인원 검사보다 먼저 거부된다")
        void rejectsNonPositiveCapacity() {
            Klass klass = draft();

            assertThatThrownBy(() -> klass.changeCapacity(0, NOW))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(KlassError.INVALID_KLASS_CAPACITY);
        }
    }

    @Nested
    @DisplayName("내용 수정")
    class ContentChange {

        @Test
        @DisplayName("제목·내용·가격 수정은 updatedAt 을 갱신한다")
        void updatesTimestamp() {
            Klass klass = draft();

            klass.changeTitle("새 제목", NOW);
            assertThat(klass.getTitle()).isEqualTo("새 제목");
            assertThat(klass.getUpdatedAt()).isEqualTo(NOW);

            klass.changeDescription("새 내용", NOW.plusHours(1));
            assertThat(klass.getDescription()).isEqualTo("새 내용");
            assertThat(klass.getUpdatedAt()).isEqualTo(NOW.plusHours(1));

            klass.changePrice(new BigDecimal("70000"), NOW.plusHours(2));
            assertThat(klass.getPrice()).isEqualByComparingTo("70000");
            assertThat(klass.getUpdatedAt()).isEqualTo(NOW.plusHours(2));
        }

        @Test
        @DisplayName("수강 기간은 쌍으로 검사된다 — 종료일이 앞서면 거부되고 원래 값이 남는다")
        void rejectsInvertedPeriod() {
            Klass klass = draft();

            assertThatThrownBy(() -> klass.changePeriod(ENDS_ON, STARTS_ON, NOW))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(KlassError.INVALID_KLASS_PERIOD);
            assertThat(klass.getStartsOn()).isEqualTo(STARTS_ON);
            assertThat(klass.getEndsOn()).isEqualTo(ENDS_ON);
        }

        @Test
        @DisplayName("수강 기간을 정상 범위로 바꾼다")
        void changesPeriod() {
            Klass klass = draft();

            klass.changePeriod(STARTS_ON.plusDays(1), ENDS_ON.plusDays(1), NOW);

            assertThat(klass.getStartsOn()).isEqualTo(STARTS_ON.plusDays(1));
            assertThat(klass.getEndsOn()).isEqualTo(ENDS_ON.plusDays(1));
        }

        @Test
        @DisplayName("취소 가능 기간은 null 로 되돌릴 수 있다 — 전역 기본값을 따른다는 뜻이다")
        void allowsNullCancellationPeriod() {
            Klass klass = draft();

            klass.changeCancellationPeriodDays(null, NOW);

            assertThat(klass.getCancellationPeriodDays()).isNull();
        }
    }

    /**
     * Design §3.2 · D-26 — 취소 가능 기간은 {@code DRAFT} 에서만 바꿀 수 있다.
     *
     * <h2>여기서 고정하는 것은 두 가지다</h2>
     * <ol>
     *   <li><b>규칙</b> — 신청자가 생긴 뒤에는 취소 조건을 바꿀 수 없다. 취소 가능 기간은
     *       수강생과의 약속이고, {@code DRAFT} 는 신청을 받지 않아 상대가 없다</li>
     *   <li><b>같은 값 재전송은 no-op</b> — 이쪽이 더 중요하다. 수정은 전체 교체라(D-25)
     *       모든 요청이 이 필드를 싣고 오므로, 무조건 거부하면 <b>{@code OPEN} 강의는
     *       어떤 수정도 불가능해진다.</b> 그 회귀를 잡는 자리가 여기다</li>
     * </ol>
     */
    @Nested
    @DisplayName("취소 가능 기간 수정 — DRAFT 제한")
    class CancellationPeriodChange {

        @Test
        @DisplayName("DRAFT 에서는 값을 바꿀 수 있고 updatedAt 이 갱신된다")
        void allowsChangeOnDraft() {
            Klass klass = draft();

            klass.changeCancellationPeriodDays(14, NOW);

            assertThat(klass.getCancellationPeriodDays()).isEqualTo(14);
            assertThat(klass.getUpdatedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("OPEN 에서 다른 값으로 바꾸면 CANCELLATION_PERIOD_NOT_EDITABLE — 원값이 보존된다")
        void rejectsDifferentValueOnOpen() {
            Klass klass = open();

            assertThatThrownBy(() -> klass.changeCancellationPeriodDays(14, NOW))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(KlassError.CANCELLATION_PERIOD_NOT_EDITABLE);
            assertThat(klass.getCancellationPeriodDays()).isEqualTo(7);
            assertThat(klass.getUpdatedAt()).isEqualTo(CREATED_AT);
        }

        /**
         * <b>이 테스트가 함정 방어의 핵심이다.</b> 여기가 깨지면 {@code OPEN} 강의의 제목만
         * 바꾸려는 요청까지 409 가 된다 — 전체 교체 규약에서 클라이언트는 바꾸지 않은 필드에
         * 현재 값을 그대로 실어 보내기 때문이다.
         */
        @Test
        @DisplayName("OPEN 에서 같은 값 재전송은 no-op 이다 — 예외도 없고 updatedAt 도 그대로다")
        void sameValueOnOpenIsNoOp() {
            Klass klass = open();

            assertThatCode(() -> klass.changeCancellationPeriodDays(7, NOW))
                    .as("같은 값 재전송은 변경이 아니다 — 거부하면 OPEN 강의 수정이 통째로 막힌다")
                    .doesNotThrowAnyException();

            assertThat(klass.getCancellationPeriodDays()).isEqualTo(7);
            assertThat(klass.getUpdatedAt())
                    .as("no-op 이므로 시각도 남기지 않는다 — updatedAt 은 다른 change* 가 갱신한다")
                    .isEqualTo(CREATED_AT);
        }

        @Test
        @DisplayName("CLOSED 에서 다른 값으로 바꾸면 CANCELLATION_PERIOD_NOT_EDITABLE")
        void rejectsDifferentValueOnClosed() {
            Klass klass = closed();

            assertThatThrownBy(() -> klass.changeCancellationPeriodDays(14, NOW))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(KlassError.CANCELLATION_PERIOD_NOT_EDITABLE);
            assertThat(klass.getCancellationPeriodDays()).isEqualTo(7);
        }

        /**
         * {@code null} 은 "전역 기본값을 따른다"는 뜻이며 그 자체가 하나의 약속이다.
         * 따라서 {@code null ↔ 값} 전환도 조건을 바꾸는 일이고 {@code DRAFT} 에서만 된다.
         *
         * <p>이 케이스가 {@code Objects.equals} 를 고정한다 — {@code equals} 를 직접 호출하면
         * 현재 값이 {@code null} 인 경로에서 NPE 이고, {@code ==} 는 박싱 캐시 범위 밖에서
         * 조용히 틀린다.
         */
        @Test
        @DisplayName("null ↔ 값 전환은 DRAFT 에서만 가능하다")
        void nullTransitionsFollowTheSameRule() {
            // 값 → null (DRAFT)
            Klass toNull = draft();
            toNull.changeCancellationPeriodDays(null, NOW);
            assertThat(toNull.getCancellationPeriodDays()).isNull();
            assertThat(toNull.getUpdatedAt()).isEqualTo(NOW);

            // null → 값 (DRAFT). 현재 값이 null 이어도 비교가 NPE 를 내지 않는다
            toNull.changeCancellationPeriodDays(30, NOW.plusHours(1));
            assertThat(toNull.getCancellationPeriodDays()).isEqualTo(30);
            assertThat(toNull.getUpdatedAt()).isEqualTo(NOW.plusHours(1));

            // 값 → null (OPEN) 은 거부된다 — 되돌리기도 조건 변경이다
            Klass published = open();
            assertThatThrownBy(() -> published.changeCancellationPeriodDays(null, NOW))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(KlassError.CANCELLATION_PERIOD_NOT_EDITABLE);
            assertThat(published.getCancellationPeriodDays()).isEqualTo(7);
        }

        @Test
        @DisplayName("null 을 다시 null 로 보내는 것도 no-op 이다 — OPEN 에서도 통과한다")
        void nullToNullIsNoOpEvenOnOpen() {
            Klass klass = open();
            ReflectionTestUtils.setField(klass, "cancellationPeriodDays", null);

            assertThatCode(() -> klass.changeCancellationPeriodDays(null, NOW))
                    .doesNotThrowAnyException();

            assertThat(klass.getCancellationPeriodDays()).isNull();
            assertThat(klass.getUpdatedAt()).isEqualTo(CREATED_AT);
        }
    }

    /**
     * Design §3.5 — 소유권·가시성.
     *
     * <p><b>{@code null} 케이스가 핵심이다.</b> 강의 조회는 선택적 인증이라 비로그인 요청이
     * {@code viewerId = null} 로 들어온다. 이 경로가 NPE 없이 동작해야 공개 목록·상세가
     * 열린다.
     */
    @Nested
    @DisplayName("소유권과 가시성")
    class Visibility {

        @Test
        @DisplayName("개설자 본인이면 true, 타인이면 false, null 이면 false")
        void ownership() {
            Klass klass = draft();

            assertThat(klass.isOwnedBy(CREATOR_ID)).isTrue();
            assertThat(klass.isOwnedBy(OTHER_ID)).isFalse();
            assertThat(klass.isOwnedBy(null)).isFalse();
        }

        @Test
        @DisplayName("DRAFT 는 개설자에게만 보인다 — 타인과 비로그인에게는 존재하지 않는다")
        void draftIsVisibleToOwnerOnly() {
            Klass klass = draft();

            assertThat(klass.isVisibleTo(CREATOR_ID)).isTrue();
            assertThat(klass.isVisibleTo(OTHER_ID)).isFalse();
            assertThat(klass.isVisibleTo(null)).isFalse();
        }

        @Test
        @DisplayName("OPEN·CLOSED 는 비로그인에게도 보인다")
        void publishedIsVisibleToEveryone() {
            assertThat(open().isVisibleTo(null)).isTrue();
            assertThat(open().isVisibleTo(OTHER_ID)).isTrue();
            assertThat(closed().isVisibleTo(null)).isTrue();
            assertThat(closed().isVisibleTo(OTHER_ID)).isTrue();
        }
    }
}
