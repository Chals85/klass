package com.toby.klass.common.adapter.in.web.dto;

import com.toby.klass.common.application.dto.CursorPageResult;
import java.util.List;
import java.util.function.Function;

/**
 * 커서 페이지네이션 응답 봉투. 도메인 공용이다.
 *
 * <h2>{@code hasNext} 가 {@code is} 접두어 규약의 예외인 이유</h2>
 * 이 저장소는 boolean 에 전 계층 {@code is} 접두어를 요구한다. 그 규칙의 <b>목적은
 * "이름만 보고 boolean 임을 알 수 있게" 하는 것</b>이고 {@code hasNext} 는 이미 그것을
 * 충족한다. {@code isHasNext} 는 규칙의 문자를 지키면서 목적을 배반한다.
 * 이후 boolean 필드는 {@code is} 아니면 {@code has} 접두어를 쓴다 (Design D-15).
 *
 * <h2>총 개수를 주지 않는다</h2>
 * 커서 방식이 빠른 이유가 건너뛴 행을 읽지 않는 것인데, 총 개수를 주려면 {@code COUNT(*)} 로
 * 전체를 세야 해서 그 이점이 사라진다.
 *
 * <p>Design Ref: §4.3 커서 응답 규격
 *
 * @param items      이 페이지의 항목
 * @param hasNext    다음 페이지 존재 여부
 * @param nextCursor 다음 요청에 넣을 커서. {@code hasNext} 가 {@code false} 면 {@code null}
 */
public record CursorPageResponse<T>(List<T> items, boolean hasNext, Long nextCursor) {

    /** 애플리케이션 계층의 페이지를 응답 DTO 페이지로 옮긴다. */
    public static <S, T> CursorPageResponse<T> from(CursorPageResult<S> page,
                                                    Function<S, T> mapper) {
        return new CursorPageResponse<>(
                page.items().stream().map(mapper).toList(),
                page.hasNext(),
                page.nextCursor());
    }
}
