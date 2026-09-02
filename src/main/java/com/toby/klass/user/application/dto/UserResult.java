package com.toby.klass.user.application.dto;

import com.toby.klass.user.domain.User;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 사용자 조회 결과.
 *
 * <p>도메인 엔티티를 그대로 웹 계층에 넘기지 않기 위한 경계다. 엔티티를 노출하면
 * {@code password} 같은 필드가 직렬화될 위험이 있고, 웹 응답의 형태 변경이 도메인
 * 수정으로 번진다.
 *
 * <p>Design Ref: §4.2 GET /v1/users/me, §1.2 경계에서의 타입 차단
 *
 * @param id        사용자 PK
 * @param username  로그인 아이디
 * @param roles     권한 이름 목록. {@code User.roleNames()} 의 결과다
 * @param isEnabled 계정 활성 여부
 * @param createdAt 가입 시각
 */
public record UserResult(Long id, String username, List<String> roles,
                         boolean isEnabled, LocalDateTime createdAt) {

    /**
     * 도메인 엔티티를 조회 결과로 변환한다.
     *
     * <p>비밀번호는 <b>의도적으로 옮기지 않는다</b>.
     */
    public static UserResult from(User user) {
        return new UserResult(
                user.getId(), user.getUsername(), user.roleNames(), user.isEnabled(), user.getCreatedAt());
    }
}
