package com.toby.klass.enrollment.domain.error;

import com.toby.klass.common.domain.error.ErrorCode;

/**
 * 수강 신청 컨텍스트의 에러 코드.
 *
 * <h2>어느 enum 에 넣을지 — 판정의 주어로 가른다</h2>
 * 상수명이 그대로 API 응답의 {@code error.code} 가 되고 응답에는 enum 타입 정보가 실리지
 * 않으므로, 다른 {@code *Error} 와 이름이 겹치면 클라이언트가 구분할 수 없다. 배치 규칙은
 * 하나다.
 *
 * <blockquote>
 * {@code klass} <b>자신의 불변식</b>(정원·기간)이면 {@code KlassError},
 * <b>신청이라는 행위</b>의 성립 여부이면 여기.
 * </blockquote>
 *
 * <p>그래서 {@link #KLASS_NOT_OPEN} 과 {@link #KLASS_ALREADY_FINISHED} 가 여기 있다 —
 * 강의 상태나 종료일 자체는 정상이고, <b>그 상태에서 신청·취소가 성립하지 않을 뿐</b>이다.
 * 반면 "정원을 넘겼다"는 강의의 불변식이라 {@code KlassError.KLASS_CAPACITY_FULL} 이다.
 *
 * <h2>왜 대부분 409 인가</h2>
 * 400 은 <b>요청 자체</b>가 잘못된 경우고 409 는 요청은 옳은데 <b>현재 리소스 상태</b>와
 * 충돌하는 경우다. 이 도메인의 거부는 거의 전부 후자다 — 입력을 아무리 고쳐도 강의나 신청의
 * 상태가 바뀌기 전엔 성공하지 않는다.
 *
 * <p>Design Ref: enrollment-management §7.0 배치 규칙, §7.1
 */
public enum EnrollmentError implements ErrorCode {

    /** 신청을 찾을 수 없다. */
    ENROLLMENT_NOT_FOUND(404, "수강 신청을 찾을 수 없습니다"),

    /**
     * 타인의 신청을 확정·취소·조회하려 했다.
     *
     * <p><b>404 로 감추지 않는다.</b> 강의 상세가 타인의 {@code DRAFT} 를 404 로 감추는 것과
     * 대비되는데 근거가 다르다 — 초안은 <b>존재 자체가 비밀</b>이지만, 신청 id 는 연속된
     * 정수라 감춰봐야 존재 여부가 추측되고 애초에 비밀이 아니다.
     */
    NOT_ENROLLMENT_OWNER(403, "본인의 수강 신청만 관리할 수 있습니다"),

    /**
     * 개설자가 자기 강의에 신청·대기 등록하려 했다.
     *
     * <p>권한이 겹치는 사용자({@code ROLE_USER} + {@code ROLE_CREATOR})가 존재하므로
     * 권한 검사만으로는 막히지 않는다. <b>차단은 세 지점</b>에 들어간다 — 신청, 대기 등록,
     * 그리고 <b>승격 적격성 검사</b>. 세 번째가 없으면 대기열이 우회로가 된다.
     *
     * <p>Design Ref: FR-19, D-30
     */
    SELF_ENROLLMENT_FORBIDDEN(403, "본인이 개설한 강의는 신청할 수 없습니다"),

    /**
     * 모집 중인 강의가 아니다. {@code DRAFT} 와 {@code CLOSED} 가 여기 걸린다.
     *
     * <p>{@code DRAFT} 를 404 로 감추지 않는 이유: 이 경로는 인증이 필수라 존재를 숨겨서
     * 얻는 것이 없고, 초안임을 알려도 개설자 외에는 아무것도 할 수 없다.
     */
    KLASS_NOT_OPEN(409, "모집 중인 강의가 아닙니다"),

    /**
     * 이미 활성 신청({@code PENDING} 또는 {@code CONFIRMED})이 있다.
     *
     * <p>{@code uq_enrollment_active} 가 최종 방어하지만 앱이 먼저 막는다 — 제약 위반 예외를
     * 잡아 409 로 바꾸는 것보다 명시적 검사가 읽힌다.
     *
     * <p>취소한 신청은 세지 않는다. 생성 컬럼 {@code active_user_key} 가 {@code CANCELLED} 에
     * NULL 을 넣어 재신청을 허용하기 때문이다.
     */
    DUPLICATE_ENROLLMENT(409, "이미 신청한 강의입니다"),

    /**
     * 허용되지 않는 상태 전이.
     *
     * <p>허용은 셋뿐이다 — {@code PENDING → CONFIRMED}(만료 전),
     * {@code PENDING → CANCELLED}(사용자 취소는 관문 면제, 만료 회수는 기한 경과 필요),
     * {@code CONFIRMED → CANCELLED}(두 관문 통과 시).
     * {@code CANCELLED} 는 종착이며 {@code CONFIRMED → PENDING} 되돌리기는 존재하지 않는다
     * — 그런 메서드를 만들지 않는 것이 1차 방어다.
     */
    INVALID_ENROLLMENT_STATUS_TRANSITION(409, "허용되지 않는 상태 변경입니다"),

    /**
     * 결제 기한이 지난 신청을 확정하려 했다.
     *
     * <p><b>첫째 만료 방어선이다.</b> 회수 배치가 둘째지만 주기 사이에는 만료된
     * {@code PENDING} 행이 DB 에 남으므로(최대 {@code app.enrollment.reap-interval}),
     * 이 검사가 없으면 그 행이 그동안 결제 가능해진다.
     *
     * <p>{@link #ENROLLMENT_NOT_EXPIRED} 와 방향이 반대다.
     *
     * <p>Design Ref: ERD 정본 §4.3 4번, pending-expiry-reaper §6.1
     */
    ENROLLMENT_EXPIRED(409, "결제 기한이 지난 신청입니다"),

    /**
     * 취소 가능 기간이 지났다. 기산점은 <b>결제 확정 시각</b>이다.
     *
     * <p>강의가 정한 {@code cancellation_period_days} 를 쓰고, 지정하지 않았으면 전역
     * 기본값을 따른다. 경계는 <b>포함</b>이다 — 정확히 그 시각까지는 취소할 수 있다.
     *
     * <p>Design Ref: FR-11, ERD 정본 §4.4 5-b
     */
    CANCELLATION_PERIOD_EXPIRED(409, "취소 가능 기간이 지났습니다"),

    /**
     * 강의가 이미 끝났다. <b>취소 가능 기간이 남아 있어도 거부한다.</b>
     *
     * <p>{@link #CANCELLATION_PERIOD_EXPIRED} 와 나누는 이유: 기간 초과는 "더 빨리 요청하면
     * 됐다"이지만 이쪽은 <b>아무리 빨리 요청해도 성립하지 않는다.</b> 사용자에게 해야 할
     * 이야기가 다르므로 코드도 다르다.
     *
     * <p>ERD 정본에 없는 신규 요건이다.
     *
     * <p>Design Ref: FR-20, D-31
     */
    KLASS_ALREADY_FINISHED(409, "종료된 강의는 취소할 수 없습니다"),

    /**
     * 아직 기한이 남은 신청을 만료 회수하려 했다.
     *
     * <p><b>HTTP 로는 나가지 않는다.</b> 만료 회수는 배치만 수행하고, 배치는 락 획득 후
     * {@code isExpiredAt} 으로 재확인한 뒤에만 {@code expire()} 를 부른다. 이 코드는
     * <b>도메인 불변식의 방어선</b>이며 {@code WaitlistError.WAITLIST_PAGE_SIZE_OUT_OF_RANGE}
     * 와 같은 성격이다 — 정상 경로를 우회하는 호출을 막는 둘째 방어선.
     *
     * <p>{@link #ENROLLMENT_EXPIRED} 와 방향이 반대다 — 그쪽은 <b>기한이 지나서</b> 확정을
     * 막고, 이쪽은 <b>기한이 남아서</b> 회수를 막는다.
     *
     * <p>Design Ref: pending-expiry-reaper §6.1
     */
    ENROLLMENT_NOT_EXPIRED(409, "아직 결제 기한이 지나지 않은 신청입니다"),

    /** 목록 조회 개수가 범위를 벗어났다. 상한이 없으면 한 번에 전체 테이블을 끌어갈 수 있다. */
    INVALID_ENROLLMENT_PAGE_SIZE(400, "조회 개수는 1 이상 100 이하여야 합니다");

    private final int httpStatus;
    private final String message;

    EnrollmentError(int httpStatus, String message) {
        this.httpStatus = httpStatus;
        this.message = message;
    }

    @Override
    public String message() {
        return message;
    }

    @Override
    public int httpStatus() {
        return httpStatus;
    }
}
