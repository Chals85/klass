package com.toby.klass.common.application.dto;

import java.util.List;
import java.util.function.Function;

/**
 * 커서 페이지네이션 결과.
 *
 * <h2>총 개수를 제공하지 않는다</h2>
 * 커서 방식이 offset 대비 빠른 이유가 <b>건너뛴 행을 읽지 않는 것</b>인데, 총 개수를 주려면
 * {@code COUNT(*)} 로 전체를 세야 해서 그 이점이 사라진다. "몇 개 중 몇 번째"를 보여줘야
 * 하는 화면이 생기면 그때 별도 엔드포인트로 다룬다.
 *
 * <h2>왜 {@code common} 에 있는가</h2>
 * 수강신청·대기열 목록도 같은 규격을 쓴다. <b>이것이 {@code klass} 패키지에 있으면
 * {@code enrollment} 가 {@code klass} 를 경유해야 재사용된다</b> — 공통화 근거가 스스로를
 * 배반한다. 처음에는 {@code klass/application/dto} 에 뒀다가 Check 단계에서 이 모순이
 * 발견돼 옮겼다 (D-24).
 *
 * <p>Design Ref: §4.3 커서 응답 규격, D-15 ({@code hasNext} 의 {@code is} 접두어 예외), D-24
 *
 * @param items      이 페이지의 항목. {@code size} 개 이하
 * @param hasNext    다음 페이지 존재 여부
 * @param nextCursor 다음 요청에 넣을 커서. {@code hasNext} 가 {@code false} 면 {@code null}
 */
public record CursorPageResult<T>(List<T> items, boolean hasNext, Long nextCursor) {

    /**
     * {@code size + 1} 개를 가져온 결과를 페이지로 자른다.
     *
     * <p>초과분 1건은 <b>다음 페이지가 있다는 신호로만 쓰고 버린다</b>. 그것을 응답에 담으면
     * 요청한 개수보다 하나 많은 목록이 나간다.
     *
     * @param fetched      호출자의 {@code fetchLimit()} 개까지 조회된 결과
     *                     ({@code KlassQuery}·{@code EnrollmentQuery}·{@code WaitlistQuery})
     * @param size         호출자가 요청한 개수
     * @param cursorOf     항목에서 커서 값을 꺼내는 함수
     */
    public static <T> CursorPageResult<T> of(List<T> fetched, int size, Function<T, Long> cursorOf) {
        boolean hasNext = fetched.size() > size;
        List<T> items = hasNext ? List.copyOf(fetched.subList(0, size)) : List.copyOf(fetched);
        Long nextCursor = hasNext ? cursorOf.apply(items.get(items.size() - 1)) : null;
        return new CursorPageResult<>(items, hasNext, nextCursor);
    }

    /** 담긴 항목을 다른 타입으로 바꾼다. 어댑터의 엔티티 페이지를 서비스가 Result 페이지로 옮길 때 쓴다. */
    public <R> CursorPageResult<R> map(Function<T, R> mapper) {
        return new CursorPageResult<>(items.stream().map(mapper).toList(), hasNext, nextCursor);
    }
}
