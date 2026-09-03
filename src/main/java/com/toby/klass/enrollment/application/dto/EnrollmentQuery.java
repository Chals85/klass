package com.toby.klass.enrollment.application.dto;

import com.toby.klass.enrollment.domain.EnrollmentStatus;
import com.toby.klass.enrollment.domain.error.EnrollmentError;

/**
 * 신청·대기 목록 조회 조건. 커서 페이지네이션의 입력이다.
 *
 * <h2>{@code KlassQuery} 와 왜 따로 두는가</h2>
 * 모양은 같지만 <b>상태 필터의 타입이 다르고</b>({@code EnrollmentStatus} vs
 * {@code KlassStatus}) 범위 위반 시 던지는 에러 코드도 다르다
 * ({@code INVALID_ENROLLMENT_PAGE_SIZE}). 제네릭으로 묶으면 그 두 차이를 타입 파라미터와
 * 생성자 인자로 밀어내야 하는데, 얻는 것보다 읽기가 나빠진다.
 *
 * <p>공통인 것은 <b>결과</b>({@code CursorPageResult})이고 그쪽은 이미 {@code common} 에
 * 있다 (D-24).
 *
 * <h2>size 상한을 여기서 막는 이유</h2>
 * 상한이 없으면 한 번의 요청으로 테이블 전체를 끌어갈 수 있다. 어댑터까지 내려가서 막으면
 * 이미 쿼리가 나간 뒤이므로 <b>조건을 만드는 시점</b>에 거부한다.
 *
 * <p>Design Ref: enrollment-management §6.3, §7.1
 *
 * @param cursor 직전 페이지 마지막 항목의 id. {@code null} 이면 첫 페이지
 * @param size   가져올 개수. 1~100
 * @param status 상태 필터. {@code null} 이면 전체. 대기 목록에서는 쓰이지 않는다
 */
public record EnrollmentQuery(Long cursor, int size, EnrollmentStatus status) {

    /** 기본 조회 개수. */
    public static final int DEFAULT_SIZE = 20;

    /** 한 번에 가져갈 수 있는 최대 개수. */
    public static final int MAX_SIZE = 100;

    public EnrollmentQuery {
        if (size < 1 || size > MAX_SIZE) {
            throw EnrollmentError.INVALID_ENROLLMENT_PAGE_SIZE.toException();
        }
    }

    /**
     * 커서 조회에 넘길 실제 limit.
     *
     * <p><b>하나를 더 가져온다.</b> {@code size + 1} 번째 행이 존재하면 다음 페이지가 있다는
     * 뜻이므로, {@code COUNT(*)} 를 따로 돌리지 않고 {@code hasNext} 를 알 수 있다.
     */
    public int fetchLimit() {
        return size + 1;
    }
}
