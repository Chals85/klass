package com.toby.klass.enrollment.application.port.in;

/**
 * 강의 마감 시 잔여 대기자 정리. ERD 정본 §4.8 5번.
 *
 * <h2>이 인터페이스가 존재하는 이유 — 유일한 서비스 간 의존</h2>
 * {@code CLOSED} 에서는 승격이 중단되고 {@code CLOSED → OPEN} 도 봉쇄돼 있어, 남은
 * {@code WAITING} 행은 <b>영구히 승격되지 않는 유령</b>이 된다. 그 정리는 {@code klass}
 * 도메인의 명령에서 트리거되지만 대상은 {@code waitlist} 다.
 *
 * <p>{@code KlassService} 가 {@code EnrollmentService} 를 <b>구현체가 아니라 이 인터페이스로</b>
 * 참조한다. 반대 방향({@code EnrollmentService → KlassService})은 없다 — 그쪽은 포트만
 * 참조하므로 서비스 간 의존 그래프가 DAG 로 유지된다 (Design D-29).
 *
 * <h2>⚠️ 트랜잭션 전파</h2>
 * 구현은 전파를 <b>명시하지 않는다.</b> 기본값 {@code REQUIRED} 여야 호출자가 이미 잡은
 * {@code klass} 행 락 안에서 실행된다. {@code REQUIRES_NEW} 로 걸면 자식이 새 트랜잭션을
 * 열고 <b>같은 행을 두고 자기 자신과 락 경합해 타임아웃까지 멈춘다.</b> 컴파일도 단일
 * 스레드 테스트도 통과하고 부하가 걸릴 때만 드러난다.
 */
public interface CancelRemainingWaitlistUseCase {

    /**
     * 강의의 잔여 {@code WAITING} 을 전부 {@code CANCELLED} 로 정리한다.
     *
     * <p>대기가 없으면 아무 일도 하지 않는다 — 호출자가 대기열 유무를 알 필요가 없다.
     *
     * @param klassId 마감된 강의
     */
    void cancelRemaining(Long klassId);
}
