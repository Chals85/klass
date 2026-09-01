package com.toby.klass.auth.application.port.out.dto;

import java.util.List;

/**
 * 자격 증명 검증에 성공한 사용자의 신원.
 *
 * <p>검증 실패는 이 타입으로 표현하지 않는다. 포트가 예외를 던지므로, 이 객체를 손에
 * 넣었다는 것 자체가 인증에 성공했다는 뜻이다. "성공 여부 플래그"를 두면 호출자가
 * 검사를 잊을 수 있다.
 *
 * <p>토큰 발급에 필요한 최소 정보만 담는다. 비밀번호는 물론 들어가지 않는다.
 *
 * @param userId   사용자 PK
 * @param username 로그인 아이디
 * @param roles    권한 이름 목록
 *
 * <p>Design Ref: §2.4 Port Signatures
 */
public record VerifiedCredentials(Long userId, String username, List<String> roles) {

    /** 방어적 복사로 불변을 보장한다. */
    public VerifiedCredentials {
        roles = roles == null ? List.of() : List.copyOf(roles);
    }
}
