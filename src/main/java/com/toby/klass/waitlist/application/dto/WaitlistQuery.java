package com.toby.klass.waitlist.application.dto;

import com.toby.klass.waitlist.domain.error.WaitlistError;

/**
 * 대기 목록 조회 조건. 커서 페이지네이션의 입력이다.
 *
 * <h2>왜 {@code EnrollmentQuery} 를 쓰지 않는가</h2>
 * 처음에는 그것을 재사용했다. 모양이 같고 커서 규약도 같았기 때문이다. 그러나 <b>대기 목록은
 * 상태 필터를 쓰지 않아</b> 호출부가 매번 {@code null} 을 넘겼고, 쓰지도 않는 필드 하나 때문에
 * {@code waitlist} 의 포트·어댑터가 {@code enrollment} 패키지를 경유했다.
 *
 * <p><b>이 저장소가 같은 판단을 이미 한 번 내렸다.</b> {@code CursorPageResult} 는 원래
 * {@code klass/application/dto} 에 있었는데 "이것이 {@code klass} 에 있으면 {@code enrollment}
 * 가 {@code klass} 를 경유해야 재사용된다 — 공통화 근거가 스스로를 배반한다"는 이유로
 * {@code common} 으로 옮겨졌다 (klass-management D-24). 여기는 방향이 반대다 — 공통화할
 * 만큼 같지 않으므로 <b>각자 갖는다.</b>
 *
 * <p>결과: {@code waitlist} 가 {@code enrollment} 를 참조하는 지점이 4곳에서 2곳으로 줄었다.
 * 남은 2곳({@code WaitlistController → port.in}, {@code WaitlistResponse → WaitlistResult})은
 * <b>대기열에 서비스를 두지 않기로 한 결정(D-29)의 직접 귀결</b>이라 없앨 대상이 아니다.
 *
 * <h2>size 상한을 여기서 막는 이유</h2>
 * 상한이 없으면 한 번의 요청으로 테이블 전체를 끌어갈 수 있다. 어댑터까지 내려가서 막으면
 * 이미 쿼리가 나간 뒤이므로 <b>조건을 만드는 시점</b>에 거부한다.
 *
 * <p>Design Ref: enrollment-management §6.3, D-46
 *
 * @param cursor 직전 페이지 마지막 항목의 id. {@code null} 이면 첫 페이지
 * @param size   가져올 개수. 1~100
 */
public record WaitlistQuery(Long cursor, int size) {

    /** 기본 조회 개수. */
    public static final int DEFAULT_SIZE = 20;

    /** 한 번에 가져갈 수 있는 최대 개수. */
    public static final int MAX_SIZE = 100;

    public WaitlistQuery {
        if (size < 1 || size > MAX_SIZE) {
            throw WaitlistError.WAITLIST_PAGE_SIZE_OUT_OF_RANGE.toException();
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
