package com.toby.klass.common.adapter.in.web.dto;

/**
 * 모든 HTTP 응답을 감싸는 공통 봉투(envelope).
 *
 * <p>성공·실패가 같은 최상위 구조를 갖게 해서 클라이언트가 분기 없이
 * 파싱할 수 있게 한다.
 *
 * <pre>{@code
 * // 성공
 * { "success": true,  "data": { ... }, "error": null }
 * // 실패
 * { "success": false, "data": null,    "error": { "code": "...", "message": "...", "details": {} } }
 * }</pre>
 *
 * <p>{@code 204 No Content} 처럼 본문이 없는 응답에는 사용하지 않는다.
 *
 * <p>Design Ref: project-setup §4.1 엔드포인트 목록 — 모든 응답은 공통
 * ApiResponse&lt;T&gt; 로 감싼다
 *
 * @param success 성공 여부. {@code error} 의 존재 여부와 항상 반대다
 * @param data    성공 데이터. 실패 시 {@code null}
 * @param error   실패 정보. 성공 시 {@code null}
 */
public record ApiResponse<T>(boolean success, T data, ErrorResponse error) {

    /**
     * 성공 응답을 만든다.
     */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null);
    }

    /**
     * 실패 응답을 만든다.
     */
    public static ApiResponse<Void> fail(ErrorResponse error) {
        return new ApiResponse<>(false, null, error);
    }
}
