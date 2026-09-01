package com.toby.klass.common.domain.error;

import java.util.Map;

/**
 * 모든 에러 코드가 구현하는 계약.
 *
 * <p>컨텍스트별 {@code {Feature}Error} enum 이 이 인터페이스를 구현한다
 * ({@link CommonError}, {@code AuthError}, {@code UserError}).
 * enum 상수 하나가 HTTP 상태와 메시지를 함께 들고 있어, 예외 처리기가
 * 코드별 분기 없이 단일 경로로 응답을 만들 수 있다.
 *
 * <h2>왜 코드 문자열을 따로 두지 않는가</h2>
 * {@code "AUTH-001"} 같은 별도 문자열을 필드로 들면 enum 상수명과 항상
 * 동기화해야 하는 이중 관리가 생기고, 번호 체계를 유지하는 부담까지 붙는다.
 * 대신 {@link #name()} 을 그대로 API 응답의 {@code error.code} 로 쓴다.
 * 상수명이 곧 코드이므로 관리 지점이 하나이고, 응답이 자기 설명적이다
 * ({@code "REFRESH_TOKEN_REUSED"} 가 {@code "AUTH-005"} 보다 읽힌다).
 *
 * <p><b>트레이드오프</b>: enum 상수명을 리팩터링하면 API 응답 코드가 바뀐다.
 * 공개 API 라면 문자열 코드를 분리해 계약을 고정하는 편이 낫지만, 이 프로젝트는
 * 학습용 예제이고 외부 클라이언트가 없으므로 단순함을 택했다.
 *
 * <h2>왜 Spring 타입을 쓰지 않는가</h2>
 * 이 인터페이스는 도메인 레이어에 있고 도메인 엔티티가 직접 참조한다.
 * {@code httpStatus} 를 {@code org.springframework.http.HttpStatus} 로 두면
 * 도메인이 Spring 에 의존하게 되므로 {@code int} 로 받는다.
 * {@code HttpStatus} 변환은 어댑터 계층의 예외 처리기가 담당한다.
 *
 * <p>Design Ref: §6.1 — Error Code Definition
 */
public interface ErrorCode {

    /**
     * enum 상수명. Enum 이 이미 구현하고 있으므로 별도 구현이 필요 없다.
     *
     * @return API 응답의 {@code error.code} 로 나가는 값 (예: {@code "VALIDATION_ERROR"})
     */
    String name();

    /**
     * 사용자에게 노출되는 한국어 메시지.
     *
     * <p>내부 구현이나 실패 원인을 드러내지 않는다. 예를 들어 로그인 실패는
     * "사용자 없음"과 "비밀번호 불일치"를 구분하지 않는데, 구분하면 사용자
     * 열거(enumeration) 공격에 노출되기 때문이다.
     */
    String message();

    /**
     * HTTP 상태 코드.
     *
     * @return 200~599 범위의 상태 코드. {@code HttpStatus} 가 아니라 {@code int} 인
     *         이유는 클래스 주석의 "왜 Spring 타입을 쓰지 않는가" 참조
     */
    int httpStatus();

    /**
     * 이 에러 코드를 담은 예외를 만든다. 필드 단위 상세 정보는 없다.
     */
    default BusinessException toException() {
        return new BusinessException(this, Map.of());
    }

    /**
     * 필드 단위 상세 정보를 함께 담은 예외를 만든다.
     *
     * @param details 필드명 → 메시지. 주로 입력 검증 실패에서 사용한다
     */
    default BusinessException toException(Map<String, String> details) {
        return new BusinessException(this, details);
    }
}
