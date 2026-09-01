package com.toby.klass.auth.application.dto;

/**
 * 로그인 요청. 웹 DTO 를 애플리케이션 경계로 옮긴 형태다.
 *
 * <p>{@code LoginRequest} 와 필드가 같은데도 따로 두는 이유는, 웹 계층의 검증 애너테이션과
 * 직렬화 관심사가 유즈케이스로 넘어오지 않게 하기 위함이다. 유즈케이스는 HTTP 를 모른다.
 *
 * @param username 로그인 아이디
 * @param password <b>평문</b> 비밀번호. 해싱 비교는 서비스가 포트로 수행한다
 *
 * <p>Design Ref: §2.2 로그인 흐름, §10.1 네이밍 규약
 */
public record LoginCommand(String username, String password) {
}
