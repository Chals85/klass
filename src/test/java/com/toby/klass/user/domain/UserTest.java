package com.toby.klass.user.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toby.klass.common.domain.error.BusinessException;
import com.toby.klass.user.domain.error.UserError;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link User} 도메인 규칙 검증.
 *
 * <p>Design Ref: §8.3 L2 단위 테스트 #4
 */
class UserTest {

    private static final LocalDateTime NOW = LocalDateTime.parse("2026-08-30T00:00:00");

    @Test
    @DisplayName("생성하면 활성 상태이고 생성 시각이 채워진다")
    void registerCreatesEnabledUser() {
        User user = User.register("chals", "$2a$10$hashed", Set.of(Role.ROLE_USER), NOW);

        assertThat(user.isEnabled()).isTrue();
        assertThat(user.getUsername()).isEqualTo("chals");
        assertThat(user.getPassword()).isEqualTo("$2a$10$hashed");
        assertThat(user.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("권한 없이는 생성할 수 없다")
    void rejectsEmptyRoles() {
        assertThatThrownBy(() -> User.register("chals", "$2a$10$hashed", Set.of(), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("활성 계정은 검증을 통과한다")
    void enabledUserPassesVerification() {
        User user = User.register("chals", "$2a$10$hashed", Set.of(Role.ROLE_USER), NOW);

        // 예외가 나지 않아야 한다
        user.verifyEnabled();
    }

    @Test
    @DisplayName("권한 목록은 선언 순서를 유지한다")
    void roleNamesPreserveOrder() {
        Set<Role> ordered = new LinkedHashSet<>();
        ordered.add(Role.ROLE_ADMIN);
        ordered.add(Role.ROLE_USER);

        User user = User.register("admin", "$2a$10$hashed", ordered, NOW);

        assertThat(user.roleNames()).containsExactly("ROLE_ADMIN", "ROLE_USER");
    }

    @Test
    @DisplayName("권한 집합은 외부에서 변경할 수 없다")
    void rolesAreImmutableFromOutside() {
        User user = User.register("chals", "$2a$10$hashed", Set.of(Role.ROLE_USER), NOW);

        assertThatThrownBy(() -> user.roles().add(Role.ROLE_ADMIN))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("비활성 계정은 USER_DISABLED 로 거부된다")
    void disabledUserIsRejected() throws Exception {
        User user = User.register("chals", "$2a$10$hashed", Set.of(Role.ROLE_USER), NOW);
        // enabled 는 setter 가 없다(의도된 설계). 비활성 상태는 DB 에서 직접 바뀌므로
        // 테스트에서는 리플렉션으로 재현한다.
        var field = User.class.getDeclaredField("isEnabled");
        field.setAccessible(true);
        field.setBoolean(user, false);

        assertThatThrownBy(user::verifyEnabled)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(UserError.USER_DISABLED);
    }
}
