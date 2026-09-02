package com.toby.klass.klass.application.dto;

import com.toby.klass.klass.domain.KlassStatus;
import com.toby.klass.klass.domain.error.KlassError;

/**
 * 목록 조회 조건. 커서 페이지네이션의 입력이다.
 *
 * <h2>왜 offset 이 아니라 커서인가</h2>
 * {@code idx_klass_status(status, id DESC)} 가 정확히 이 조회를 위해 설계됐다 —
 * 인덱스가 이미 {@code id} 내림차순으로 저장돼 있어 정렬 작업이 0이다. offset 은 뒤쪽
 * 페이지로 갈수록 건너뛴 행을 모두 읽어야 해 느려진다 (ERD 정본 §3.6, Design §3.3).
 *
 * <h2>size 상한을 여기서 막는 이유</h2>
 * 상한이 없으면 한 번의 요청으로 테이블 전체를 끌어갈 수 있다. 어댑터까지 내려가서 막으면
 * 이미 쿼리가 나간 뒤이므로 <b>조건을 만드는 시점</b>에 거부한다.
 *
 * <p>Design Ref: §4.3 GET /v1/klasses, §6.1 INVALID_KLASS_PAGE_SIZE
 *
 * @param cursor 직전 페이지 마지막 항목의 id. {@code null} 이면 첫 페이지
 * @param size   가져올 개수. 1~100
 * @param status 상태 필터. {@code null} 이면 해당 목록의 기본 범위를 그대로 쓴다
 */
public record KlassQuery(Long cursor, int size, KlassStatus status) {

    /** 기본 조회 개수. */
    public static final int DEFAULT_SIZE = 20;

    /** 한 번에 가져갈 수 있는 최대 개수. */
    public static final int MAX_SIZE = 100;

    public KlassQuery {
        if (size < 1 || size > MAX_SIZE) {
            throw KlassError.INVALID_KLASS_PAGE_SIZE.toException();
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
