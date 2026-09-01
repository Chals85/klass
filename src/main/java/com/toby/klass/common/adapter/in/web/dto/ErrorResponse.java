package com.toby.klass.common.adapter.in.web.dto;

import com.toby.klass.common.domain.error.ErrorCode;
import java.util.Map;

/**
 * 실패 응답의 {@code error} 필드에 실리는 본문.
 *
 * <p>이 형식은 <b>두 경로에서 동일하게</b> 생성돼야 한다.
 * <ul>
 *   <li>컨트롤러·서비스·도메인에서 던진 예외 → {@code GlobalExceptionControllerAdvice}</li>
 *   <li>Security 필터에서 발생한 인증 실패 → {@code CustomAuthenticationEntryPoint}</li>
 * </ul>
 * 필터는 {@code DispatcherServlet} 앞단이라 {@code @RestControllerAdvice} 에
 * 도달하지 않는다. 두 경로가 다른 형식을 뱉으면 클라이언트가 401 의 출처에 따라
 * 다른 파싱을 해야 하므로, 양쪽 모두 이 record 를 써야 한다.
 *
 * @param code    에러 코드. {@link ErrorCode#name()} 값이다 (예: {@code "VALIDATION_ERROR"})
 * @param message 사용자에게 보여줄 한국어 메시지
 * @param details 필드 단위 상세 정보. 검증 실패가 아니면 빈 맵이다
 *
 * <p>Design Ref: §6.2, §6.3 — 예외 처리 경로가 둘인 점에 대한 주의
 */
public record ErrorResponse(String code, String message, Map<String, String> details) {

    /**
     * 에러 코드만으로 응답 본문을 만든다.
     *
     * @param errorCode 변환할 에러 코드
     * @return {@code details} 가 빈 맵인 응답 본문
     */
    public static ErrorResponse from(ErrorCode errorCode) {
        return new ErrorResponse(errorCode.name(), errorCode.message(), Map.of());
    }

    /**
     * 필드 단위 상세 정보를 포함해 응답 본문을 만든다.
     *
     * @param errorCode 변환할 에러 코드
     * @param details   필드명 → 검증 실패 메시지
     * @return 응답 본문
     */
    public static ErrorResponse from(ErrorCode errorCode, Map<String, String> details) {
        return new ErrorResponse(errorCode.name(), errorCode.message(), Map.copyOf(details));
    }
}
