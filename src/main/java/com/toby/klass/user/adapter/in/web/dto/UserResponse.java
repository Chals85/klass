package com.toby.klass.user.adapter.in.web.dto;

import com.toby.klass.user.application.dto.UserResult;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 사용자 조회 응답 본문.
 *
 * <p>비밀번호 필드가 없다. 도메인 엔티티를 직접 직렬화하지 않고 이 record 로 옮기므로,
 * 엔티티에 필드를 추가해도 실수로 노출될 일이 없다.
 *
 * <p>Design Ref: §4.2 GET /v1/users/me
 *
 * @param id        사용자 PK
 * @param username  로그인 아이디
 * @param roles     권한 목록
 * @param isEnabled 계정 활성 여부
 * @param createdAt 가입 시각
 */
public record UserResponse(Long id, String username, List<String> roles,
                           boolean isEnabled, LocalDateTime createdAt) {

    /**
     * 유즈케이스 결과를 응답 본문으로 변환한다.
     */
    public static UserResponse from(UserResult result) {
        return new UserResponse(
                result.id(), result.username(), result.roles(), result.isEnabled(), result.createdAt());
    }
}
