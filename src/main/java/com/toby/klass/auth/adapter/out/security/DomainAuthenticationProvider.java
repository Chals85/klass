package com.toby.klass.auth.adapter.out.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AccountStatusUserDetailsChecker;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 아이디·비밀번호 인증 제공자. 비밀번호 비교를 직접 수행한다.
 *
 * <h2>왜 상속해서 직접 구현하는가</h2>
 * {@link DaoAuthenticationProvider} 를 그대로 써도 로그인은 동작한다. 그럼에도 확장하는
 * 이유는 <b>비교가 일어나는 지점을 코드에 드러내고, 그 자리에 우리 관심사를 붙이기</b>
 * 위해서다. 기본 구현을 쓰면 비밀번호가 어디서 어떻게 비교되는지 프레임워크 안에 묻힌다.
 *
 * <p>이 클래스가 여는 확장 지점의 예:
 * <ul>
 *   <li>로그인 실패 로깅 — 브루트포스 시도 추적의 출발점</li>
 *   <li>실패 횟수 누적 후 계정 잠금</li>
 *   <li>비밀번호 만료 정책, 레거시 해시 알고리즘 마이그레이션</li>
 * </ul>
 *
 * <h2>부모에게 남겨둔 것</h2>
 * {@code retrieveUser()} 는 재정의하지 않는다. 부모 구현에는 <b>타이밍 공격 방어</b>가
 * 들어 있다 — 사용자가 존재하지 않을 때도 더미 해시를 한 번 비교해서, 응답 시간으로
 * 아이디의 존재 여부를 알아내지 못하게 한다. 직접 구현하면 이 방어를 잃기 쉽다.
 *
 * <p>Design Ref: §2.2 로그인 흐름, §7 Security Considerations
 */
@Component
public class DomainAuthenticationProvider extends DaoAuthenticationProvider {

    private static final Logger log = LoggerFactory.getLogger(DomainAuthenticationProvider.class);

    /**
     * 인증 파이프라인을 조립하고 계정 상태 검사 순서를 조정한다.
     *
     * @param userDetailsService 도메인 사용자 공급자
     * @param passwordEncoder    비밀번호 비교기. 시딩에 쓰는 것과 같은 인스턴스여야 한다
     */
    public DomainAuthenticationProvider(UserDetailsService userDetailsService,
                                        PasswordEncoder passwordEncoder) {
        super(userDetailsService);
        setPasswordEncoder(passwordEncoder);

        // 계정 상태 검사를 비밀번호 검증 뒤로 미룬다.
        //
        // 기본값은 pre 단계다. 그대로 두면 비활성 계정에 아무 비밀번호나 넣어도
        // DisabledException 이 돌아오므로, 비밀번호를 몰라도 계정의 존재·상태를
        // 알아낼 수 있다(사용자 열거 공격).
        setPreAuthenticationChecks(userDetails -> {
            // 의도적으로 비어 있다. 상태 검사는 post 단계에서 한다.
        });
        setPostAuthenticationChecks(new AccountStatusUserDetailsChecker());
    }

    /**
     * 비밀번호를 비교한다. Spring Security 인증 파이프라인이 호출하는 훅이다.
     *
     * <p>호출 시점은 {@code retrieveUser()} 로 사용자를 찾은 <b>직후</b>이며,
     * 계정 상태 검사보다 <b>앞</b>이다(위 생성자에서 순서를 조정했다).
     *
     * <h4>실패 메시지를 구분하지 않는 이유</h4>
     * 비밀번호 미제공과 불일치를 모두 같은 {@link BadCredentialsException} 으로 던진다.
     * 어차피 {@code SpringSecurityCredentialsAdapter} 가 {@code INVALID_CREDENTIALS} 하나로
     * 번역하지만, 여기서부터 구분하지 않아야 실수로 원인이 새어 나가지 않는다.
     * 상세는 로그에만 남긴다.
     *
     * @param userDetails    DB 에서 찾은 사용자. 저장된 해시를 들고 있다
     * @param authentication 인증 요청. {@code credentials} 에 입력된 평문이 들어 있다
     * @throws BadCredentialsException 비밀번호가 없거나 일치하지 않는 경우
     */
    @Override
    protected void additionalAuthenticationChecks(UserDetails userDetails,
                                                  UsernamePasswordAuthenticationToken authentication) {
        String username = userDetails.getUsername();

        if (authentication.getCredentials() == null) {
            log.debug("로그인 실패 — 비밀번호가 제공되지 않았습니다. username={}", username);
            throw new BadCredentialsException("자격 증명이 올바르지 않습니다");
        }

        String presentedPassword = authentication.getCredentials().toString();

        // 저장된 값은 BCrypt 해시이고 솔트가 섞여 있다. 문자열 비교로는 절대 검증할 수 없고,
        // matches() 는 내부적으로 상수 시간 비교를 수행해 타이밍 공격에도 대응한다.
        if (!getPasswordEncoder().matches(presentedPassword, userDetails.getPassword())) {
            // 실서비스라면 이 지점이 실패 횟수 누적·계정 잠금·알림의 연결점이 된다.
            log.debug("로그인 실패 — 비밀번호가 일치하지 않습니다. username={}", username);
            throw new BadCredentialsException("자격 증명이 올바르지 않습니다");
        }

        log.debug("비밀번호 검증 통과. username={}", username);
    }
}
