package com.toby.klass.waitlist.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toby.klass.common.domain.error.BusinessException;
import com.toby.klass.klass.domain.Klass;
import com.toby.klass.klass.domain.KlassFixture;
import com.toby.klass.waitlist.domain.error.WaitlistError;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 대기열 상태 전이와 판별 (L1).
 *
 * <h2>이 클래스가 지키는 것</h2>
 * <b>승격과 포기가 같은 {@code WAITING} 행을 두고 경합한다.</b> 먼저 커밋된 쪽이 상태를
 * 바꾸면 나머지는 반드시 거부돼야 하는데, 검사를 한쪽에만 두면 그 경합이 조용히 통과한다.
 * 아래 테스트가 <b>양쪽 모두</b> 종착 상태를 거부하는지 확인하는 이유다.
 *
 * <p>Design Ref: enrollment-management §3.2.3 · §9.2, ERD 정본 §4.9 3번
 */
@DisplayName("Waitlist — 승격과 포기")
class WaitlistTest {

    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 10, 1, 10, 0);
    private static final LocalDateTime PROMOTED_AT = CREATED_AT.plusHours(2);

    private static final Klass KLASS = KlassFixture.open();

    private static Waitlist waiting() {
        return Waitlist.enqueue(KLASS, KlassFixture.student(), 1, CREATED_AT);
    }

    private static Waitlist promoted() {
        Waitlist waitlist = waiting();
        waitlist.promote(PROMOTED_AT);
        return waitlist;
    }

    private static Waitlist cancelled() {
        Waitlist waitlist = waiting();
        waitlist.cancel();
        return waitlist;
    }

    @Nested
    @DisplayName("생성")
    class Creation {

        @Test
        @DisplayName("WAITING 으로 시작하고 승격 시각은 비어 있다")
        void startsAsWaiting() {
            Waitlist waitlist = waiting();

            assertThat(waitlist.getStatus()).isEqualTo(WaitlistStatus.WAITING);
            assertThat(waitlist.getPosition()).isEqualTo(1);
            assertThat(waitlist.getPromotedAt()).isNull();
            assertThat(waitlist.isWaiting()).isTrue();
        }
    }

    @Nested
    @DisplayName("승격")
    class Promote {

        @Test
        @DisplayName("WAITING → PROMOTED. 승격 시각이 기록된다")
        void promotesWaiting() {
            Waitlist waitlist = waiting();

            waitlist.promote(PROMOTED_AT);

            assertThat(waitlist.getStatus()).isEqualTo(WaitlistStatus.PROMOTED);
            assertThat(waitlist.getPromotedAt())
                    .as("ck_waitlist_promoted 가 PROMOTED 에 값을 강제한다")
                    .isEqualTo(PROMOTED_AT);
            assertThat(waitlist.isWaiting()).isFalse();
        }

        @Test
        @DisplayName("순번은 승격해도 그대로 남는다 — gap 으로 남기는 설계")
        void keepsPosition() {
            assertThat(promoted().getPosition()).isEqualTo(1);
        }

        @Test
        @DisplayName("이미 PROMOTED 면 거부한다 — 같은 대기자가 두 번 승격되지 않는다")
        void rejectsAlreadyPromoted() {
            Waitlist waitlist = promoted();

            assertThatThrownBy(() -> waitlist.promote(PROMOTED_AT.plusHours(1)))
                    .as("취소 2건이 동시에 나도 승격은 1건만 일어나야 한다 (정본 시나리오 6)")
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(WaitlistError.WAITLIST_NOT_WAITING);
        }

        @Test
        @DisplayName("포기한 대기는 승격할 수 없다")
        void rejectsCancelled() {
            Waitlist waitlist = cancelled();

            assertThatThrownBy(() -> waitlist.promote(PROMOTED_AT))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(WaitlistError.WAITLIST_NOT_WAITING);
        }

        @Test
        @DisplayName("거부되면 상태도 승격 시각도 그대로다")
        void leavesStateUntouchedOnRejection() {
            Waitlist waitlist = cancelled();

            assertThatThrownBy(() -> waitlist.promote(PROMOTED_AT))
                    .isInstanceOf(BusinessException.class);

            assertThat(waitlist.getStatus()).isEqualTo(WaitlistStatus.CANCELLED);
            assertThat(waitlist.getPromotedAt())
                    .as("ck_waitlist_promoted 는 PROMOTED 가 아닌데 값이 있는 것을 막지 않는다. "
                            + "도메인이 지켜야 한다")
                    .isNull();
        }
    }

    @Nested
    @DisplayName("포기 — 세 원인이 한 메서드를 쓴다")
    class Cancel {

        @Test
        @DisplayName("WAITING → CANCELLED")
        void cancelsWaiting() {
            Waitlist waitlist = waiting();

            waitlist.cancel();

            assertThat(waitlist.getStatus()).isEqualTo(WaitlistStatus.CANCELLED);
            assertThat(waitlist.isWaiting()).isFalse();
        }

        @Test
        @DisplayName("승격 시각을 남기지 않는다 — cancelled_at 컬럼 자체가 없다")
        void recordsNoTimestamp() {
            assertThat(cancelled().getPromotedAt()).isNull();
        }

        @Test
        @DisplayName("이미 PROMOTED 면 거부한다 — 배정된 좌석이 주인 없이 남으면 안 된다")
        void rejectsPromoted() {
            Waitlist waitlist = promoted();

            assertThatThrownBy(waitlist::cancel)
                    .as("승격이 먼저 커밋되면 포기 요청은 '이미 자리가 배정되었다'를 받아야 한다")
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(WaitlistError.WAITLIST_NOT_WAITING);
        }

        @Test
        @DisplayName("이미 CANCELLED 면 거부한다")
        void rejectsAlreadyCancelled() {
            Waitlist waitlist = cancelled();

            assertThatThrownBy(waitlist::cancel)
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(WaitlistError.WAITLIST_NOT_WAITING);
        }
    }

    @Nested
    @DisplayName("승격과 포기의 경합")
    class Contention {

        @Test
        @DisplayName("승격이 먼저면 포기가 막히고, 포기가 먼저면 승격이 막힌다 — 대칭이다")
        void whicheverCommitsFirstWins() {
            Waitlist promotedFirst = waiting();
            promotedFirst.promote(PROMOTED_AT);
            assertThatThrownBy(promotedFirst::cancel).isInstanceOf(BusinessException.class);

            Waitlist cancelledFirst = waiting();
            cancelledFirst.cancel();
            assertThatThrownBy(() -> cancelledFirst.promote(PROMOTED_AT))
                    .as("한쪽에만 검사를 두면 이 대칭이 깨지고 경합이 조용히 통과한다")
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("소유권")
    class Ownership {

        @Test
        @DisplayName("대기자 본인만 참이다. null 은 거짓이다")
        void identifiesOwner() {
            Waitlist waitlist = waiting();

            assertThat(waitlist.isOwnedBy(KlassFixture.STUDENT_ID)).isTrue();
            assertThat(waitlist.isOwnedBy(KlassFixture.OTHER_ID)).isFalse();
            assertThat(waitlist.isOwnedBy(null)).isFalse();
        }
    }
}
