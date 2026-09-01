package com.toby.klass.auth.application.port.in;

import com.toby.klass.auth.application.dto.LoginCommand;
import com.toby.klass.auth.application.dto.TokenResult;

/**
 * 로그인 유즈케이스.
 *
 * <p>컨트롤러는 이 인터페이스만 알고 구현체({@code AuthService})는 모른다.
 *
 * <p>Design Ref: §2.2 로그인 흐름, §10.1 네이밍 규약 — 인바운드 포트는 {X}UseCase
 */
public interface LoginUseCase {

    /**
     * 자격 증명을 검증하고 토큰 쌍을 발급한다.
     *
     * @param command 로그인 요청
     * @return 새 Access/Refresh 토큰
     * @throws com.toby.klass.common.domain.error.BusinessException
     *         {@code INVALID_CREDENTIALS} 아이디가 없거나 비밀번호가 틀림 —
     *         <b>두 경우를 구분하지 않는다</b>(사용자 열거 방지) /
     *         {@code USER_DISABLED} 비활성 계정
     */
    TokenResult login(LoginCommand command);
}
