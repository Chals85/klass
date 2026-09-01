package com.toby.klass.user.domain.error;

import com.toby.klass.common.domain.error.ErrorCode;

/**
 * 사용자 컨텍스트의 에러 코드.
 *
 * <p>로그인 실패는 여기에 없다. {@code AuthError.INVALID_CREDENTIALS} 가 담당하며,
 * 그 이유는 해당 상수의 문서를 참조.
 *
 * <p>Design Ref: §6.1 — Error Code Definition
 */
public enum UserError implements ErrorCode {

    /**
     * 사용자를 찾을 수 없다.
     *
     * <p>토큰은 유효한데 그 사이 사용자가 삭제된 경우에 발생한다. 로그인 과정에서는
     * 쓰이지 않는다 — 그쪽은 사용자 열거를 막기 위해 {@code INVALID_CREDENTIALS} 로 통일한다.
     */
    USER_NOT_FOUND(404, "사용자를 찾을 수 없습니다"),

    /**
     * 비활성화된 계정.
     *
     * <p>로그인 흐름에서 <b>비밀번호 검증 이후에</b> 확인한다. 순서를 바꾸면 비밀번호를
     * 몰라도 계정의 존재·활성 여부를 알아낼 수 있다.
     */
    USER_DISABLED(401, "비활성화된 계정입니다");

    private final int httpStatus;
    private final String message;

    UserError(int httpStatus, String message) {
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
