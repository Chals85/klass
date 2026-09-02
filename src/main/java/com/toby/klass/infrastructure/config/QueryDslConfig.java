package com.toby.klass.infrastructure.config;

import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * QueryDSL 배선.
 *
 * <h2>이 저장소에서 QueryDSL 의 첫 실사용이다</h2>
 * 의존성과 애너테이션 프로세서는 1차에서 넣었지만(project-setup Design §12 D-3) 스파이크로
 * 판정만 하고 실사용처가 없었다. 강의 목록 조회가 첫 사용처다.
 *
 * <h2>왜 QueryDSL 인가 — 파생 쿼리로는 조합이 폭발한다</h2>
 * 목록 조회의 조건이 세 갈래(공개 목록 / 상태 지정 / 내 강의 목록)이고 각각 커서 유무가
 * 갈려, Spring Data 파생 쿼리로 풀면 메서드가 여섯 개가 된다. <b>파생 쿼리의 메서드명은
 * 엔티티 속성명과 일치해야 하는데 컴파일러가 그것을 검사하지 않는다</b> — 어긋나면 앱이
 * 기동조차 못 한다. QueryDSL 은 {@code QKlass} 를 통해 그 검사를 컴파일 타임으로 옮긴다.
 *
 * <p>Design Ref: §2.0 Option C 선택 근거, §9.3 QueryDSL 배선
 */
@Configuration
public class QueryDslConfig {

    /**
     * QueryDSL 질의 팩토리.
     *
     * <p>{@link EntityManager} 는 프록시로 주입되며 요청·트랜잭션마다 실제 인스턴스로
     * 위임된다. 따라서 이 팩토리를 싱글턴으로 둬도 스레드 안전하다.
     */
    @Bean
    public JPAQueryFactory jpaQueryFactory(EntityManager entityManager) {
        return new JPAQueryFactory(entityManager);
    }
}
