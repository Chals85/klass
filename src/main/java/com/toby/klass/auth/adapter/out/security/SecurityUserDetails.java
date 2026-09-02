package com.toby.klass.auth.adapter.out.security;

import com.toby.klass.user.domain.User;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Spring Security 인증 파이프라인이 다루는 사용자 표현.
 *
 * <h2>{@code AuthenticatedUser} 와 무엇이 다른가</h2>
 * 이름이 비슷하지만 쓰임이 다르다.
 * <ul>
 *   <li>이 클래스 — <b>로그인 시점</b>에만 쓴다. 비밀번호 해시를 들고 있어야
 *       {@code DaoAuthenticationProvider} 가 비교할 수 있다</li>
 *   <li>{@code AuthenticatedUser} — <b>토큰 인증 이후</b>의 principal. 비밀번호를 모른다</li>
 * </ul>
 * 둘을 하나로 합치면 토큰 인증 경로에도 비밀번호 필드가 따라다니게 된다.
 *
 * <p>Spring 의 기본 {@code org.springframework.security.core.userdetails.User} 를 쓰지 않는
 * 이유는 {@code userId} 를 담아야 하기 때문이다. 그것 없이는 인증 성공 후 토큰의
 * {@code sub} 에 넣을 값을 다시 조회해야 한다.
 *
 * <p>Design Ref: §2.2 로그인 흐름
 *
 * @param userId      사용자 PK. 인증 성공 후 토큰의 {@code sub} 에 넣을 값이다
 * @param username    로그인 아이디. {@code UserDetails.getUsername()} 이 그대로 돌려준다
 * @param password    BCrypt 해시. {@code DaoAuthenticationProvider} 가 입력값과 비교한다
 * @param isEnabled   활성 여부. 계정 상태 검사에 쓰인다
 * @param authorities Security 권한 목록. {@code User.roleNames()} 를 {@code SimpleGrantedAuthority} 로
 *                    옮긴 것이다
 */
public record SecurityUserDetails(Long userId, String username, String password,
                                  boolean isEnabled,
                                  Collection<? extends GrantedAuthority> authorities)
        implements UserDetails {

    /**
     * 도메인 사용자를 Security 표현으로 변환한다.
     */
    public static SecurityUserDetails from(User user) {
        List<GrantedAuthority> authorities = user.roleNames().stream()
                .map(SimpleGrantedAuthority::new)
                .map(GrantedAuthority.class::cast)
                .toList();
        return new SecurityUserDetails(
                user.getId(), user.getUsername(), user.getPassword(), user.isEnabled(), authorities);
    }

    /**
     * 권한을 문자열 목록으로 변환한다.
     *
     * @return 권한 이름 목록. 토큰 클레임에 실을 형태다
     */
    public List<String> roleNames() {
        return authorities.stream().map(GrantedAuthority::getAuthority).toList();
    }

    // ── UserDetails 계약 ──
    // record 접근자(username(), password())와 이름이 달라 명시 구현이 필요하다.

    /** {@inheritDoc} */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    /** {@inheritDoc} */
    @Override
    public String getPassword() {
        return password;
    }

    /** {@inheritDoc} */
    @Override
    public String getUsername() {
        return username;
    }

    // 계정 만료·잠금·비밀번호 만료는 이 예제의 범위 밖이다.
    // UserDetails 의 기본 구현이 true 를 돌려주므로 재정의하지 않는다.
}
