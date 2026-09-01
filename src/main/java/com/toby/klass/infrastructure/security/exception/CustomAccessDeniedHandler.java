package com.toby.klass.infrastructure.security.exception;

import com.toby.klass.common.adapter.in.web.dto.ApiResponse;
import com.toby.klass.common.adapter.in.web.dto.ErrorResponse;
import com.toby.klass.common.domain.error.CommonError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 권한 부족(403) 응답을 만든다.
 *
 * <p><b>현재 이 핸들러가 호출되는 경로는 없다.</b> 인증된 사용자는 모든 엔드포인트에
 * 접근할 수 있고 메서드 보안({@code @PreAuthorize})은 이 예제의 범위 밖이다.
 * 그럼에도 등록해 두는 것은, 나중에 권한 검사를 추가했을 때 <b>응답 형식이 조용히
 * 달라지는 것</b>을 막기 위해서다. Security 기본 403 응답은 본문 형식이 우리 것과 다르다.
 *
 * <p>Design Ref: §6.1 ACCESS_DENIED 주석, §6.3 예외 처리 경로가 둘인 점
 */
@Component
public class CustomAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    /**
     * Jackson 3 ObjectMapper 를 주입받는다.
     *
     * @param objectMapper Jackson 3 매퍼
     */
    public CustomAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {

        response.setStatus(CommonError.ACCESS_DENIED.httpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(),
                ApiResponse.fail(ErrorResponse.from(CommonError.ACCESS_DENIED)));
    }
}
