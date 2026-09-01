package com.toby.klass.infrastructure.security.principal;

import java.time.Instant;
import java.util.List;

/**
 * 인증된 사용자. {@code SecurityContext} 의 principal 로 들어간다.
 *
 * <p>컨트롤러는 {@code @AuthenticationPrincipal} 로 이 객체를 받는다.
 * 요청 본문의 사용자 id 를 믿지 않고 여기서 꺼내야 남의 자원을 건드리는 것을 막는다.
 *
 * <h2>왜 토큰 정보까지 들고 있는가</h2>
 * {@code jti}/{@code tokenExpiresAt} 은 <b>로그아웃 때 필요하다</b>. 폐기 목록에
 * 올리려면 현재 Access 토큰의 {@code jti} 와 만료 시각을 알아야 하는데, 필터가 이미
 * 파싱해 둔 값을 여기 실어 보내면 컨트롤러가 Authorization 헤더를 다시 읽고 토큰을
 * 두 번 파싱할 필요가 없다.
 *
 * <p>토큰 <b>원문</b>은 담지 않는다. 로그나 디버거에 노출될 수 있고, 폐기에는
 * {@code jti} 만 있으면 충분하다.
 *
 * <p>Design Ref: §2.2 인증된 요청 흐름, §2.2 로그아웃 흐름
 *
 * @param jti        현재 Access 토큰의 {@code jti}
 * @param tokenExpiresAt 현재 Access 토큰의 만료 시각(JWT 의 {@code exp})
 */
public record AuthenticatedUser(Long id, String username, List<String> roles,
                                String jti, Instant tokenExpiresAt) {

    /** 방어적 복사로 불변을 보장한다. */
    public AuthenticatedUser {
        roles = roles == null ? List.of() : List.copyOf(roles);
    }
}
