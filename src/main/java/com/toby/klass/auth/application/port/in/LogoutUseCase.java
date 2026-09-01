package com.toby.klass.auth.application.port.in;

import com.toby.klass.auth.application.dto.LogoutCommand;

/**
 * 로그아웃 유즈케이스.
 *
 * <p>Design Ref: §2.2 로그아웃 흐름
 */
public interface LogoutUseCase {

    /**
     * Refresh 토큰을 폐기한다.
     *
     * <p><b>멱등하다.</b> 이미 로그아웃했거나 남의 토큰을 넣어도 예외 없이 통과한다.
     * 토큰의 존재 여부를 응답으로 알려주지 않기 위함이다.
     *
     * @param command 로그아웃 요청
     */
    void logout(LogoutCommand command);
}
