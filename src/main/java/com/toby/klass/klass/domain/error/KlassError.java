package com.toby.klass.klass.domain.error;

import com.toby.klass.common.domain.error.ErrorCode;

/**
 * 강의 컨텍스트의 에러 코드.
 *
 * <h2>상수명에 {@code KLASS_} 를 붙이는 이유</h2>
 * 상수명이 그대로 API 응답의 {@code error.code} 가 되고({@link ErrorCode}), 응답에는 enum
 * 타입 정보가 실리지 않는다. {@code CommonError} 에 이미 {@code NOT_FOUND} 와
 * {@code ACCESS_DENIED} 가 있으므로 접두어 없이 두면 <b>클라이언트가 둘을 구분할 수 없다.</b>
 *
 * <h2>왜 400 과 409 를 나누는가</h2>
 * 400 은 <b>요청 자체</b>가 잘못된 경우고, 409 는 요청은 옳은데 <b>현재 리소스 상태</b>와
 * 충돌하는 경우다. 이 구분이 클라이언트의 행동을 가른다 — 400 이면 입력을 고쳐 다시 보내면
 * 되지만, 409 는 아무리 입력을 고쳐도 강의 상태가 바뀌기 전엔 성공하지 않는다.
 *
 * <p>Design Ref: §6.1 KlassError 정의, §6.2 상태 코드 선택 근거
 */
public enum KlassError implements ErrorCode {

    /**
     * 강의를 찾을 수 없다.
     *
     * <p><b>타인의 {@code DRAFT} 강의에 접근할 때도 이 코드다.</b> 403 을 쓰면 "그 강의는
     * 존재하는데 네가 못 본다"를 알려주게 되는데, 초안은 존재 자체가 비밀이다. 목록에서
     * 안 보이는 것과 상세에서 404 인 것이 같은 이야기를 해야 한다 (Design §6.2).
     */
    KLASS_NOT_FOUND(404, "강의를 찾을 수 없습니다"),

    /**
     * 남의 강의를 수정·전이하려 했다.
     *
     * <p>여기서는 404 로 감추지 않는다. 이 코드에 도달했다는 것은 강의가 이미 공개돼 있어
     * (`OPEN`/`CLOSED`) 상세 조회로 누구나 볼 수 있다는 뜻이므로, 존재를 숨겨봐야 얻는 것이
     * 없다. 오히려 404 로 답하면 개설자 본인도 자기 강의를 못 찾는 것처럼 읽힌다.
     *
     * <p><b>{@code ROLE_CREATOR} 를 가졌는지와 별개의 검사다.</b> 권한만 확인하면 크리에이터끼리
     * 서로의 강의를 수정할 수 있다 — 이 코드가 그 구멍을 막는다.
     */
    NOT_KLASS_OWNER(403, "본인이 개설한 강의만 관리할 수 있습니다"),

    /**
     * 허용되지 않는 상태 전이.
     *
     * <p>허용은 3종뿐이다 — {@code DRAFT → OPEN}, {@code DRAFT → CLOSED}(개설 철회),
     * {@code OPEN → CLOSED}. 역전이({@code OPEN → DRAFT}, {@code CLOSED → OPEN})는
     * 대기자가 유령 행으로 남거나 신규 신청자가 대기자를 앞지르는 구멍을 열어 차단한다
     * (Design §3.3, D-18).
     */
    INVALID_KLASS_STATUS_TRANSITION(409, "허용되지 않는 상태 변경입니다"),

    /**
     * 이미 앉은 인원보다 적은 정원으로 줄이려 했다.
     *
     * <p>DB 의 {@code ck_klass_count} 가 최종 방어하지만 <b>앱이 먼저 막는다</b> — CHECK 에
     * 걸리면 제약 위반 예외가 나갈 뿐 사용자에게 무엇이 문제인지 설명할 수 없다
     * (ERD 정본 §4.8).
     */
    CAPACITY_BELOW_ENROLLMENT(409, "현재 수강 인원보다 적은 정원으로 변경할 수 없습니다"),

    /**
     * 수강 종료일이 시작일보다 빠르다.
     *
     * <p>{@code ck_klass_period} 와 같은 규칙을 도메인에서 먼저 검사한다. 요청 값만 보고
     * 판정할 수 있어 400 이다 — 강의의 현재 상태를 참조하지 않는다.
     */
    INVALID_KLASS_PERIOD(400, "수강 종료일은 시작일보다 빠를 수 없습니다"),

    /** 정원이 1 미만이다. {@code ck_klass_capacity} 와 같은 규칙. */
    INVALID_KLASS_CAPACITY(400, "정원은 1명 이상이어야 합니다"),

    /**
     * 목록 조회 개수가 범위를 벗어났다.
     *
     * <p>상한을 두지 않으면 한 번의 요청으로 전체 테이블을 끌어갈 수 있다.
     */
    INVALID_KLASS_PAGE_SIZE(400, "조회 개수는 1 이상 100 이하여야 합니다");

    private final int httpStatus;
    private final String message;

    KlassError(int httpStatus, String message) {
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
