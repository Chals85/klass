package com.toby.klass.enrollment.application.port.out;

import com.toby.klass.enrollment.domain.Enrollment;

/**
 * 수강 신청 영속 (쓰기).
 *
 * <h2>왜 {@code save} 하나뿐인가</h2>
 * 확정과 취소는 <b>영속 컨텍스트의 변경 감지</b>가 처리한다. 트랜잭션 안에서 조회한 엔티티를
 * {@code confirm()}·{@code cancel()} 로 바꾸면 커밋 시점에 UPDATE 가 나가므로 서비스가
 * {@code save} 를 다시 부를 이유가 없다. 불러도 무해하지만 <b>"저장해야 반영된다"는 잘못된
 * 인상</b>을 남긴다.
 *
 * <p>물리 삭제는 없다 (ERD 정본 §2). 취소는 상태 전이이지 삭제가 아니다.
 *
 * <p>Design Ref: enrollment-management §10.1
 */
public interface EnrollmentCommandPort {

    /**
     * 새 신청을 저장한다. 직접 신청과 대기열 승격 양쪽에서 호출된다.
     *
     * <p>{@code uq_enrollment_active} 가 활성 중복을 최종 방어한다 — 앱 검사를 통과했더라도
     * 동시 요청이 끼어들면 여기서 제약 위반이 난다.
     *
     * @return id 가 채워진 신청
     */
    Enrollment save(Enrollment enrollment);
}
