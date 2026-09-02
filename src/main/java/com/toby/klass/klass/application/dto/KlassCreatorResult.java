package com.toby.klass.klass.application.dto;

import com.toby.klass.user.domain.User;

/**
 * 응답에 실리는 개설자 정보.
 *
 * <p>{@code User} 전체를 노출하지 않는다 — 비밀번호 해시·권한·활성 여부는 강의를 보는
 * 사람이 알아야 할 정보가 아니다.
 *
 * <p>Design Ref: §4.3 응답 스펙
 *
 * @param id       개설자 PK
 * @param username 개설자 로그인 아이디
 */
public record KlassCreatorResult(Long id, String username) {

    public static KlassCreatorResult from(User creator) {
        return new KlassCreatorResult(creator.getId(), creator.getUsername());
    }
}
