package com.toby.klass.user.domain;

/**
 * 사용자 권한. 값 객체(enum)이며 {@code User} 애그리거트 내부에 속한다.
 *
 * <p>{@code ROLE_} 접두어는 Spring Security 의 관례다. {@code hasRole("USER")} 는
 * 내부적으로 {@code ROLE_USER} 를 찾으므로, 이름을 바꾸면 권한 검사가 조용히 실패한다.
 *
 * <p>이 예제는 권한 세분화를 다루지 않는다. {@code ROLE_ADMIN} 은 권한이 여러 개일 수
 * 있음을 보여주는 예시 값이며, 별도 접근 제어가 걸려 있지는 않다.
 *
 * <p>Design Ref: §3.1 — Entity Definition
 */
public enum Role {

    /** 기본 사용자 권한. 초기 시딩 계정({@code chals})이 갖는다. */
    ROLE_USER,

    /** 관리자 권한. 현재 이 권한으로만 접근 가능한 엔드포인트는 없다. */
    ROLE_ADMIN,

    /**
     * 강의 개설자.
     *
     * <p><b>원본에 없던 값이다.</b> ERD 정본 §3.3 이 확정한 3종 중 하나이며, §7 의 소유권
     * 검사({@code klass.creator_id == sub})가 이 권한을 전제로 한다.
     *
     * <p>한 사용자가 수강생이면서 크리에이터일 수 있어야 하므로 단일 role 컬럼이 아니라
     * {@code user_roles} 다중 권한 구조를 그대로 쓴다.
     *
     * <p>Design Ref: §3.2 ENUM, §12 D-4 · D-6
     */
    ROLE_CREATOR
}
