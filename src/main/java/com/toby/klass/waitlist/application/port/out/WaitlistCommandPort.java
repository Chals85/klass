package com.toby.klass.waitlist.application.port.out;

import com.toby.klass.waitlist.domain.Waitlist;

/**
 * 대기열 영속 (쓰기).
 *
 * <p>{@code save} 하나뿐인 이유는 {@code EnrollmentCommandPort} 와 같다 — 승격과 포기는
 * 변경 감지가 처리한다.
 *
 * <h2>일괄 정리에 벌크 UPDATE 를 두지 않는다</h2>
 * 강의 마감 시 잔여 {@code WAITING} 을 전부 {@code CANCELLED} 로 바꿔야 하는데(ERD 정본
 * §4.8 5번), JPQL {@code update} 한 방이면 될 일이다. 그러지 않는 이유가 둘 있다.
 *
 * <ul>
 *   <li>JPQL 문자열은 CLAUDE.md 가 지목한 "컴파일러가 잡지 못하는 지점" 1번이고, 틀리면
 *       <b>부트스트랩에서 앱이 통째로 안 뜬다</b></li>
 *   <li>벌크 UPDATE 는 <b>영속 컨텍스트를 우회</b>한다. 같은 트랜잭션에서 이미 로딩한
 *       {@code Waitlist} 가 있으면 그 인스턴스는 옛 상태로 남아, 이후 코드가 낡은 값을 본다</li>
 * </ul>
 *
 * <p>대기자 수가 정원 규모를 넘지 않으므로 {@code findAllWaiting} 후 도메인 메서드를
 * 반복하는 편이 안전하고 읽힌다.
 *
 * <p>Design Ref: enrollment-management §4.3 ⑦, §10.1
 */
public interface WaitlistCommandPort {

    /**
     * 새 대기 등록을 저장한다.
     *
     * <p>{@code uq_waitlist_position} 이 순번 경합을, {@code uq_waitlist_waiting} 이 활성
     * 중복을 최종 방어한다. 둘 다 실제로 거부함을 module-0 에서 확인했다.
     *
     * @return id 가 채워진 대기 행
     */
    Waitlist save(Waitlist waitlist);
}
