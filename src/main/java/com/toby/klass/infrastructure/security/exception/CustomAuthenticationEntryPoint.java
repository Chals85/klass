package com.toby.klass.infrastructure.security.exception;

import com.toby.klass.auth.domain.error.AuthError;
import com.toby.klass.common.adapter.in.web.dto.ApiResponse;
import com.toby.klass.common.adapter.in.web.dto.ErrorResponse;
import com.toby.klass.common.domain.error.ErrorCode;
import com.toby.klass.infrastructure.security.filter.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 인증 실패 응답을 만든다.
 *
 * <h2>왜 이 클래스가 필요한가</h2>
 * Security 필터에서 발생한 실패는 {@code DispatcherServlet} 앞단이라
 * {@code @RestControllerAdvice} 에 도달하지 않는다. 그대로 두면 Security 기본 응답이
 * 나가면서 <b>같은 401 인데도 본문 형식이 두 가지</b>가 된다. 클라이언트가 401 의 출처에
 * 따라 다른 파싱을 하게 만들지 않으려면 여기서 동일한 {@link ErrorResponse} 를 써야 한다.
 *
 * <h2>Jackson 3 를 쓴다</h2>
 * {@code tools.jackson.databind.ObjectMapper} 다. Spring Boot 4 의 기본 직렬화가
 * Jackson 3 이므로 컨트롤러 응답과 형식이 일치한다. 클래스패스에는 springdoc 이
 * 끌어온 Jackson 2({@code com.fasterxml.jackson})도 있지만 그쪽을 import 하면
 * 설정(날짜 표기 등)이 달라져 응답이 미묘하게 어긋난다.
 *
 * <p>Design Ref: §6.3 예외 처리 경로가 둘인 점, §13.5 Jackson 2/3 공존
 */
@Component
public class CustomAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    /**
     * Jackson 3 ObjectMapper 를 주입받는다. 컨트롤러 응답과 같은 직렬화 설정을 쓰기 위함이다.
     *
     * @param objectMapper Jackson 3 매퍼
     */
    public CustomAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 인증되지 않은 요청에 대한 응답을 직접 기록한다.
     *
     * <p>{@link JwtAuthenticationFilter} 가 남긴 에러 코드가 있으면 그것을 쓰고,
     * 없으면 토큰 자체가 제공되지 않은 경우이므로 {@link AuthError#UNAUTHENTICATED} 로 답한다.
     *
     * @param request       실패한 요청. 필터가 심어 둔 에러 코드를 여기서 읽는다
     * @param response      응답
     * @param authException Security 가 만든 예외. 우리 에러 코드 체계와 무관해 쓰지 않는다
     */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {

        ErrorCode errorCode = resolveErrorCode(request);

        response.setStatus(errorCode.httpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), ApiResponse.fail(ErrorResponse.from(errorCode)));
    }

    /**
     * 필터가 남긴 실패 원인을 꺼낸다.
     *
     * @return 필터가 심어 둔 에러 코드. 없으면 {@link AuthError#UNAUTHENTICATED}
     */
    private ErrorCode resolveErrorCode(HttpServletRequest request) {
        Object attribute = request.getAttribute(JwtAuthenticationFilter.AUTH_ERROR_ATTRIBUTE);
        if (attribute instanceof ErrorCode errorCode) {
            return errorCode;
        }
        return AuthError.UNAUTHENTICATED;
    }
}
