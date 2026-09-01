package com.toby.klass.common.adapter.in.web.advice;

import com.toby.klass.common.adapter.in.web.dto.ApiResponse;
import com.toby.klass.common.adapter.in.web.dto.ErrorResponse;
import com.toby.klass.common.domain.error.BusinessException;
import com.toby.klass.common.domain.error.CommonError;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 컨트롤러·서비스·도메인에서 발생한 예외를 공통 응답 형식으로 변환한다.
 *
 * <p><b>이 처리기가 잡지 못하는 예외가 있다.</b> Spring Security 필터에서 발생한
 * 인증 실패는 {@code DispatcherServlet} 앞단이라 여기까지 오지 않는다. 그쪽은
 * {@code CustomAuthenticationEntryPoint} 가 담당하며, <b>동일한 {@link ErrorResponse}
 * 형식</b>을 직접 write 해야 한다. 두 경로가 다른 형식을 뱉으면 클라이언트가 401 의
 * 출처에 따라 다른 파싱을 해야 하기 때문이다.
 *
 * <p>Design Ref: §6.3 — 예외 처리 경로가 둘인 점에 대한 주의
 */
@RestControllerAdvice
public class GlobalExceptionControllerAdvice {

    /** 상태가 없으므로 주입받을 의존성이 없다. 직접 호출하지 않는다. */
    public GlobalExceptionControllerAdvice() {
    }

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionControllerAdvice.class);

    /**
     * 업무 규칙 위반. 에러 코드가 HTTP 상태까지 들고 있으므로 코드별 분기가 필요 없다.
     *
     * <p>4xx 는 호출자의 문제이므로 스택트레이스 없이 한 줄만 남기고, 5xx 는
     * {@link #handleUnexpected} 로 넘어가지 않으므로 여기서 스택트레이스를 남긴다.
     *
     * @param e 도메인 또는 애플리케이션 계층이 던진 예외
     * @return 에러 코드가 지정한 HTTP 상태와 공통 응답 본문
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
        int status = e.errorCode().httpStatus();
        if (status >= 500) {
            log.error("업무 예외(5xx): {}", e.errorCode().name(), e);
        } else {
            log.debug("업무 예외: {} - {}", e.errorCode().name(), e.getMessage());
        }
        return ResponseEntity.status(HttpStatus.valueOf(status))
                .body(ApiResponse.fail(ErrorResponse.from(e.errorCode(), e.details())));
    }

    /**
     * {@code @Valid} 검증 실패. 필드별 메시지를 {@code details} 에 담아 돌려준다.
     *
     * <p>같은 필드에 검증 애너테이션이 여러 개 붙어 여러 번 실패할 수 있는데,
     * {@code merge} 로 <b>첫 번째 메시지만</b> 남긴다. 사용자에게 한 필드당 하나씩
     * 보여주는 편이 낫고, 검증 순서는 보장되지 않으므로 개수를 늘려봐야 도움이 안 된다.
     *
     * @param e 바인딩 결과를 담은 예외
     * @return 400 과 필드별 메시지
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError fieldError : e.getBindingResult().getFieldErrors()) {
            fieldErrors.merge(
                    fieldError.getField(),
                    fieldError.getDefaultMessage() == null ? "" : fieldError.getDefaultMessage(),
                    (first, ignored) -> first);
        }
        log.debug("입력 검증 실패: {}", fieldErrors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail(ErrorResponse.from(CommonError.VALIDATION_ERROR, fieldErrors)));
    }

    /**
     * 존재하지 않는 경로.
     *
     * <p><b>이 핸들러가 없으면 404 가 500 이 된다.</b> Spring MVC 는 매핑되지 않은 요청을
     * 정적 리소스 조회로 넘기고, 그것도 없으면 {@link NoResourceFoundException} 을 던진다.
     * 그런데 아래 {@link #handleUnexpected} 가 {@code Exception} 을 전부 잡으므로,
     * 명시적으로 먼저 가로채지 않으면 "서버 오류"로 둔갑한다.
     *
     * @param e 리소스를 찾지 못한 예외
     * @return 404 와 공통 응답 본문
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(NoResourceFoundException e) {
        log.debug("존재하지 않는 경로: {}", e.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.fail(ErrorResponse.from(CommonError.NOT_FOUND)));
    }

    /**
     * 경로는 맞지만 HTTP 메서드가 다르다.
     *
     * @param e 메서드 불일치 예외
     * @return 405 와 공통 응답 본문
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e) {
        log.debug("지원하지 않는 메서드: {}", e.getMethod());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResponse.fail(ErrorResponse.from(CommonError.METHOD_NOT_ALLOWED)));
    }

    /**
     * 요청 본문을 파싱할 수 없다. 깨진 JSON, 빈 본문, 타입 불일치 등.
     *
     * <p>{@link #handleValidation} 과 구분한다. 그쪽은 파싱에 성공한 뒤 값이 규칙을 어긴
     * 경우라 필드별 상세를 줄 수 있지만, 여기는 객체를 만들지도 못한 상태다.
     *
     * <p>예외 메시지에 파싱 실패 위치와 원본 일부가 담기므로 <b>응답에 노출하지 않는다</b>.
     *
     * @param e 본문 파싱 실패 예외
     * @return 400 과 공통 응답 본문
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleMalformedRequest(HttpMessageNotReadableException e) {
        log.debug("요청 본문 파싱 실패: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail(ErrorResponse.from(CommonError.MALFORMED_REQUEST)));
    }

    /**
     * 처리하지 못한 모든 예외의 최종 안전망.
     *
     * <p>원인은 로그에만 남기고 응답에는 노출하지 않는다. 예외 메시지에 SQL 이나
     * 내부 경로가 섞여 나가면 공격자에게 정보를 주게 된다.
     *
     * <p><b>주의</b>: 이 핸들러가 {@code Exception} 을 전부 잡으므로, 고유한 상태코드를
     * 가져야 하는 예외는 위쪽에 <b>명시적 핸들러를 먼저 두어야 한다</b>. 그러지 않으면
     * 404·405 같은 응답이 모두 500 으로 뭉개진다.
     *
     * @param e 예상하지 못한 예외
     * @return 500 과 일반화된 메시지
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
        log.error("처리되지 않은 예외", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(ErrorResponse.from(CommonError.INTERNAL_ERROR)));
    }
}
