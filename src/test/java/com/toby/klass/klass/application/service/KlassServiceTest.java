package com.toby.klass.klass.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.toby.klass.common.domain.error.BusinessException;
import com.toby.klass.common.domain.error.ErrorCode;
import com.toby.klass.klass.application.dto.ChangeKlassStatusCommand;
import com.toby.klass.klass.application.dto.KlassResult;
import com.toby.klass.klass.application.dto.RegisterKlassCommand;
import com.toby.klass.klass.application.dto.UpdateKlassCommand;
import com.toby.klass.klass.application.port.out.KlassCommandPort;
import com.toby.klass.klass.application.port.out.KlassQueryPort;
import com.toby.klass.klass.domain.Klass;
import com.toby.klass.klass.domain.KlassStatus;
import com.toby.klass.klass.domain.error.KlassError;
import com.toby.klass.user.application.port.out.UserQueryPort;
import com.toby.klass.user.domain.Role;
import com.toby.klass.user.domain.User;
import com.toby.klass.user.domain.error.UserError;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 강의 서비스 조립 검증 (L2).
 *
 * <h2>여기서만 검증할 수 있는 것</h2>
 * <b>소유권 검사와 그 순서</b>다. 컨트롤러 테스트(L3)는 유즈케이스를 목으로 대체하므로
 * 이 로직이 아예 실행되지 않고, 통합 테스트(L4)는 경로 전체가 이어지는지를 볼 뿐
 * <b>검사 순서가 뒤바뀐 경우</b>를 정밀하게 짚기 어렵다.
 *
 * <h2>Clock 을 고정한다</h2>
 * {@code updatedAt} 이 주입된 시각으로 채워지는지 확인하려면 시각이 예측 가능해야 한다.
 * 무인자 {@code LocalDateTime.now()} 를 썼다면 이 검증 자체가 불가능하다.
 *
 * <p>Design Ref: §6.3 검사 순서, §4.3 전체 교체 수정, §8.4 L2 서비스, §12 D-25
 */
@ExtendWith(MockitoExtension.class)
class KlassServiceTest {

    private static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 9, 2, 15, 30);
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 9, 1, 10, 0);
    private static final LocalDate STARTS_ON = LocalDate.of(2026, 10, 1);
    private static final LocalDate ENDS_ON = LocalDate.of(2026, 12, 31);

    private static final Long OWNER_ID = 1L;
    private static final Long OTHER_ID = 2L;
    private static final Long KLASS_ID = 100L;

    @Mock
    private KlassCommandPort klassCommandPort;

    @Mock
    private KlassQueryPort klassQueryPort;

    @Mock
    private UserQueryPort userQueryPort;

    /** 고정 시각. {@code updatedAt} 이 주입된 시각으로 채워지는지 확인하려면 예측 가능해야 한다. */
    private final Clock clock = Clock.fixed(
            FIXED_NOW.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());

    private KlassService service;

    @BeforeEach
    void setUp() {
        service = new KlassService(klassCommandPort, klassQueryPort, userQueryPort, clock);
    }

    private static User user(Long id, String username) {
        User user = User.register(username, "hashed", Set.of(Role.ROLE_CREATOR), CREATED_AT);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    /** 개설자가 {@code OWNER_ID} 인 강의. 상태와 id 를 지정한다. */
    private static Klass klass(KlassStatus status) {
        Klass klass = Klass.open(user(OWNER_ID, "owner"), "원래 제목", "원래 내용",
                new BigDecimal("50000"), 30, STARTS_ON, ENDS_ON, 7, CREATED_AT);
        ReflectionTestUtils.setField(klass, "id", KLASS_ID);
        if (status == KlassStatus.OPEN) {
            klass.publish(CREATED_AT);
        } else if (status == KlassStatus.CLOSED) {
            klass.close(CREATED_AT);
        }
        return klass;
    }

    /**
     * 강의의 <b>현재 값 그대로</b>인 수정 명령 (D-25 전체 교체).
     *
     * <p>수정은 전 필드가 필수이므로 "아무것도 안 바꾸는 명령"은 존재하지 않는다.
     * 권한 검사처럼 값이 무엇인지가 중요하지 않은 테스트는 이것을 쓴다 — 값이 유효해야
     * 권한 검사 이후 단계에서 엉뚱하게 실패하지 않는다.
     *
     * <p><b>{@code cancellationPeriodDays} 는 {@link #klass} 의 값(7)과 같아야 한다.</b>
     * 다른 값을 넣으면 {@code DRAFT} 아닌 강의에서 {@code CANCELLATION_PERIOD_NOT_EDITABLE}
     * (409)가 나서, 권한을 검증하려던 테스트가 엉뚱한 이유로 실패한다 (D-26).
     */
    private static UpdateKlassCommand sameValueUpdate(Long requesterId) {
        return new UpdateKlassCommand(KLASS_ID, requesterId, "원래 제목", "원래 내용",
                new BigDecimal("50000"), 30, STARTS_ON, ENDS_ON, 7);
    }

    private static ErrorCode errorCodeOf(Throwable e) {
        return ((BusinessException) e).errorCode();
    }

    @Nested
    @DisplayName("등록")
    class Register {

        @Test
        @DisplayName("DRAFT 로 저장하고 주입된 Clock 으로 시각을 채운다")
        void registersAsDraft() {
            given(userQueryPort.findById(OWNER_ID)).willReturn(Optional.of(user(OWNER_ID, "owner")));
            given(klassCommandPort.save(any(Klass.class))).willAnswer(i -> i.getArgument(0));

            KlassResult result = service.register(new RegisterKlassCommand(
                    OWNER_ID, "새 강의", "새 내용", new BigDecimal("10000"),
                    20, STARTS_ON, ENDS_ON, 7));

            assertThat(result.status()).isEqualTo(KlassStatus.DRAFT);
            assertThat(result.enrollmentCount()).isZero();
            assertThat(result.createdAt()).isEqualTo(FIXED_NOW);
            assertThat(result.updatedAt()).isEqualTo(FIXED_NOW);
        }

        @Test
        @DisplayName("사용자가 사라졌으면 USER_NOT_FOUND — 강의 도메인의 사건이 아니다")
        void rejectsMissingCreator() {
            given(userQueryPort.findById(OWNER_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.register(new RegisterKlassCommand(
                    OWNER_ID, "새 강의", "새 내용", BigDecimal.ONE,
                    20, STARTS_ON, ENDS_ON, null)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(KlassServiceTest::errorCodeOf)
                    .isEqualTo(UserError.USER_NOT_FOUND);

            verify(klassCommandPort, never()).save(any());
        }
    }

    /**
     * <b>이 클래스가 Context Anchor 의 RISK 를 정면으로 검증한다.</b>
     *
     * <p>"{@code ROLE_CREATOR} 만 검사하면 남의 강의를 수정할 수 있다" — 권한은
     * {@code SecurityConfig} 가 보고, 소유권은 여기서만 본다.
     */
    @Nested
    @DisplayName("권한 검사와 그 순서")
    class Authorization {

        @Test
        @DisplayName("남의 공개 강의를 수정하면 403 이다 — 존재는 이미 공개돼 있어 숨길 것이 없다")
        void rejectsOtherCreatorsPublishedKlass() {
            given(klassQueryPort.findById(KLASS_ID))
                    .willReturn(Optional.of(klass(KlassStatus.OPEN)));

            assertThatThrownBy(() -> service.update(sameValueUpdate(OTHER_ID)))
                    .extracting(KlassServiceTest::errorCodeOf)
                    .isEqualTo(KlassError.NOT_KLASS_OWNER);
        }

        @Test
        @DisplayName("남의 DRAFT 를 수정하면 403 이 아니라 404 다 — 초안의 존재가 새면 안 된다")
        void hidesOtherCreatorsDraft() {
            given(klassQueryPort.findById(KLASS_ID))
                    .willReturn(Optional.of(klass(KlassStatus.DRAFT)));

            assertThatThrownBy(() -> service.update(sameValueUpdate(OTHER_ID)))
                    .as("가시성 검사가 소유권 검사보다 먼저 와야 한다 (Design §6.3)")
                    .extracting(KlassServiceTest::errorCodeOf)
                    .isEqualTo(KlassError.KLASS_NOT_FOUND);
        }

        @Test
        @DisplayName("상태 변경도 같은 순서로 검사한다")
        void statusChangeUsesSameOrder() {
            given(klassQueryPort.findById(KLASS_ID))
                    .willReturn(Optional.of(klass(KlassStatus.DRAFT)));

            assertThatThrownBy(() -> service.changeStatus(
                    new ChangeKlassStatusCommand(KLASS_ID, OTHER_ID, KlassStatus.OPEN)))
                    .extracting(KlassServiceTest::errorCodeOf)
                    .isEqualTo(KlassError.KLASS_NOT_FOUND);
        }

        @Test
        @DisplayName("없는 강의는 404 다")
        void missingKlass() {
            given(klassQueryPort.findById(KLASS_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.update(sameValueUpdate(OWNER_ID)))
                    .extracting(KlassServiceTest::errorCodeOf)
                    .isEqualTo(KlassError.KLASS_NOT_FOUND);
        }

        @Test
        @DisplayName("본인 DRAFT 는 통과한다 — 가시성 검사가 개설자를 막지 않는다")
        void ownerPassesOnOwnDraft() {
            given(klassQueryPort.findById(KLASS_ID))
                    .willReturn(Optional.of(klass(KlassStatus.DRAFT)));

            KlassResult result = service.changeStatus(
                    new ChangeKlassStatusCommand(KLASS_ID, OWNER_ID, KlassStatus.OPEN));

            assertThat(result.status()).isEqualTo(KlassStatus.OPEN);
        }
    }

    /**
     * <b>수정은 전체 교체다</b> (Design D-25).
     *
     * <p>부분 수정 시절의 계약("지정한 필드만 바뀐다" / "빈 요청은 아무것도 안 바꾼다")은
     * 성립하지 않는다. 클라이언트 수정 화면이 강의의 전체 값을 들고 있어 변경되지 않은
     * 필드도 그대로 실어 보내므로, 명령에 도달한 값은 전부 "이 값으로 만들라"는 지시다.
     */
    @Nested
    @DisplayName("수정 (전체 교체)")
    class FullReplaceUpdate {

        @Test
        @DisplayName("전 필드가 명령의 값으로 교체된다")
        void replacesEveryField() {
            given(klassQueryPort.findById(KLASS_ID))
                    .willReturn(Optional.of(klass(KlassStatus.DRAFT)));

            KlassResult result = service.update(new UpdateKlassCommand(
                    KLASS_ID, OWNER_ID, "바뀐 제목", "바뀐 내용",
                    new BigDecimal("70000"), 40,
                    STARTS_ON.plusDays(1), ENDS_ON.plusDays(1), 14));

            assertThat(result.title()).isEqualTo("바뀐 제목");
            assertThat(result.description()).isEqualTo("바뀐 내용");
            assertThat(result.price()).isEqualByComparingTo("70000");
            assertThat(result.capacity()).isEqualTo(40);
            assertThat(result.startsOn()).isEqualTo(STARTS_ON.plusDays(1));
            assertThat(result.endsOn()).isEqualTo(ENDS_ON.plusDays(1));
            assertThat(result.cancellationPeriodDays()).isEqualTo(14);
            assertThat(result.updatedAt()).isEqualTo(FIXED_NOW);
        }

        /**
         * <b>전체 교체 시맨틱의 핵심 계약이다.</b> 값을 비교해 "실질적 변경이 없다"고
         * 판단하고 넘어가면, 클라이언트가 저장했다고 믿는 시점과 이력이 어긋난다.
         */
        @Test
        @DisplayName("기존 값과 동일한 값을 보내도 반영되고 updatedAt 이 갱신된다")
        void sameValuesStillRefreshTimestamp() {
            given(klassQueryPort.findById(KLASS_ID))
                    .willReturn(Optional.of(klass(KlassStatus.DRAFT)));

            KlassResult result = service.update(sameValueUpdate(OWNER_ID));

            assertThat(result.title()).isEqualTo("원래 제목");
            assertThat(result.updatedAt())
                    .as("매 요청이 수정이다 — 값 비교로 시각을 아끼지 않는다 (D-25)")
                    .isEqualTo(FIXED_NOW);
        }

        @Test
        @DisplayName("취소 가능 기간을 null 로 보내면 전역 기본값으로 되돌아간다")
        void nullCancellationPeriodFallsBackToGlobalDefault() {
            given(klassQueryPort.findById(KLASS_ID))
                    .willReturn(Optional.of(klass(KlassStatus.DRAFT)));

            KlassResult result = service.update(new UpdateKlassCommand(
                    KLASS_ID, OWNER_ID, "원래 제목", "원래 내용",
                    new BigDecimal("50000"), 30, STARTS_ON, ENDS_ON, null));

            assertThat(result.cancellationPeriodDays())
                    .as("선택 필드라 null 이 곧 '전역 기본값을 따른다'다 (Design §10)")
                    .isNull();
        }

        @Test
        @DisplayName("종료일이 시작일보다 빠르면 INVALID_KLASS_PERIOD — 조립 없이 직접 검사된다")
        void rejectsInvertedPeriod() {
            given(klassQueryPort.findById(KLASS_ID))
                    .willReturn(Optional.of(klass(KlassStatus.DRAFT)));

            assertThatThrownBy(() -> service.update(new UpdateKlassCommand(
                    KLASS_ID, OWNER_ID, "원래 제목", "원래 내용",
                    new BigDecimal("50000"), 30,
                    ENDS_ON.plusDays(1), ENDS_ON, 7)))
                    .extracting(KlassServiceTest::errorCodeOf)
                    .isEqualTo(KlassError.INVALID_KLASS_PERIOD);
        }

        /**
         * <b>이 테스트가 없으면 "OPEN 강의 수정 불가" 회귀를 아무도 잡지 못한다.</b>
         *
         * <p>취소 가능 기간은 {@code DRAFT} 에서만 바꿀 수 있다(D-26). 그런데 수정은 전체
         * 교체이므로 모든 요청이 그 필드를 <b>항상 싣고 오고</b>, {@link KlassService#update}
         * 는 {@code changeCancellationPeriodDays} 를 <b>무조건 호출</b>한다. 도메인이 상태만
         * 보고 무조건 거부하면 여기서 409 가 나면서 <b>{@code OPEN} 강의는 제목 하나도 고칠
         * 수 없게 된다.</b> 같은 값 재전송이 no-op 이어야 이 경로가 살아 있다.
         */
        @Test
        @DisplayName("OPEN 강의도 취소 기간을 현재 값 그대로 보내면 수정된다 — 전체 교체 규약과의 정합")
        void openKlassIsUpdatableWhenCancellationPeriodIsUnchanged() {
            given(klassQueryPort.findById(KLASS_ID))
                    .willReturn(Optional.of(klass(KlassStatus.OPEN)));

            // 강의의 현재 취소 기간은 7 이다. 바꾸지 않은 필드에 현재 값을 그대로 싣는 것이
            // 전체 교체 규약의 정상 동작이다 (D-25)
            KlassResult result = service.update(new UpdateKlassCommand(
                    KLASS_ID, OWNER_ID, "바뀐 제목", "바뀐 내용",
                    new BigDecimal("70000"), 40, STARTS_ON, ENDS_ON, 7));

            assertThat(result.title()).isEqualTo("바뀐 제목");
            assertThat(result.capacity()).isEqualTo(40);
            assertThat(result.cancellationPeriodDays()).isEqualTo(7);
            assertThat(result.status()).isEqualTo(KlassStatus.OPEN);
            assertThat(result.updatedAt())
                    .as("취소 기간이 no-op 이어도 나머지 change* 가 시각을 갱신한다")
                    .isEqualTo(FIXED_NOW);
        }

        @Test
        @DisplayName("OPEN 강의의 취소 기간을 다른 값으로 바꾸면 CANCELLATION_PERIOD_NOT_EDITABLE")
        void rejectsCancellationPeriodChangeOnOpenKlass() {
            given(klassQueryPort.findById(KLASS_ID))
                    .willReturn(Optional.of(klass(KlassStatus.OPEN)));

            assertThatThrownBy(() -> service.update(new UpdateKlassCommand(
                    KLASS_ID, OWNER_ID, "원래 제목", "원래 내용",
                    new BigDecimal("50000"), 30, STARTS_ON, ENDS_ON, 14)))
                    .as("신청자가 생긴 뒤에는 취소 조건을 사후에 바꿀 수 없다 (D-26)")
                    .extracting(KlassServiceTest::errorCodeOf)
                    .isEqualTo(KlassError.CANCELLATION_PERIOD_NOT_EDITABLE);
        }

        @Test
        @DisplayName("도메인 규칙 위반은 그대로 전파된다 — 서비스가 삼키지 않는다")
        void propagatesDomainViolation() {
            Klass klass = klass(KlassStatus.OPEN);
            ReflectionTestUtils.setField(klass, "enrollmentCount", 10);
            given(klassQueryPort.findById(KLASS_ID)).willReturn(Optional.of(klass));

            assertThatThrownBy(() -> service.update(new UpdateKlassCommand(
                    KLASS_ID, OWNER_ID, "원래 제목", "원래 내용",
                    new BigDecimal("50000"), 5, STARTS_ON, ENDS_ON, 7)))
                    .extracting(KlassServiceTest::errorCodeOf)
                    .isEqualTo(KlassError.CAPACITY_BELOW_ENROLLMENT);
        }
    }

    @Nested
    @DisplayName("상태 변경")
    class StatusChange {

        @Test
        @DisplayName("DRAFT 를 목표로 하면 도메인까지 가지 않고 거부된다")
        void rejectsDraftAsTarget() {
            given(klassQueryPort.findById(KLASS_ID))
                    .willReturn(Optional.of(klass(KlassStatus.OPEN)));

            assertThatThrownBy(() -> service.changeStatus(
                    new ChangeKlassStatusCommand(KLASS_ID, OWNER_ID, KlassStatus.DRAFT)))
                    .extracting(KlassServiceTest::errorCodeOf)
                    .isEqualTo(KlassError.INVALID_KLASS_STATUS_TRANSITION);
        }

        @Test
        @DisplayName("DRAFT → CLOSED 개설 철회가 통과한다")
        void withdrawsDraft() {
            given(klassQueryPort.findById(KLASS_ID))
                    .willReturn(Optional.of(klass(KlassStatus.DRAFT)));

            KlassResult result = service.changeStatus(
                    new ChangeKlassStatusCommand(KLASS_ID, OWNER_ID, KlassStatus.CLOSED));

            assertThat(result.status()).isEqualTo(KlassStatus.CLOSED);
            assertThat(result.updatedAt()).isEqualTo(FIXED_NOW);
        }
    }

    @Nested
    @DisplayName("상세 조회")
    class FindDetail {

        @Test
        @DisplayName("비로그인도 공개 강의를 본다 — viewerId 가 null 이어도 NPE 가 없다")
        void anonymousSeesPublished() {
            given(klassQueryPort.findById(KLASS_ID))
                    .willReturn(Optional.of(klass(KlassStatus.OPEN)));

            KlassResult result = service.findById(KLASS_ID, null);

            assertThat(result.id()).isEqualTo(KLASS_ID);
        }

        @Test
        @DisplayName("비로그인이 타인 DRAFT 를 조회하면 404 다")
        void anonymousCannotSeeDraft() {
            given(klassQueryPort.findById(KLASS_ID))
                    .willReturn(Optional.of(klass(KlassStatus.DRAFT)));

            assertThatThrownBy(() -> service.findById(KLASS_ID, null))
                    .extracting(KlassServiceTest::errorCodeOf)
                    .isEqualTo(KlassError.KLASS_NOT_FOUND);
        }

        @Test
        @DisplayName("개설자는 자기 DRAFT 를 본다")
        void ownerSeesOwnDraft() {
            given(klassQueryPort.findById(KLASS_ID))
                    .willReturn(Optional.of(klass(KlassStatus.DRAFT)));

            assertThat(service.findById(KLASS_ID, OWNER_ID).status())
                    .isEqualTo(KlassStatus.DRAFT);
        }

    }
}
