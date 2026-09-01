package com.toby.klass.infrastructure.bootstrap;

import com.toby.klass.user.domain.Role;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 기동 시 시딩할 기본 계정 목록.
 *
 * <h2>원본과 다른 점 (Design §12 D-6)</h2>
 * 원본은 계정 하나만 담는 평평한 record 였다({@code app.default-user.username} 등).
 * 이 프로젝트는 <b>권한별로 최소 두 계정</b>이 필요하다 — ERD 정본 §7 의 권한 검증 지점
 * ({@code creator_id == sub})을 확인하려면 {@code ROLE_CREATOR} 를 가진 계정이 있어야 한다.
 * 그래서 목록 구조로 바꿨다.
 *
 * <p>Design Ref: §8.7 Seed Data, §12 D-6
 */
@ConfigurationProperties(prefix = "app")
@Validated
public record DefaultUserProperties(@NotEmpty @Valid List<DefaultUser> defaultUsers) {

    /**
     * 시딩할 계정 하나.
     *
     * @param username 로그인 아이디. 유일해야 한다
     * @param password <b>평문</b>. 시딩 시점에 해싱된다
     * @param roles    부여할 권한. 비어 있으면 안 된다
     */
    public record DefaultUser(
            @NotBlank String username,
            @NotBlank String password,
            @NotEmpty List<Role> roles) {
    }
}
