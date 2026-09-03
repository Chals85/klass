package com.toby.klass.klass.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 취소 정책 판정 (L1).
 *
 * <h2>왜 엔티티 없이 테스트하는가</h2>
 * 판정을 값 객체로 뽑은 이득이 바로 이것이다 (Design D-37). {@code Klass} 와
 * {@code Enrollment} 를 만들지 않고 record 하나로 <b>경계를 정밀하게</b> 찔러볼 수 있다.
 * 엔티티를 세워야 했다면 경계 케이스마다 강의·사용자·신청 3개를 조립해야 했다.
 *
 * <p><b>경계가 이 클래스의 전부다.</b> 취소 가능 여부는 "며칠 지났나"라는 연속량을 boolean
 * 으로 자르는 판정이라, 버그는 항상 자르는 지점에서 난다.
 *
 * <p>Design Ref: enrollment-management §3.2.2 · §9.2, FR-11 · FR-20
 */
@DisplayName("CancellationPolicy — 취소 판정 2관문")
class CancellationPolicyTest {

    private static final LocalDate ENDS_ON = LocalDate.of(2026, 12, 31);
    private static final LocalDateTime CONFIRMED_AT = LocalDateTime.of(2026, 10, 1, 10, 0);

    private static CancellationPolicy policy(int periodDays) {
        return new CancellationPolicy(ENDS_ON, periodDays);
    }

    @Nested
    @DisplayName("관문 1 — 강의 종료일 (FR-20)")
    class KlassFinished {

        @Test
        @DisplayName("종료일 당일은 아직 끝나지 않았다")
        void notFinishedOnEndDate() {
            assertThat(policy(7).isKlassFinished(ENDS_ON))
                    .as("12/31 종료 강의는 12/31 에도 진행 중이다")
                    .isFalse();
        }

        @Test
        @DisplayName("종료일 다음 날부터 끝난 것이다")
        void finishedAfterEndDate() {
            assertThat(policy(7).isKlassFinished(ENDS_ON.plusDays(1))).isTrue();
        }

        @Test
        @DisplayName("종료일 전날은 당연히 진행 중이다")
        void notFinishedBefore() {
            assertThat(policy(7).isKlassFinished(ENDS_ON.minusDays(1))).isFalse();
        }
    }

    @Nested
    @DisplayName("관문 2 — 결제일 기준 취소 가능 기간 (FR-11)")
    class WithinPeriod {

        @Test
        @DisplayName("결제 직후는 기간 안이다")
        void justConfirmed() {
            assertThat(policy(7).isWithinPeriod(CONFIRMED_AT, CONFIRMED_AT)).isTrue();
        }

        @Test
        @DisplayName("경계를 포함한다 — 결제 + 7일 정확히 그 시각까지 취소할 수 있다")
        void boundaryIsInclusive() {
            LocalDateTime exactly = CONFIRMED_AT.plusDays(7);

            assertThat(policy(7).isWithinPeriod(CONFIRMED_AT, exactly))
                    .as("배제하면 '7일 이내 취소 가능'이라 안내하고 7일째에 거부하는 셈이 된다")
                    .isTrue();
        }

        @Test
        @DisplayName("경계에서 1초만 지나도 거부다")
        void oneSecondPastBoundary() {
            LocalDateTime justPast = CONFIRMED_AT.plusDays(7).plusSeconds(1);

            assertThat(policy(7).isWithinPeriod(CONFIRMED_AT, justPast)).isFalse();
        }

        @Test
        @DisplayName("기간 0 이면 결제 시각까지만 — 사실상 즉시 취소 불가다")
        void zeroPeriod() {
            assertThat(policy(0).isWithinPeriod(CONFIRMED_AT, CONFIRMED_AT)).isTrue();
            assertThat(policy(0).isWithinPeriod(CONFIRMED_AT, CONFIRMED_AT.plusSeconds(1)))
                    .isFalse();
        }

        @Test
        @DisplayName("기간 비교는 분 단위로 정확하다 — 날짜만 보지 않는다")
        void comparesTimeNotJustDate() {
            // 같은 날짜(10/8)지만 결제 시각(10:00)을 넘긴 09:59 와 10:01 이 갈려야 한다
            assertThat(policy(7).isWithinPeriod(CONFIRMED_AT,
                    LocalDateTime.of(2026, 10, 8, 9, 59))).isTrue();
            assertThat(policy(7).isWithinPeriod(CONFIRMED_AT,
                    LocalDateTime.of(2026, 10, 8, 10, 1)))
                    .as("날짜만 비교했다면 둘 다 통과해 하루가 공짜로 늘어난다")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("Klass 가 정책을 만든다 — COALESCE 가 한 곳에 모인다")
    class Factory {

        @Test
        @DisplayName("강의가 기간을 지정했으면 그것을 쓴다")
        void usesKlassValue() {
            Klass klass = KlassFixture.withCancellationPeriod(3);

            CancellationPolicy policy = klass.cancellationPolicy(7);

            assertThat(policy.periodDays()).isEqualTo(3);
            assertThat(policy.klassEndsOn()).isEqualTo(KlassFixture.ENDS_ON);
        }

        @Test
        @DisplayName("강의가 지정하지 않았으면(null) 전역 기본값을 쓴다")
        void fallsBackToDefault() {
            Klass klass = KlassFixture.withCancellationPeriod(null);

            assertThat(klass.cancellationPolicy(7).periodDays())
                    .as("null 은 '전역 기본값을 따른다'는 뜻이다")
                    .isEqualTo(7);
        }

        @Test
        @DisplayName("강의가 0 을 지정했으면 0 이다 — null 과 0 은 다르다")
        void zeroIsNotNull() {
            Klass klass = KlassFixture.withCancellationPeriod(0);

            assertThat(klass.cancellationPolicy(7).periodDays())
                    .as("0 을 전역 기본값으로 덮으면 '취소 불가' 정책을 표현할 수 없다")
                    .isZero();
        }
    }
}
