package com.toby.klass.klass.application.port.out;

import com.toby.klass.klass.domain.Klass;

/**
 * 강의 영속 (쓰기).
 *
 * <h2>왜 {@code save} 하나뿐인가</h2>
 * 수정과 상태 전이는 <b>영속 컨텍스트의 변경 감지(dirty checking)</b>가 처리한다. 트랜잭션
 * 안에서 조회한 엔티티를 도메인 메서드로 바꾸면 커밋 시점에 UPDATE 가 나가므로, 서비스가
 * {@code save} 를 다시 부를 이유가 없다. 불러도 무해하지만 <b>"저장해야 반영된다"는 잘못된
 * 인상</b>을 남긴다.
 *
 * <p>Design Ref: §9.1 계층 배치
 */
public interface KlassCommandPort {

    /**
     * 새 강의를 저장한다.
     *
     * @return id 가 채워진 강의
     */
    Klass save(Klass klass);
}
