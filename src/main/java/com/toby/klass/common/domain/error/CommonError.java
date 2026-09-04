package com.toby.klass.common.domain.error;

/**
 * 특정 바운디드 컨텍스트에 속하지 않는 공통 에러.
 *
 * <p>상수명이 그대로 API 응답의 {@code error.code} 가 된다({@link ErrorCode}).
 * 단어 구분은 언더스코어이며, 서로 다른 {@code *Error} enum 사이에 같은
 * 상수명을 두지 않는다 — 응답에는 enum 타입 정보가 실리지 않으므로 이름이
 * 겹치면 클라이언트가 구분할 수 없다.
 *
 * <p>Design Ref: §6.1, §10.1 — 에러 enum 명명 규칙
 */
public enum CommonError implements ErrorCode {

    /** {@code @Valid} 검증 실패. {@code details} 에 필드별 메시지가 담긴다. */
    VALIDATION_ERROR(400, "입력값이 올바르지 않습니다"),

    /**
     * 인증은 됐으나 권한이 없는 경우.
     *
     * <p><b>도달한다.</b> {@code SecurityConfig} 의 {@code hasRole("CREATOR")} 매처가
     * {@code ROLE_USER} 를 막을 때 {@code CustomAccessDeniedHandler} 가 이 코드로 답한다.
     * 메서드 보안({@code @PreAuthorize})을 쓰지 않는 것은 CLAUDE.md 규약이며, 인가가
     * 없다는 뜻이 아니라 <b>요청 매처로만 한다</b>는 뜻이다.
     *
     * <p>이 코드가 있어야 인증 실패(401)와 권한 부족(403)의 응답 본문 형식이 같아진다.
     */
    ACCESS_DENIED(403, "접근 권한이 없습니다"),

    /**
     * 요청 본문을 읽을 수 없다. 깨진 JSON, 빈 본문, 타입이 맞지 않는 값 등.
     *
     * <p>{@link #VALIDATION_ERROR} 와 구분한다. 그쪽은 파싱은 됐으나 값이 규칙을 어긴
     * 경우이고, 이쪽은 <b>파싱 자체가 실패</b>한 경우다. 필드별 상세를 줄 수 없다.
     */
    MALFORMED_REQUEST(400, "요청 본문을 해석할 수 없습니다"),

    /** 존재하지 않는 경로. Spring MVC 의 {@code NoResourceFoundException} 을 변환한 것이다. */
    NOT_FOUND(404, "요청한 리소스를 찾을 수 없습니다"),

    /** 경로는 있으나 HTTP 메서드가 다르다. 예: POST 전용 엔드포인트에 GET 요청. */
    METHOD_NOT_ALLOWED(405, "지원하지 않는 HTTP 메서드입니다"),

    /** 처리하지 못한 모든 예외의 최종 안전망. 원인은 로그에만 남기고 노출하지 않는다. */
    INTERNAL_ERROR(500, "서버 오류가 발생했습니다");

    private final int httpStatus;
    private final String message;

    CommonError(int httpStatus, String message) {
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
