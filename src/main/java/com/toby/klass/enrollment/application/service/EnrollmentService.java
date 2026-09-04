package com.toby.klass.enrollment.application.service;

import com.toby.klass.enrollment.application.EnrollmentProperties;
import com.toby.klass.enrollment.application.dto.ApplyEnrollmentCommand;
import com.toby.klass.enrollment.application.dto.CancelEnrollmentCommand;
import com.toby.klass.enrollment.application.dto.ConfirmEnrollmentCommand;
import com.toby.klass.enrollment.application.dto.EnrollmentQuery;
import com.toby.klass.enrollment.application.dto.EnrollmentResult;
import com.toby.klass.enrollment.application.dto.EnrollmentSummaryResult;
import com.toby.klass.enrollment.application.dto.GiveUpWaitlistCommand;
import com.toby.klass.enrollment.application.dto.KlassEnrollmentResult;
import com.toby.klass.enrollment.application.dto.RegisterWaitlistCommand;
import com.toby.klass.enrollment.application.dto.WaitlistResult;
import com.toby.klass.enrollment.application.port.in.ApplyEnrollmentUseCase;
import com.toby.klass.enrollment.application.port.in.CancelEnrollmentUseCase;
import com.toby.klass.enrollment.application.port.in.CancelRemainingWaitlistUseCase;
import com.toby.klass.enrollment.application.port.in.ConfirmEnrollmentUseCase;
import com.toby.klass.enrollment.application.port.in.FindEnrollmentUseCase;
import com.toby.klass.enrollment.application.port.in.GiveUpWaitlistUseCase;
import com.toby.klass.enrollment.application.port.in.ListEnrollmentUseCase;
import com.toby.klass.enrollment.application.port.in.ListWaitlistUseCase;
import com.toby.klass.enrollment.application.port.in.ReapExpiredEnrollmentUseCase;
import com.toby.klass.enrollment.application.port.in.RegisterWaitlistUseCase;
import com.toby.klass.enrollment.application.port.out.EnrollmentCommandPort;
import com.toby.klass.enrollment.application.port.out.EnrollmentQueryPort;
import com.toby.klass.enrollment.domain.Enrollment;
import com.toby.klass.enrollment.domain.EnrollmentSource;
import com.toby.klass.enrollment.domain.error.EnrollmentError;
import com.toby.klass.klass.application.port.out.KlassQueryPort;
import com.toby.klass.klass.domain.Klass;
import com.toby.klass.klass.domain.KlassStatus;
import com.toby.klass.common.application.dto.CursorPageResult;
import com.toby.klass.klass.domain.error.KlassError;
import com.toby.klass.user.application.port.out.UserQueryPort;
import com.toby.klass.user.domain.User;
import com.toby.klass.user.domain.error.UserError;
import com.toby.klass.waitlist.application.dto.WaitlistQuery;
import com.toby.klass.waitlist.application.port.out.WaitlistCommandPort;
import com.toby.klass.waitlist.application.port.out.WaitlistQueryPort;
import com.toby.klass.waitlist.domain.Waitlist;
import com.toby.klass.waitlist.domain.error.WaitlistError;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 좌석을 건드리는 모든 유스케이스.
 *
 * <h2>왜 한 서비스인가</h2>
 * ERD 정본 §4.1 이 <b>"정원과 관련된 모든 트랜잭션이 {@code klass} 행 락을 가장 먼저
 * 잡는다"</b>로 락 순서를 고정했다. 트랜잭션 경계가 곧 애그리거트 경계이므로
 * {@code klass}·{@code enrollment}·{@code waitlist} 는 <b>논리적으로 하나의 애그리거트</b>다.
 * 테이블이 셋인 것은 물리 모델일 뿐이다.
 *
 * <p>도메인별로 서비스를 쪼개면 §4.4 의 "한 트랜잭션 안에서 끝낸다"가 여러 클래스에 걸치고,
 * {@code @Transactional} 전파 하나가 어긋나는 순간 <b>락 밖에서 승격이 일어나 그 틈에 일반
 * 신청자가 좌석을 채간다.</b> 그래서 {@code waitlist} 패키지에는 서비스가 없다 (Design D-29).
 *
 * <h2>락 획득 순서</h2>
 * <table border="1">
 *   <caption>유스케이스별 락</caption>
 *   <tr><th>유스케이스</th><th>1번째</th><th>2번째</th><th>3번째</th></tr>
 *   <tr><td>{@link #apply}</td><td>{@code klass}</td><td>—</td><td>—</td></tr>
 *   <tr><td>{@link #confirm}</td><td>{@code enrollment}</td><td>—</td><td>§4.1 예외</td></tr>
 *   <tr><td>{@link #cancel}</td><td>{@code klass}</td><td>{@code enrollment}</td><td>{@code waitlist}</td></tr>
 *   <tr><td>{@link #register}</td><td>{@code klass}</td><td>—</td><td>—</td></tr>
 *   <tr><td>{@link #giveUp}</td><td>{@code waitlist}</td><td>—</td><td>§4.1 예외</td></tr>
 *   <tr><td>{@link #cancelRemaining}</td><td>(호출자의 {@code klass})</td><td>{@code waitlist}</td><td>—</td></tr>
 *   <tr><td>{@link #reapExpired}</td><td>{@code klass}</td><td>{@code enrollment}</td><td>{@code waitlist}</td></tr>
 * </table>
 *
 * <p>{@link #reapExpired} 는 {@link #cancel} 과 <b>똑같은 순서로 똑같은 대상</b>을 잠근다.
 * 새 락 경로가 생기지 않으므로 데드락 가능성이 늘지 않는다 (pending-expiry-reaper §9.1).
 *
 * <p>예외 둘은 <b>락을 하나만 잡고 그 뒤 아무것도 더 잡지 않으므로</b> 순환 대기가
 * 성립하지 않는다. 둘 다 {@code enrollment_count} 를 건드리지 않는다는 공통점이 근거다.
 *
 * <h2>{@code Clock} 주입</h2>
 * 도메인이 Spring 을 모르므로 시각은 이 계층이 만들어 파라미터로 넘긴다. 무인자
 * {@code LocalDateTime.now()} 는 금지다 — 취소 기간 경계 테스트가 전적으로 여기에 의존한다.
 * <b>날짜도 마찬가지다</b> — {@code LocalDate.now(clock)} 으로 얻어야 시간대 결정이
 * {@code ClockConfig} 한 곳에 모인다 (ERD 정본 §2.2).
 *
 * <p>Design Ref: enrollment-management §2.1 · §4.3, D-29
 */
@Service
@Transactional(readOnly = true)
public class EnrollmentService implements ApplyEnrollmentUseCase, ConfirmEnrollmentUseCase,
        CancelEnrollmentUseCase, RegisterWaitlistUseCase, GiveUpWaitlistUseCase,
        CancelRemainingWaitlistUseCase, FindEnrollmentUseCase, ListEnrollmentUseCase,
        ListWaitlistUseCase, ReapExpiredEnrollmentUseCase {

    private final KlassQueryPort klassQueryPort;
    private final EnrollmentCommandPort enrollmentCommandPort;
    private final EnrollmentQueryPort enrollmentQueryPort;
    private final WaitlistCommandPort waitlistCommandPort;
    private final WaitlistQueryPort waitlistQueryPort;
    private final UserQueryPort userQueryPort;
    private final EnrollmentProperties properties;
    private final Clock clock;

    public EnrollmentService(KlassQueryPort klassQueryPort,
                             EnrollmentCommandPort enrollmentCommandPort,
                             EnrollmentQueryPort enrollmentQueryPort,
                             WaitlistCommandPort waitlistCommandPort,
                             WaitlistQueryPort waitlistQueryPort,
                             UserQueryPort userQueryPort,
                             EnrollmentProperties properties,
                             Clock clock) {
        this.klassQueryPort = klassQueryPort;
        this.enrollmentCommandPort = enrollmentCommandPort;
        this.enrollmentQueryPort = enrollmentQueryPort;
        this.waitlistCommandPort = waitlistCommandPort;
        this.waitlistQueryPort = waitlistQueryPort;
        this.userQueryPort = userQueryPort;
        this.properties = properties;
        this.clock = clock;
    }

    // ── 수강 신청 (ERD 정본 §4.2) ────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>검사 순서가 사용자에게 보이는 메시지를 결정한다.
     *
     * <ol>
     *   <li><b>존재</b> — 없으면 404</li>
     *   <li><b>모집 상태</b> — {@code DRAFT} 도 여기서 함께 걸러진다. 강의 조회처럼 404 로
     *       감추지 않는 이유는, 이 경로가 인증 필수라 존재를 숨겨 얻는 것이 없고 초안임을
     *       알려도 개설자 외에는 아무것도 할 수 없기 때문이다</li>
     *   <li><b>개설자 본인</b> — <b>중복 검사보다 먼저</b>다. 개설자는 애초에 신청 자격이
     *       없으므로 "이미 신청했다"라는 엉뚱한 메시지가 나가면 안 된다 (FR-19)</li>
     *   <li><b>중복 신청</b> — {@code uq_enrollment_active} 가 최종 방어하지만 앱이 먼저
     *       막아야 이유를 설명할 수 있다</li>
     *   <li><b>정원</b> — {@code occupySeat()} 안에서 검사와 증가가 한 번에 일어난다.
     *       둘 사이에 코드가 끼면 검사가 무의미해진다</li>
     * </ol>
     *
     * <p>4번과 5번 사이에 다른 트랜잭션이 끼어들 수 없는 이유는 1번의 배타 락이다.
     * <b>이것이 "동시에 여러 사람이 마지막 자리에 신청"을 해결하는 지점이다.</b>
     */
    @Override
    @Transactional
    public EnrollmentResult apply(ApplyEnrollmentCommand command) {
        Klass klass = lockKlass(command.klassId());
        verifyOpenAndNotOwner(klass, command.userId());

        if (enrollmentQueryPort.existsActive(klass.getId(), command.userId())) {
            throw EnrollmentError.DUPLICATE_ENROLLMENT.toException();
        }

        LocalDateTime now = now();
        klass.occupySeat();

        Enrollment saved = enrollmentCommandPort.save(Enrollment.apply(
                klass, loadUser(command.userId()), EnrollmentSource.DIRECT,
                now, now.plus(properties.pendingExpiry().direct())));

        return toResult(saved, now);
    }

    // ── 결제 확정 (ERD 정본 §4.3) ────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p><b>{@code klass} 락을 잡지 않는다.</b> {@code PENDING} 이 이미 좌석을 점유하고
     * 있어 카운터가 변하지 않으므로, {@code enrollment} 단독 락만으로 충분하고 순환 대기가
     * 성립하지 않는다 (ERD 정본 §4.1 예외).
     *
     * <p>상태 재확인과 만료 검사는 {@link Enrollment#confirm} 안에 있다. 서비스에 두면
     * 다른 호출 경로가 생길 때 빠뜨릴 수 있다. 회수 배치가 붙은 지금도 <b>사이클 사이에
     * 만료된 행이 남으므로 그 검사가 첫째 방어선</b>이고, {@link #reapExpired} 가 둘째다.
     */
    @Override
    @Transactional
    public EnrollmentResult confirm(ConfirmEnrollmentCommand command) {
        Enrollment enrollment = lockEnrollment(command.enrollmentId(), command.requesterId());

        LocalDateTime now = now();
        enrollment.confirm(now);

        return toResult(enrollment, now);
    }

    // ── 수강 취소 + 승격 (ERD 정본 §4.4) ─────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>0번이 <b>락 없이</b> 소속 강의를 알아내는 이유: §4.1 규약상 {@code klass} 를 먼저
     * 잠가야 하는데, 어느 강의인지는 {@code enrollment} 를 봐야 안다. 순서를 지키려면 이
     * 단계가 필요하다. 이 조회와 락 획득 사이에 강의가 바뀔 수는 없다 —
     * {@code enrollment.klass_id} 는 생성 후 변경되지 않는다.
     *
     * <p>정본 §4.4 3번({@code enrollment.klass_id != klassId} 재확인)은 <b>생략했다.</b>
     * 정본은 {@code klassId} 를 외부 입력으로 받는 호출자를 가정했으나 여기서는 0번이
     * 스스로 구하므로 어긋날 경로가 없다 (Design D-34).
     */
    @Override
    @Transactional
    public EnrollmentResult cancel(CancelEnrollmentCommand command) {
        // 0. 락 순서를 지키려고 소속 강의부터 알아낸다 (무락)
        Long klassId = enrollmentQueryPort.findKlassIdById(command.enrollmentId())
                .orElseThrow(EnrollmentError.ENROLLMENT_NOT_FOUND::toException);

        Klass klass = lockKlass(klassId);
        Enrollment enrollment = lockEnrollment(command.enrollmentId(), command.requesterId());

        LocalDateTime now = now();
        LocalDate today = today();

        enrollment.cancel(now, today,
                klass.cancellationPolicy(properties.defaultCancellationPeriodDays()));
        klass.releaseSeat();

        promoteNextWaiting(klass, now);

        return toResult(enrollment, now);
    }

    /**
     * 반납된 좌석을 대기자에게 이전한다. 1건만 승격한다.
     *
     * <h4>{@code private} 인 것이 설계다</h4>
     * 별 빈으로 두면 {@code @Transactional} 전파 하나로 <b>락 밖에서 승격이 일어나</b>
     * 그 틈에 일반 신청자가 좌석을 채간다 (ERD 정본 §4.4 핵심 성질 2번). {@code private}
     * 메서드는 프록시를 타지 않으므로 애초에 그 실수가 불가능하다.
     *
     * <h4>순변화 0</h4>
     * 호출자의 {@code releaseSeat()} 와 여기의 {@code occupySeat()} 가 상쇄된다. 반납된
     * 좌석이 일반 신청자에게 노출되는 틈 없이 대기자에게 이전된다. 승격 대상이 없거나
     * 강의가 {@code OPEN} 이 아니면 순변화는 {@code -1} 이고 좌석은 빈 채로 남는다.
     *
     * <h4>부적격 대기자는 건너뛴다</h4>
     * 비활성 계정, 이미 활성 신청이 있는 사람, <b>개설자 본인</b>(FR-19 세 번째 지점)은
     * 대기 행을 {@code CANCELLED} 로 정리하고 다음 순번을 본다. 개설자 검사가 없으면
     * 대기열이 신청 차단의 우회로가 된다.
     */
    private void promoteNextWaiting(Klass klass, LocalDateTime now) {
        // CLOSED 에서는 승격하지 않는다 — 명단이 계속 흔들리면 마감의 의미가 없다 (§2.1)
        if (klass.getStatus() != KlassStatus.OPEN) {
            return;
        }

        int lastPosition = 0;
        while (true) {
            Optional<Waitlist> candidate =
                    waitlistQueryPort.findNextWaitingWithLock(klass.getId(), lastPosition);
            if (candidate.isEmpty()) {
                return;   // 적격 대기자가 없다. 좌석은 빈 채로 남는다
            }

            Waitlist waitlist = candidate.get();
            lastPosition = waitlist.getPosition();

            if (!isEligible(klass, waitlist)) {
                waitlist.cancel();
                continue;
            }

            waitlist.promote(now);
            klass.occupySeat();
            enrollmentCommandPort.save(Enrollment.apply(
                    klass, waitlist.getUser(), EnrollmentSource.WAITLIST,
                    now, now.plus(properties.pendingExpiry().waitlist())));
            return;   // 1건만 승격한다
        }
    }

    /**
     * 승격 자격을 판별한다.
     *
     * <p>{@code waitlist.getUser()} 는 {@code LAZY} 프록시라 {@code isEnabled()} 접근에서
     * 초기화된다. 후보 한 명당 조회 한 번인데, 루프가 보통 한 번만 도므로 fetch join 을
     * 걸어 <b>모든 승격 경로에</b> 조인 비용을 지우는 것보다 낫다.
     */
    private boolean isEligible(Klass klass, Waitlist waitlist) {
        Long userId = waitlist.getUser().getId();
        return waitlist.getUser().isEnabled()
                && !enrollmentQueryPort.existsActive(klass.getId(), userId)
                && !klass.isOwnedBy(userId);       // FR-19 — 대기열 우회로 차단
    }

    // ── 대기열 등록 (ERD 정본 §4.5) ──────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>3번(활성 신청 검사)이 필수인 이유: {@code uq_enrollment_active} 는
     * {@code enrollment} INSERT 에만 작동하고 {@code uq_waitlist_waiting} 은 중복 <b>대기</b>만
     * 막는다. 없으면 <b>이미 {@code CONFIRMED} 인 사용자가 대기열에 등록</b>되어 순번을
     * 차지하고, 승격 시 부적격으로 걸러진다.
     *
     * <p>{@code MAX(position) + 1} 이 안전한 것은 {@code klass} 락 하위에서만 실행되기
     * 때문이다. {@code uq_waitlist_position} 이 최종 방어한다.
     */
    @Override
    @Transactional
    public WaitlistResult register(RegisterWaitlistCommand command) {
        Klass klass = lockKlass(command.klassId());
        verifyOpenAndNotOwner(klass, command.userId());

        if (enrollmentQueryPort.existsActive(klass.getId(), command.userId())) {
            throw EnrollmentError.DUPLICATE_ENROLLMENT.toException();
        }
        if (waitlistQueryPort.existsWaiting(klass.getId(), command.userId())) {
            throw WaitlistError.DUPLICATE_WAITLIST.toException();
        }
        // 자리가 있는데 대기열에 넣으면 좌석 반납이 일어날 때까지 영구히 기다린다
        if (klass.hasSeat()) {
            throw WaitlistError.WAITLIST_SEAT_AVAILABLE.toException();
        }

        int nextPosition = waitlistQueryPort.maxPosition(klass.getId()) + 1;

        return WaitlistResult.from(waitlistCommandPort.save(
                Waitlist.enqueue(klass, loadUser(command.userId()), nextPosition, now())));
    }

    // ── 대기 포기 (ERD 정본 §4.9) ────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>상태 재확인({@link Waitlist#cancel} 안)이 <b>승격 트랜잭션과의 경합을 막는다.</b>
     * 승격이 먼저 커밋되면 이 요청은 {@code PROMOTED} 를 보고 거부되며, 사용자는 "이미
     * 자리가 배정되었다"를 안내받는다 — 포기시켜 버리면 배정된 좌석이 주인 없이 남는다.
     */
    @Override
    @Transactional
    public WaitlistResult giveUp(GiveUpWaitlistCommand command) {
        Waitlist waitlist = waitlistQueryPort.findWithLockById(command.waitlistId())
                .orElseThrow(WaitlistError.WAITLIST_NOT_FOUND::toException);

        if (!waitlist.isOwnedBy(command.requesterId())) {
            throw WaitlistError.NOT_WAITLIST_OWNER.toException();
        }

        waitlist.cancel();
        return WaitlistResult.from(waitlist);
    }

    // ── 강의 마감 시 잔여 대기자 정리 (ERD 정본 §4.8 5번) ────────────────────

    /**
     * {@inheritDoc}
     *
     * <p><b>{@code KlassService} 가 호출한다.</b> 전파를 명시하지 않아 기본값
     * {@code REQUIRED} 를 쓰므로, 호출자가 이미 잡은 {@code klass} 행 락 <b>안에서</b>
     * 실행된다. 그래서 여기서 {@code klass} 를 다시 잠그지 않는다.
     *
     * <p>벌크 UPDATE 대신 조회 후 도메인 메서드를 반복한다 — 벌크는 영속 컨텍스트를
     * 우회해 이미 로딩된 인스턴스가 옛 상태로 남고, JPQL 문자열은 부트스트랩 위험이다.
     */
    @Override
    @Transactional
    public void cancelRemaining(Long klassId) {
        waitlistQueryPort.findAllWaiting(klassId).forEach(Waitlist::cancel);
    }

    // ── 만료 회수 (Design pending-expiry-reaper §5.3) ────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>클래스 레벨 {@code @Transactional(readOnly = true)} 를 그대로 받는다. 락을 잡지
     * 않으므로 인기 강의의 신청 트랜잭션과 직렬화되지 않는다.
     */
    @Override
    public List<Long> findExpiredTargets() {
        return enrollmentQueryPort.findExpiredIds(now(), properties.reapBatchSize());
    }

    /**
     * {@inheritDoc}
     *
     * <h4>락 순서가 {@link #cancel} 과 같다</h4>
     * {@code klass} → {@code enrollment} → ({@code waitlist}). ERD 정본 §4.1 이 고정한
     * 순서이며, 배치라고 예외를 두면 <b>기존 취소 트랜잭션과 데드락이 생긴다.</b>
     *
     * <h4>재확인이 이 메서드의 존재 이유다</h4>
     * 후보 조회는 락 없이 하므로 그 사이 사용자가 결제를 마쳤거나 스스로 취소했을 수 있다.
     * 락을 잡은 <b>뒤에</b> {@code isExpiredAt} 으로 다시 보지 않으면 <b>확정된 신청을
     * 배치가 취소한다</b> (Plan R-2).
     *
     * <h4>승격이 락 안에 있다</h4>
     * {@code promoteNextWaiting} 은 이 클래스의 {@code private} 메서드라 프록시를 타지
     * 않는다. 별 빈으로 빼거나 이벤트로 발행하면 전파 하나로 락 밖에서 실행돼 그 틈에
     * 일반 신청자가 반납된 좌석을 채간다 (Design D-47).
     *
     * <p>{@code lockEnrollment(id, requesterId)} 를 쓰지 않는다 — 그 헬퍼는 소유권을
     * 검사하는데 배치에는 요청자가 없다.
     */
    @Override
    @Transactional
    public boolean reapExpired(Long enrollmentId) {
        // 0. 락 순서를 지키려고 소속 강의부터 알아낸다 (무락) — cancel 과 동일 (§4.1)
        Long klassId = enrollmentQueryPort.findKlassIdById(enrollmentId).orElse(null);
        if (klassId == null) {
            return false;   // 후보 조회 이후 사라졌다. 도달하기 어렵지만 배치는 방어한다
        }

        Klass klass = lockKlass(klassId);
        Enrollment enrollment = enrollmentQueryPort.findWithLockById(enrollmentId).orElse(null);
        if (enrollment == null) {
            return false;
        }

        LocalDateTime now = now();
        if (!enrollment.isExpiredAt(now)) {
            return false;   // 그 사이 결제·취소됐다. 예외가 아니라 정상적인 경합 결과다
        }

        enrollment.expire(now);
        klass.releaseSeat();
        promoteNextWaiting(klass, now);

        return true;
    }

    // ── 조회 ─────────────────────────────────────────────────────────────────
    // 클래스 레벨 @Transactional(readOnly = true) 를 그대로 받는다. Hibernate 가 flush 를
    // 건너뛰고 더티 체킹을 하지 않으므로, 조회 경로에서 실수로 도메인 메서드를 불러도
    // DB 에 반영되지 않는다 — 승격처럼 상태를 바꾸는 코드가 같은 클래스에 있어 값을 한다.

    /**
     * {@inheritDoc}
     *
     * <p><b>락을 잡지 않는다.</b> 조회가 신청 트랜잭션과 직렬화되면 안 된다.
     */
    @Override
    public EnrollmentResult findById(Long enrollmentId, Long requesterId) {
        Enrollment enrollment = enrollmentQueryPort.findById(enrollmentId)
                .orElseThrow(EnrollmentError.ENROLLMENT_NOT_FOUND::toException);

        if (!enrollment.isOwnedBy(requesterId)) {
            throw EnrollmentError.NOT_ENROLLMENT_OWNER.toException();
        }
        return toResult(enrollment, now());
    }

    @Override
    public CursorPageResult<EnrollmentSummaryResult> listMine(Long userId, EnrollmentQuery query) {
        LocalDateTime now = now();
        LocalDate today = today();
        int defaultDays = properties.defaultCancellationPeriodDays();

        return enrollmentQueryPort.findUserPage(userId, query)
                .map(e -> EnrollmentSummaryResult.from(e, now, today, defaultDays));
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>소유권 검사가 여기 있어야 하는 이유</b>: {@code SecurityConfig} 는
     * {@code hasRole("CREATOR")} 까지만 본다. 그것만으로는 <b>크리에이터끼리 서로의 수강생
     * 명단을 볼 수 있다</b> — 권한과 소유권은 다른 검사다.
     *
     * <p>강의를 <b>무락으로</b> 읽는다. 명단 조회는 카운터를 건드리지 않으므로 락이 불필요하고,
     * 잡으면 인기 강의의 명단 조회가 신청 트랜잭션과 직렬화된다.
     */
    @Override
    public CursorPageResult<KlassEnrollmentResult> listByKlass(Long klassId, Long requesterId,
                                                               EnrollmentQuery query) {
        Klass klass = klassQueryPort.findById(klassId)
                .orElseThrow(KlassError.KLASS_NOT_FOUND::toException);

        if (!klass.isOwnedBy(requesterId)) {
            throw KlassError.NOT_KLASS_OWNER.toException();
        }
        return enrollmentQueryPort.findKlassPage(klassId, query).map(KlassEnrollmentResult::from);
    }

    @Override
    public CursorPageResult<WaitlistResult> listMineWaitlist(Long userId, WaitlistQuery query) {
        return waitlistQueryPort.findUserPage(userId, query).map(WaitlistResult::from);
    }

    // ── 공통 ─────────────────────────────────────────────────────────────────

    /** {@code klass} 배타 락. 정원과 관련된 모든 트랜잭션의 첫 단계다 (ERD 정본 §4.1). */
    private Klass lockKlass(Long klassId) {
        return klassQueryPort.findWithLockById(klassId)
                .orElseThrow(KlassError.KLASS_NOT_FOUND::toException);
    }

    /** {@code enrollment} 배타 락 + 소유권. 확정과 취소가 공유한다. */
    private Enrollment lockEnrollment(Long enrollmentId, Long requesterId) {
        Enrollment enrollment = enrollmentQueryPort.findWithLockById(enrollmentId)
                .orElseThrow(EnrollmentError.ENROLLMENT_NOT_FOUND::toException);

        // 소유권을 상태보다 먼저 본다 — 비소유자에게 신청 상태를 노출하지 않는다 (D-40)
        if (!enrollment.isOwnedBy(requesterId)) {
            throw EnrollmentError.NOT_ENROLLMENT_OWNER.toException();
        }
        return enrollment;
    }

    /**
     * 신청·대기 등록이 공유하는 두 검사.
     *
     * <p>개설자 검사를 중복 검사보다 <b>앞</b>에 두는 순서까지 공유한다 — 두 경로에서
     * 같은 이유로 거부되는데 메시지가 다르면 안 된다 (FR-19).
     */
    private void verifyOpenAndNotOwner(Klass klass, Long userId) {
        if (klass.getStatus() != KlassStatus.OPEN) {
            throw EnrollmentError.KLASS_NOT_OPEN.toException();
        }
        if (klass.isOwnedBy(userId)) {
            throw EnrollmentError.SELF_ENROLLMENT_FORBIDDEN.toException();
        }
    }

    /** 토큰은 유효한데 그 사이 사용자가 사라진 경우다. 사용자 도메인의 에러를 그대로 쓴다. */
    private User loadUser(Long userId) {
        return userQueryPort.findById(userId)
                .orElseThrow(UserError.USER_NOT_FOUND::toException);
    }

    private EnrollmentResult toResult(Enrollment enrollment, LocalDateTime now) {
        return EnrollmentResult.from(enrollment, now, today(),
                properties.defaultCancellationPeriodDays());
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    /**
     * 오늘 날짜.
     *
     * <p><b>무인자 {@code LocalDate.now()} 를 쓰면 안 된다.</b> {@code DATE} 와 현재 시각을
     * 비교하는 지점에서 시간대가 개입하는데, 그 결정은 {@code ClockConfig} 한 곳에 모여야
     * 한다 (ERD 정본 §2.2).
     */
    private LocalDate today() {
        return LocalDate.now(clock);
    }
}
