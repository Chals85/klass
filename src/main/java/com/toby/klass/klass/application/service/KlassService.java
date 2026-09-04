package com.toby.klass.klass.application.service;

import com.toby.klass.klass.application.dto.ChangeKlassStatusCommand;
import com.toby.klass.common.application.dto.CursorPageResult;
import com.toby.klass.enrollment.application.port.in.CancelRemainingWaitlistUseCase;
import com.toby.klass.klass.application.dto.KlassQuery;
import com.toby.klass.klass.application.dto.KlassResult;
import com.toby.klass.klass.application.dto.KlassSummaryResult;
import com.toby.klass.klass.application.dto.RegisterKlassCommand;
import com.toby.klass.klass.application.dto.UpdateKlassCommand;
import com.toby.klass.klass.application.port.in.ChangeKlassStatusUseCase;
import com.toby.klass.klass.application.port.in.FindKlassUseCase;
import com.toby.klass.klass.application.port.in.ListKlassUseCase;
import com.toby.klass.klass.application.port.in.RegisterKlassUseCase;
import com.toby.klass.klass.application.port.in.UpdateKlassUseCase;
import com.toby.klass.klass.application.port.out.KlassCommandPort;
import com.toby.klass.klass.application.port.out.KlassQueryPort;
import com.toby.klass.klass.domain.Klass;
import com.toby.klass.klass.domain.error.KlassError;
import com.toby.klass.user.application.port.out.UserQueryPort;
import com.toby.klass.user.domain.User;
import com.toby.klass.user.domain.error.UserError;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 강의 유즈케이스 구현.
 *
 * <h2>이 서비스가 하는 일과 하지 않는 일</h2>
 * <b>판단은 도메인이 하고, 여기서는 조립만 한다.</b> 전이 가부·정원 축소 방어·가시성 판정은
 * {@link Klass} 가 알고 있고, 서비스는 "누가 요청했는가"를 그 판단에 연결한다.
 *
 * <h2>검사 순서가 보안이다</h2>
 * {@link #update}·{@link #changeStatus} 는 <b>존재 → 가시성 → 소유권</b> 순으로 검사한다
 * ({@link #loadForCommand}). {@link #register} 는 대상 강의가 없어 이 경로를 타지 않는다.
 * 가시성과 소유권이 뒤바뀌면 <b>타인의 초안이 있다는 사실이 403 으로 새어나간다</b> —
 * 404 여야 할 응답이 "그 강의는 있는데 네 것이 아니다"를 알려주는 셈이다.
 * {@code DomainAuthenticationProvider} 가 비밀번호 검증을 계정 상태 검사보다 먼저 하도록
 * 순서를 보장하는 것과 같은 종류의 결합이며, <b>컴파일도 테스트도(순서 케이스를 안 쓰면)
 * 통과한다.</b>
 *
 * <h2>{@code Clock} 주입</h2>
 * 도메인이 Spring 을 모르므로 {@code @CreatedDate} 를 쓸 수 없다. 시각은 이 계층이 만들어
 * 파라미터로 넘긴다. 무인자 {@code LocalDateTime.now()} 는 금지다 — 테스트에서 시각을
 * 고정할 수 없어진다.
 *
 * <p>Design Ref: §2.1 컴포넌트 구조, §6.3 검사 순서, §3.5 가시성
 */
@Service
@Transactional(readOnly = true)
public class KlassService implements RegisterKlassUseCase, UpdateKlassUseCase,
        ChangeKlassStatusUseCase, FindKlassUseCase, ListKlassUseCase {

    private final KlassCommandPort klassCommandPort;
    private final KlassQueryPort klassQueryPort;
    private final UserQueryPort userQueryPort;

    /**
     * 마감 시 잔여 대기자 정리. <b>이 서비스의 유일한 서비스 간 의존</b>이며 단방향이다 —
     * 구현체({@code EnrollmentService})는 이쪽을 참조하지 않고 포트만 본다 (Design D-29).
     *
     * <p><b>전파를 건드리지 말 것.</b> 기본값 {@code REQUIRED} 여야 아래에서 잡은
     * {@code klass} 락 안에서 실행된다. {@code REQUIRES_NEW} 로 바꾸면 자식이 새 트랜잭션을
     * 열고 같은 행을 두고 자기 자신과 락 경합해 타임아웃까지 멈춘다.
     */
    private final CancelRemainingWaitlistUseCase cancelRemainingWaitlistUseCase;

    private final Clock clock;

    public KlassService(KlassCommandPort klassCommandPort,
                        KlassQueryPort klassQueryPort,
                        UserQueryPort userQueryPort,
                        CancelRemainingWaitlistUseCase cancelRemainingWaitlistUseCase,
                        Clock clock) {
        this.klassCommandPort = klassCommandPort;
        this.klassQueryPort = klassQueryPort;
        this.userQueryPort = userQueryPort;
        this.cancelRemainingWaitlistUseCase = cancelRemainingWaitlistUseCase;
        this.clock = clock;
    }

    @Override
    @Transactional
    public KlassResult register(RegisterKlassCommand command) {
        // 토큰은 유효한데 그 사이 사용자가 사라진 경우다. 강의 도메인의 사건이 아니라
        // 사용자 도메인의 에러 코드를 그대로 쓴다
        User creator = userQueryPort.findById(command.creatorId())
                .orElseThrow(UserError.USER_NOT_FOUND::toException);

        Klass klass = Klass.open(creator, command.title(), command.description(),
                command.price(), command.capacity(),
                command.startsOn(), command.endsOn(),
                command.cancellationPeriodDays(), now());

        return KlassResult.from(klassCommandPort.save(klass));
    }

    /**
     * 강의를 <b>전체 교체</b>한다 (Design D-25).
     *
     * <h4>전 필드를 무조건 적용한다</h4>
     * 명령의 모든 필드가 필수이므로 "이 필드는 바꾸지 않는다"라는 경우가 없다. 조건 분기가
     * 없는 것은 누락이 아니라 계약이다 — 클라이언트가 전체 값을 실어 보내고, 그중 일부가
     * 비어 있으면 {@code adapter.in} 의 검증이 이미 400 으로 거부했다.
     *
     * <p>수강 기간은 두 날짜가 <b>항상 함께</b> 오므로 {@code changePeriod} 에 그대로 넘긴다.
     * 현재 값으로 빈 쪽을 채우는 조립이 필요하지 않다.
     *
     * <p>{@code cancellationPeriodDays} 도 무조건 적용한다 — <b>{@code null} 을 보내면
     * {@code null} 이 세팅되어 전역 기본값으로 되돌아간다.</b> 전체 교체 시맨틱에서는
     * 그것이 "이 강의는 전역 기본값을 따른다"는 의사 표시이므로 자연스러운 결과다.
     *
     * <h4>{@code updatedAt} 은 값이 바뀌지 않았어도 갱신된다</h4>
     * 전체 교체이므로 <b>매 요청이 수정</b>이다. 기존 값과 동일한 값을 보내도 "그 값으로
     * 저장하라"는 지시이며, 서버가 값을 비교해 "실질적 변경이 없었다"고 판단해 시각을
     * 남기지 않으면 <b>클라이언트가 저장했다고 믿는 시점과 이력이 어긋난다.</b>
     * 부분 수정 시절에는 "바꿀 것이 없는 요청"이 성립해 그때만 시각을 보존했지만,
     * 지금은 그런 요청 자체가 없다.
     */
    @Override
    @Transactional
    public KlassResult update(UpdateKlassCommand command) {
        Klass klass = loadForCommand(command.klassId(), command.requesterId());

        LocalDateTime now = now();

        // 제목은 언제나 바꿀 수 있다 — 오타 수정을 막을 이유가 없다
        klass.changeTitle(command.title(), now);

        // 나머지는 DRAFT 에서만. 공개된 뒤에는 요청에 값이 실려 와도 무시한다.
        // 거부(409)가 아니라 무시인 이유: 수정 화면이 전체 값을 들고 있어 변경하지 않은
        // 필드도 그대로 재전송하므로, 거부하면 제목만 바꾸려는 정상 요청이 막힌다.
        // 클라이언트는 응답에 실린 실제 저장값으로 무엇이 반영됐는지 확인한다 (D-28)
        if (klass.isFullyEditable()) {
            klass.changeDescription(command.description(), now);
            klass.changePrice(command.price(), now);
            klass.changeCapacity(command.capacity(), now);
            klass.changePeriod(command.startsOn(), command.endsOn(), now);
            klass.changeCancellationPeriodDays(command.cancellationPeriodDays(), now);
        }

        // save 를 부르지 않는다 — 영속 컨텍스트의 변경 감지가 커밋 시점에 UPDATE 를 만든다
        return KlassResult.from(klass);
    }

    @Override
    @Transactional
    public KlassResult changeStatus(ChangeKlassStatusCommand command) {
        Klass klass = loadForCommand(command.klassId(), command.requesterId());
        LocalDateTime now = now();

        // 목표 상태로 메서드를 고를 뿐, 전이 가부는 도메인이 판단한다 (Design §4.3)
        switch (command.status()) {
            case OPEN -> klass.publish(now);
            case CLOSED -> {
                klass.close(now);
                // 마감된 강의의 잔여 WAITING 은 영구히 승격되지 않는 유령이 된다.
                // CLOSED 에서는 승격이 중단되고 CLOSED → OPEN 도 봉쇄돼 있기 때문이다
                // (ERD 정본 §4.8 5번). 정리 대상이 waitlist 라 위임한다 (D-29)
                cancelRemainingWaitlistUseCase.cancelRemaining(klass.getId());
            }
            // DRAFT 로 되돌아가는 메서드는 존재하지 않는다.
            // CLOSED → DRAFT 는 ERD 정본도 금지하고, OPEN → DRAFT 는 D-18 로 차단했다
            case DRAFT -> throw KlassError.INVALID_KLASS_STATUS_TRANSITION.toException();
        }

        return KlassResult.from(klass);
    }

    @Override
    public KlassResult findById(Long klassId, Long viewerId) {
        Klass klass = klassQueryPort.findById(klassId)
                .orElseThrow(KlassError.KLASS_NOT_FOUND::toException);

        // 보이지 않으면 없는 것과 같이 답한다. 403 은 "그 강의는 존재한다"를 알려준다
        if (!klass.isVisibleTo(viewerId)) {
            throw KlassError.KLASS_NOT_FOUND.toException();
        }
        return KlassResult.from(klass);
    }

    @Override
    public CursorPageResult<KlassSummaryResult> listPublic(KlassQuery query) {
        return klassQueryPort.findPublicPage(query).map(KlassSummaryResult::from);
    }

    @Override
    public CursorPageResult<KlassSummaryResult> listByCreator(Long creatorId, KlassQuery query) {
        return klassQueryPort.findCreatorPage(creatorId, query).map(KlassSummaryResult::from);
    }

    /**
     * 명령 대상 강의를 락과 함께 읽고 권한을 검사한다.
     *
     * <h4>순서를 바꾸지 말 것</h4>
     * <ol>
     *   <li><b>존재</b> — 없으면 404</li>
     *   <li><b>가시성</b> — 보이지 않으면 <b>404</b>. 타인의 초안은 존재를 드러내지 않는다</li>
     *   <li><b>소유권</b> — 남의 (공개된) 강의면 403. 여기서는 존재가 이미 공개돼 있어
     *       숨길 것이 없고, 404 로 답하면 개설자 본인도 못 찾는 것처럼 읽힌다</li>
     * </ol>
     *
     * <p>2와 3이 뒤바뀌면 타인의 {@code DRAFT} 에 대해 403 이 나가면서 <b>그 초안의 존재가
     * 드러난다.</b>
     *
     * <h4>배타 락으로 읽는다 (D-21 해소)</h4>
     * ERD 정본 §4.1 이 <b>"정원과 관련된 모든 트랜잭션은 {@code klass} 단일 행을 첫 락으로
     * 잡는다"</b>로 순서를 고정했고, 강의 명령의 진입점이 이 메서드다.
     *
     * <p>klass-management 사이클에서는 이 락을 <b>일부러 걷어냈다</b> — 직렬화할 상대가
     * 없었기 때문이다. 수강신청이 붙으면서 실제 경합 상대가 생겼다.
     *
     * <p><b>위험한 것은 {@code changeCapacity} 가 아니라 {@link #changeStatus} 다.</b>
     * {@code changeCapacity} 는 {@code isFullyEditable()}({@code status == DRAFT}) 분기
     * 안에서만 불리고 {@code DRAFT} 는 신청을 받지 못하므로 {@code enrollment_count} 가
     * 항상 0 이다 — 경합할 상대가 구조적으로 없다 (D-33 과 같은 근거).
     *
     * <pre>{@code
     * OPEN 강의, 정원 10, 현재 9명, 대기자 1명
     *   [크리에이터] CLOSED 전이 → cancelRemaining 이 잔여 WAITING 을 정리
     *   [수강생]     10번째 신청 / 다른 수강생의 취소 → 승격 시도
     * }</pre>
     *
     * <p>이 둘이 같은 {@code klass} 행을 두고 부딪힌다. 락이 없으면 마감 뒤에 승격이
     * 일어나 명단이 흔들리거나, 정리된 대기자가 되살아난다. ERD 정본 §4.1 이 순서를
     * 고정한 이유가 이것이다.
     *
     * <h4>개설자가 프록시로 온다</h4>
     * 락 조회는 {@code @EntityGraph} 를 붙이지 않는다 — 조인하면 {@code users} 행까지 잠겨
     * "락 대상은 {@code klass} 단일 행" 규약이 깨진다. 그래서 {@code KlassResult.from} 이
     * {@code creator.getUsername()} 을 읽을 때 <b>조회가 한 번 더 나간다.</b>
     *
     * <p>받아들인 비용이다. 명령은 호출 빈도가 낮고, 그 추가 조회는 <b>락을 잡지 않는
     * 단순 읽기</b>라 다른 트랜잭션을 막지 않는다. 조인해서 한 번에 읽으면 락 범위가 번져
     * 훨씬 비싼 대가를 치른다.
     */
    private Klass loadForCommand(Long klassId, Long requesterId) {
        Klass klass = klassQueryPort.findWithLockById(klassId)
                .orElseThrow(KlassError.KLASS_NOT_FOUND::toException);

        if (!klass.isVisibleTo(requesterId)) {
            throw KlassError.KLASS_NOT_FOUND.toException();
        }
        if (!klass.isOwnedBy(requesterId)) {
            throw KlassError.NOT_KLASS_OWNER.toException();
        }
        return klass;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
