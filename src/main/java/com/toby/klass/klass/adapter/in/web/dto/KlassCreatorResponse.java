package com.toby.klass.klass.adapter.in.web.dto;

import com.toby.klass.klass.application.dto.KlassCreatorResult;

/**
 * 응답에 실리는 개설자 정보.
 *
 * <p>{@code User} 를 그대로 내보내지 않는다 — 비밀번호 해시·권한·활성 여부는 강의를 보는
 * 사람이 알 이유가 없다.
 *
 * <p>Design Ref: §4.3 응답 스펙
 *
 * @param id       개설자 PK
 * @param username 개설자 로그인 아이디
 */
public record KlassCreatorResponse(Long id, String username) {

    public static KlassCreatorResponse from(KlassCreatorResult creator) {
        return new KlassCreatorResponse(creator.id(), creator.username());
    }
}
