package com.toby.klass.enrollment.adapter.in.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.BDDMockito.willThrow;

import com.toby.klass.enrollment.application.port.in.ReapExpiredEnrollmentUseCase;
import java.lang.reflect.Constructor;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 만료 회수 스케줄러의 루프 (L2).
 *
 * <h2>여기서만 검증할 수 있는 것</h2>
 * <ol>
 *   <li><b>예외 격리</b> — 한 건이 터져도 나머지를 처리하는가. 이것이 없으면 만료 건
 *       하나가 <b>남은 대상 전부를 미처리로 만든다</b>. 서비스 테스트는 한 건만 보므로
 *       루프의 성질을 볼 수 없다</li>
 *   <li><b>계층 규칙</b> — 스케줄러가 out 포트를 직접 잡지 않는가. 잡으면
 *       {@code adapter.in → port.in} 규칙이 깨지는데 컴파일은 통과한다</li>
 * </ol>
 *
 * <p>스케줄 <b>주기</b>는 검증하지 않는다. {@code @Scheduled} 의 placeholder 는 Spring 이
 * 해석하므로 단위 테스트로는 볼 수 없고, 잘못돼도 기본값 {@code PT10M} 으로 대체돼 실질
 * 피해가 없다 (Design §10.2 의 받아들인 대가).
 *
 * <p>Design Ref: pending-expiry-reaper §5.1 · §8.5
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ExpiredEnrollmentScheduler — 회수 루프")
class ExpiredEnrollmentSchedulerTest {

    private static final Long FIRST = 1L;
    private static final Long SECOND = 2L;
    private static final Long THIRD = 3L;

    @Mock
    private ReapExpiredEnrollmentUseCase reapExpiredEnrollmentUseCase;

    @InjectMocks
    private ExpiredEnrollmentScheduler scheduler;

    @Test
    @DisplayName("후보 전건에 대해 회수를 시도한다")
    void reapsEveryTarget() {
        given(reapExpiredEnrollmentUseCase.findExpiredTargets())
                .willReturn(List.of(FIRST, SECOND, THIRD));

        scheduler.reap();

        then(reapExpiredEnrollmentUseCase).should().reapExpired(FIRST);
        then(reapExpiredEnrollmentUseCase).should().reapExpired(SECOND);
        then(reapExpiredEnrollmentUseCase).should().reapExpired(THIRD);
    }

    /**
     * <b>이 테스트가 FR-07 의 핵심이다.</b> 배치 루프에서 예외를 잡지 않으면 한 건의 실패가
     * 사이클을 통째로 멈춘다 — 그 뒤 대상은 다음 사이클로 밀리고, 원인이 지속되면 영영
     * 처리되지 않는다.
     */
    @Test
    @DisplayName("한 건이 실패해도 나머지를 계속 처리한다")
    void continuesAfterFailure() {
        given(reapExpiredEnrollmentUseCase.findExpiredTargets())
                .willReturn(List.of(FIRST, SECOND, THIRD));
        willThrow(new RuntimeException("락 획득 실패"))
                .given(reapExpiredEnrollmentUseCase).reapExpired(SECOND);

        assertThatCode(() -> scheduler.reap())
                .as("스케줄러 밖으로 예외가 나가면 다음 실행까지 로그만 남고 끝난다")
                .doesNotThrowAnyException();

        then(reapExpiredEnrollmentUseCase).should().reapExpired(THIRD);
    }

    @Test
    @DisplayName("후보가 없으면 회수를 시도하지 않는다")
    void doesNothingWhenNoTarget() {
        given(reapExpiredEnrollmentUseCase.findExpiredTargets()).willReturn(List.of());

        scheduler.reap();

        then(reapExpiredEnrollmentUseCase).should(never()).reapExpired(anyLong());
    }

    @Nested
    @DisplayName("계층 규칙")
    class LayerRule {

        /**
         * {@code adapter.in} 은 {@code port.in} 만 의존할 수 있다. 후보 조회를 유스케이스에
         * 두지 않으면 스케줄러가 {@code EnrollmentQueryPort}(out 포트)를 직접 주입해야 하는데,
         * <b>그래도 컴파일은 통과한다</b> — 생성자 시그니처로 못박는다 (Design D-48).
         */
        @Test
        @DisplayName("유스케이스 포트 하나만 주입받는다 — out 포트도 Clock 도 모른다")
        void dependsOnlyOnInboundPort() {
            Constructor<?>[] constructors = ExpiredEnrollmentScheduler.class.getConstructors();

            assertThat(constructors).hasSize(1);
            assertThat(constructors[0].getParameterTypes())
                    .as("여기에 QueryPort 나 Clock 이 끼면 계층이 뒤집힌 것이다")
                    .containsExactly(ReapExpiredEnrollmentUseCase.class);
        }
    }
}
