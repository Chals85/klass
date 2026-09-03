package com.toby.klass.klass.domain;

import com.toby.klass.user.domain.Role;
import com.toby.klass.user.domain.User;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * L1 도메인 테스트가 공유하는 강의·사용자 조립기.
 *
 * <h2>왜 공용으로 뽑았는가</h2>
 * 수강신청 사이클의 L1 은 세 패키지({@code klass}·{@code enrollment}·{@code waitlist})에
 * 흩어지는데 셋 다 <b>같은 강의와 사용자</b>를 필요로 한다. 각자 조립하면 정원이나 종료일
 * 같은 값이 미묘하게 달라져, 테스트가 깨졌을 때 <b>규칙이 틀린 건지 픽스처가 다른 건지</b>
 * 구분하는 데 시간이 든다.
 *
 * <p>id 는 영속화 없이 리플렉션으로 채운다 — 소유권 판정({@code isOwnedBy})이 id 를 보는데,
 * 그것 하나 때문에 {@code @DataJpaTest} 를 띄우면 L1 이 L2 가 된다.
 *
 * <p>Design Ref: enrollment-management §9.2
 */
public final class KlassFixture {

    /** 고정 시각. CLAUDE.md 는 전 계층에서 무인자 {@code now()} 를 금지한다. */
    public static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 9, 1, 10, 0);

    public static final LocalDate STARTS_ON = LocalDate.of(2026, 10, 1);
    public static final LocalDate ENDS_ON = LocalDate.of(2026, 12, 31);

    public static final Long CREATOR_ID = 1L;
    public static final Long STUDENT_ID = 2L;
    public static final Long OTHER_ID = 3L;

    /** 기본 정원. 좌석 점유 테스트가 이 값을 기준으로 경계를 만든다. */
    public static final int CAPACITY = 10;

    private KlassFixture() {
    }

    public static User creator() {
        return user(CREATOR_ID, "creator", Role.ROLE_CREATOR);
    }

    public static User student() {
        return user(STUDENT_ID, "student", Role.ROLE_USER);
    }

    public static User other() {
        return user(OTHER_ID, "other", Role.ROLE_USER);
    }

    public static User user(Long id, String username, Role... roles) {
        User user = User.register(username, "hashed", Set.of(roles), CREATED_AT);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    /** {@code DRAFT} 강의. 취소 가능 기간 7일. */
    public static Klass draft() {
        return withCancellationPeriod(7);
    }

    /** {@code OPEN} 강의. 신청을 받을 수 있는 유일한 상태다. */
    public static Klass open() {
        Klass klass = draft();
        klass.publish(CREATED_AT);
        return klass;
    }

    /**
     * 취소 가능 기간을 지정한 {@code DRAFT} 강의.
     *
     * @param days {@code null} 이면 전역 기본값을 따른다는 뜻이다
     */
    public static Klass withCancellationPeriod(Integer days) {
        return Klass.open(creator(), "스프링 부트 입문", "처음 시작하는 스프링 부트",
                new BigDecimal("50000"), CAPACITY, STARTS_ON, ENDS_ON, days, CREATED_AT);
    }

    /**
     * 좌석이 {@code count} 만큼 점유된 {@code OPEN} 강의.
     *
     * <p><b>리플렉션이 아니라 실제 도메인 메서드를 쓴다.</b> 이전에는 증감 메서드가 없어
     * {@code ReflectionTestUtils} 로 필드를 직접 채웠는데, 그러면 테스트가 도메인 규칙
     * (정원 초과 거부)을 우회하는 상태를 만들 수 있어 픽스처가 현실에 없는 강의를 만든다.
     *
     * @throws com.toby.klass.common.domain.error.BusinessException {@code count} 가 정원을 넘는 경우
     */
    public static Klass withOccupiedSeats(int count) {
        Klass klass = open();
        for (int i = 0; i < count; i++) {
            klass.occupySeat();
        }
        return klass;
    }
}
