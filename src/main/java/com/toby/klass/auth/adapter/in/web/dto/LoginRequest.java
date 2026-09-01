package com.toby.klass.auth.adapter.in.web.dto;

import com.toby.klass.auth.application.dto.LoginCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 로그인 요청 본문.
 *
 * <p>검증에 실패하면 {@code VALIDATION_ERROR} 와 함께 필드별 메시지가 돌아간다.
 * 길이 제약은 DB 컬럼과 맞춰 둔다 — 여기서 걸러야 무의미한 조회가 발생하지 않는다.
 *
 * <p>Design Ref: §4.2 POST /v1/auth/login
 *
 * @param password 평문 비밀번호
 */
public record LoginRequest(
        @NotBlank(message = "아이디는 필수입니다")
        @Size(max = 50, message = "아이디는 50자를 넘을 수 없습니다")
        String username,

        @NotBlank(message = "비밀번호는 필수입니다")
        String password) {

    /**
     * 애플리케이션 계층으로 넘길 커맨드
     */
    public LoginCommand toCommand() {
        return new LoginCommand(username, password);
    }
}
