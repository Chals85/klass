package com.toby.klass.auth.domain.error;

import com.toby.klass.common.domain.error.ErrorCode;

/**
 * 인증 컨텍스트의 에러 코드.
 *
 * <p>상수명이 그대로 API 응답의 {@code error.code} 가 된다.
 *
 * <h2>왜 대부분 401 이고 메시지가 뭉툭한가</h2>
 * 인증 실패의 원인을 자세히 알려주면 공격자에게 정보를 준다. 특히
 * {@link #INVALID_CREDENTIALS} 는 "사용자가 없음"과 "비밀번호가 틀림"을 구분하지
 * 않는데, 구분하면 어떤 아이디가 존재하는지 알아낼 수 있기 때문이다(사용자 열거 공격).
 *
 * <p>Design Ref: §6.1 — Error Code Definition, §7 — Security Considerations
 */
public enum AuthError implements ErrorCode {

    /**
     * 로그인 실패. 사용자 없음·비밀번호 불일치를 <b>구분하지 않는다</b>.
     *
     * <p>{@code AuthService.login()} 이 던진다. 도메인({@code User})이 던지지 않는 이유는
     * 두 가지다 — ① 비밀번호 비교에는 {@code PasswordHasherPort} 가 필요한데 도메인은
     * 포트를 알 수 없고, ② {@code user} 컨텍스트가 {@code auth} 컨텍스트의 이 enum 을
     * import 하면 바운디드 컨텍스트가 교차한다.
     */
    INVALID_CREDENTIALS(401, "아이디 또는 비밀번호가 올바르지 않습니다"),

    /** JWT 의 {@code exp} 가 지났다. 서명 검증 단계에서 걸러진다. */
    TOKEN_EXPIRED(401, "토큰이 만료되었습니다"),

    /** 서명 위조, 형식 오류, 필수 클레임 누락 등 파싱 자체가 실패한 경우. */
    TOKEN_INVALID(401, "유효하지 않은 토큰입니다"),

    /**
     * 로그아웃으로 폐기된 Access 토큰이다. 서명도 유효하고 아직 만료되지도 않았지만
     * 폐기 목록({@code RevokedAccessToken})에 올라 있다.
     *
     * <p><b>{@link #TOKEN_EXPIRED} 와 구분하는 이유.</b> 클라이언트의 대응이 다르다.
     * 만료는 Refresh 토큰으로 재발급하면 되지만, 폐기는 이미 로그아웃한 세션이므로
     * 재발급도 실패한다 — 재로그인 외에는 방법이 없다. 같은 코드로 뭉뚱그리면
     * 클라이언트가 무의미한 재발급을 시도하고 다시 실패하는 경로를 탄다.
     *
     * <p>폐기 사실을 알려주는 것이 정보 노출은 아니다. 이 응답을 받으려면 애초에
     * <b>유효한 서명의 토큰</b>을 제시해야 하므로, 그 토큰의 소유자만 볼 수 있다.
     */
    TOKEN_REVOKED(401, "로그아웃된 토큰입니다"),

    /**
     * 서명은 유효하나 DB 에 해당 해시가 없다.
     *
     * <p>로그아웃된 토큰이 여기에 해당한다. 로그아웃은 행을 <b>삭제</b>하므로
     * 재사용({@link #REFRESH_TOKEN_REUSED})이 아니라 이 코드가 나온다.
     */
    REFRESH_TOKEN_NOT_FOUND(401, "등록되지 않은 리프레시 토큰입니다"),

    /**
     * 이미 회전되어 폐기된 Refresh 토큰을 다시 사용했다.
     *
     * <p>토큰 탈취 신호로 간주하고 <b>해당 사용자의 모든 Refresh 토큰을 무효화</b>한다.
     * 정상 사용자와 공격자 중 누가 먼저 썼는지 알 수 없으므로 양쪽 모두 재로그인시킨다.
     */
    REFRESH_TOKEN_REUSED(401, "이미 사용된 리프레시 토큰입니다"),

    /**
     * DB 에 기록된 만료 시각이 지났다.
     *
     * <p><b>정상 경로에서는 도달하지 않는다.</b> 토큰 파싱이 JWT 의 {@code exp} 를 먼저
     * 검사해 {@link #TOKEN_EXPIRED} 로 끝나기 때문이다. 이 코드는 DB 의 {@code expires_at}
     * 과 JWT 의 {@code exp} 가 어긋난 경우를 막는 도메인 불변식 방어이며, 단위 테스트에서만
     * 재현된다.
     */
    REFRESH_TOKEN_EXPIRED(401, "리프레시 토큰이 만료되었습니다"),

    /**
     * 토큰 종류가 기대와 다르다. Refresh 토큰으로 보호된 API 에 접근하거나
     * Access 토큰으로 재발급을 시도한 경우다.
     *
     * <p>이 검증이 없으면 유효기간이 훨씬 긴 Refresh 토큰을 Access 토큰처럼 쓸 수 있다.
     */
    TOKEN_TYPE_MISMATCH(401, "토큰 유형이 올바르지 않습니다"),

    /**
     * Authorization 헤더가 아예 없거나 {@code Bearer } 형식이 아니다.
     *
     * <p>토큰 파싱을 시도조차 하지 않은 경우이므로 {@link #TOKEN_INVALID} 와 구분한다.
     * {@code CustomAuthenticationEntryPoint} 의 기본 응답이다.
     */
    UNAUTHENTICATED(401, "인증이 필요합니다");

    private final int httpStatus;
    private final String message;

    AuthError(int httpStatus, String message) {
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
