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
 * <p><b>실제로 호출된다.</b> {@code SecurityConfig} 가 {@code hasRole("CREATOR")} 매처로
 * 강의 등록·수정·상태 전이·수강생 목록을 막으므로, {@code ROLE_USER} 만 가진 사용자가
 * 그 경로에 오면 여기로 온다. {@code KlassFlowIntegrationTest} 가 403 을 단언한다.
 *
 * <p>CLAUDE.md 는 {@code @PreAuthorize} 대신 <b>{@code SecurityConfig} 요청 매처로만</b>
 * 인가하도록 규정한다 — 메서드 보안이 없다는 사실이 "권한 검사가 없다"를 뜻하지 않는다.
 *
 * <p>Security 기본 403 응답은 본문 형식이 우리 것과 다르므로, 이 핸들러가 없으면
 * 인증 실패(401)와 권한 부족(403)의 응답 형식이 갈라진다.
 *
 * <p>Design Ref: project-setup §6.1 ACCESS_DENIED 주석, §6.2 응답 형식
 */
@Component
public class CustomAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    /**
     * Jackson 3 ObjectMapper 를 주입받는다.
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
