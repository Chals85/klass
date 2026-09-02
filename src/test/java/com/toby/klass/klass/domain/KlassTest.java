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
     * Design D-28 — 공개된 뒤에는 제목만 바꿀 수 있다.
     *
     * <h2>이 판정을 도메인에 둔 이유</h2>
     * "무엇을 바꿀 수 있는가"는 <b>강의의 상태가 결정하는 규칙</b>이다. 서비스가
     * {@code if (status == DRAFT)} 로 분기하면 그 규칙이 호출처마다 복제되고, 수강신청
     * 사이클에서 다른 수정 경로가 생기면 한쪽만 고쳐진다.
     *
     * <p>{@code isOwnedBy}·{@code isVisibleTo} 와 같은 자리 — <b>도메인이 판단하고 서비스가
     * 조립한다.</b>
     */
    @Nested
    @DisplayName("수정 가능 범위 — 공개 후에는 제목만")
    class EditableScope {

        @Test
        @DisplayName("DRAFT 는 전 필드를 바꿀 수 있다")
        void draftIsFullyEditable() {
            assertThat(draft().isFullyEditable()).isTrue();
        }

        @Test
        @DisplayName("OPEN·CLOSED 는 제목 외 필드를 바꿀 수 없다")
        void publishedIsNotFullyEditable() {
            assertThat(open().isFullyEditable()).isFalse();
            assertThat(closed().isFullyEditable()).isFalse();
        }

        /**
         * <b>DRAFT 로 되돌아올 수 없다는 점이 이 규칙을 단순하게 만든다.</b>
         * {@code OPEN → DRAFT} 역전이가 차단돼 있어(D-18) 한 번 공개된 강의는 영구히
         * "제목만 수정 가능" 상태다 — 되돌릴 경로가 생기면 이 단언이 깨지고, 그때는
         * 무엇을 바꿀 수 있는지 규칙을 다시 정해야 한다.
         */
        @Test
        @DisplayName("공개 후에는 마감돼도 되돌아오지 않는다 — 전이할수록 좁아질 뿐이다")
        void editabilityNeverReturns() {
            Klass klass = draft();
            assertThat(klass.isFullyEditable()).isTrue();

            klass.publish(NOW);
            assertThat(klass.isFullyEditable()).isFalse();

            klass.close(NOW.plusHours(1));
            assertThat(klass.isFullyEditable()).isFalse();
        }

        /**
         * 도메인의 {@code change*} 메서드는 <b>상태를 검사하지 않는다.</b> 호출 가부는
         * {@link Klass#isFullyEditable()} 을 본 호출자가 정한다.
         *
         * <p>검사를 각 메서드에 심으면 6곳에 같은 조건이 복제되고, 그중 하나를 빠뜨리면
         * <b>그 필드만 조용히 공개 후에도 바뀐다.</b> 판정 지점을 하나로 모은 이유다.
         */
        @Test
        @DisplayName("change* 는 상태를 검사하지 않는다 — 판정은 호출자의 몫이다")
        void changeMethodsDoNotGuardStatus() {
            Klass klass = open();

            // 직접 호출하면 바뀐다. 서비스가 isFullyEditable 로 막는 것이 계약이다
            klass.changeCancellationPeriodDays(14, NOW);

            assertThat(klass.getCancellationPeriodDays()).isEqualTo(14);
        }
    }

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
