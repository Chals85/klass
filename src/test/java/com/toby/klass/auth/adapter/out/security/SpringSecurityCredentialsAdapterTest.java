package com.toby.klass.auth.adapter.out.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toby.klass.auth.application.port.out.dto.VerifiedCredentials;
import com.toby.klass.auth.domain.error.AuthError;
import com.toby.klass.common.domain.error.BusinessException;
import com.toby.klass.common.domain.error.ErrorCode;
import com.toby.klass.infrastructure.security.config.AuthenticationConfig;
import com.toby.klass.user.application.port.out.UserQueryPort;
import com.toby.klass.user.domain.Role;
import com.toby.klass.user.domain.User;
import com.toby.klass.user.domain.error.UserError;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * {@link SpringSecurityCredentialsAdapter} 검증.
 *
 * <p>Spring 컨텍스트 없이 {@link AuthenticationConfig} 와 {@link DomainAuthenticationProvider}
 * 를 직접 조립한다. 설정 클래스가 만드는 것과 <b>같은 구성</b>을 검증해야 의미가 있기 때문이다.
 *
 * <h2>이 테스트가 지키는 것</h2>
 * <b>검사 순서</b>다. {@code DaoAuthenticationProvider} 는 기본적으로 계정 상태를 비밀번호보다
 * 먼저 검사하는데, 그러면 비밀번호를 몰라도 계정이 비활성인지 알아낼 수 있다.
 * {@link DomainAuthenticationProvider} 의 생성자가 pre 검사를 비우고 post 로 옮겨 이를 막는데,
 * 그 설정이 사라지면 아래 "비활성 계정 + 틀린 비밀번호" 테스트가 깨진다.
 *
 * <p>Design Ref: §2.2 로그인 흐름, §7 Security Considerations
 */
class SpringSecurityCredentialsAdapterTest {

    private static final LocalDateTime NOW = LocalDateTime.parse("2026-08-30T00:00:00");
    private static final String USERNAME = "chals";
    private static final String RAW_PASSWORD = "test";
    private static final Long USER_ID = 1L;

    private final Map<String, User> users = new HashMap<>();
    private SpringSecurityCredentialsAdapter adapter;

    @BeforeEach
    void setUp() {
        AuthenticationConfig config = new AuthenticationConfig();
        PasswordEncoder encoder = config.passwordEncoder();

        // 실제 설정과 동일한 파이프라인을 조립한다.
        AuthenticationManager manager = config.authenticationManager(
                new DomainAuthenticationProvider(
                        new DomainUserDetailsService(new InMemoryUserQueryPort()), encoder));
        adapter = new SpringSecurityCredentialsAdapter(manager);

        users.put(USERNAME, newUser(USERNAME, encoder.encode(RAW_PASSWORD), true));
    }

    private User newUser(String username, String encodedPassword, boolean enabled) {
        User user = User.register(username, encodedPassword, Set.of(Role.ROLE_USER), NOW);
        setField(user, "id", USER_ID);
        if (!enabled) {
            setField(user, "isEnabled", false);
        }
        return user;
    }

    private void setField(Object target, String name, Object value) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("테스트 데이터 준비 실패", e);
        }
    }

    private ErrorCode errorCodeOf(Throwable t) {
        return ((BusinessException) t).errorCode();
    }

    @Test
    @DisplayName("올바른 자격 증명이면 사용자 신원을 돌려준다")
    void verifiesValidCredentials() {
        VerifiedCredentials verified = adapter.verify(USERNAME, RAW_PASSWORD);

        assertThat(verified.userId()).isEqualTo(USER_ID);
        assertThat(verified.username()).isEqualTo(USERNAME);
        assertThat(verified.roles()).containsExactly("ROLE_USER");
    }

    @Test
    @DisplayName("존재하지 않는 아이디는 INVALID_CREDENTIALS 다")
    void unknownUsername() {
        assertThatThrownBy(() -> adapter.verify("nobody", RAW_PASSWORD))
                .isInstanceOf(BusinessException.class)
                .extracting(this::errorCodeOf)
                .isEqualTo(AuthError.INVALID_CREDENTIALS);
    }

    @Test
    @DisplayName("비밀번호가 틀리면 아이디 오류와 같은 코드다 — 사용자 열거 방지")
    void wrongPassword() {
        assertThatThrownBy(() -> adapter.verify(USERNAME, "wrong"))
                .extracting(this::errorCodeOf)
                .isEqualTo(AuthError.INVALID_CREDENTIALS);
    }

    @Test
    @DisplayName("비밀번호를 제공하지 않으면 INVALID_CREDENTIALS 다")
    void nullPassword() {
        assertThatThrownBy(() -> adapter.verify(USERNAME, null))
                .extracting(this::errorCodeOf)
                .isEqualTo(AuthError.INVALID_CREDENTIALS);
    }

    @Test
    @DisplayName("비활성 계정이라도 비밀번호가 맞아야 USER_DISABLED 를 알려준다")
    void disabledAccountWithCorrectPassword() {
        PasswordEncoder encoder = new AuthenticationConfig().passwordEncoder();
        users.put("disabled", newUser("disabled", encoder.encode(RAW_PASSWORD), false));

        assertThatThrownBy(() -> adapter.verify("disabled", RAW_PASSWORD))
                .extracting(this::errorCodeOf)
                .isEqualTo(UserError.USER_DISABLED);
    }

    @Test
    @DisplayName("비활성 계정에 틀린 비밀번호를 넣으면 계정 상태를 노출하지 않는다")
    void disabledAccountWithWrongPasswordHidesStatus() {
        PasswordEncoder encoder = new AuthenticationConfig().passwordEncoder();
        users.put("disabled", newUser("disabled", encoder.encode(RAW_PASSWORD), false));

        // DaoAuthenticationProvider 의 기본 설정(pre-authentication checks)이라면
        // 비밀번호를 보기도 전에 DisabledException 이 나서 USER_DISABLED 가 돌아온다.
        // AuthenticationConfig 가 검사를 post 로 옮겼기 때문에 여기서는 자격 증명 오류가 나온다.
        assertThatThrownBy(() -> adapter.verify("disabled", "wrong"))
                .as("계정 상태 검사가 비밀번호 검증보다 먼저면 사용자 열거가 가능해진다")
                .extracting(this::errorCodeOf)
                .isEqualTo(AuthError.INVALID_CREDENTIALS);
    }

    /** 테스트용 사용자 저장소. DB 없이 인증 파이프라인만 검증한다. */
    private class InMemoryUserQueryPort implements UserQueryPort {

        @Override
        public Optional<User> findById(Long id) {
            return users.values().stream().filter(u -> id.equals(u.getId())).findFirst();
        }

        @Override
        public Optional<User> findByUsername(String username) {
            return Optional.ofNullable(users.get(username));
        }

    }
}
