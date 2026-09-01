package com.toby.klass.common.domain.error;

import java.io.Serial;
import java.util.Map;

/**
 * 도메인·애플리케이션 계층이 던지는 업무 예외.
 *
 * <p>{@link ErrorCode} 를 감싸고 있어, 예외 처리기는 이 타입 하나만 잡으면
 * HTTP 상태·코드·메시지를 모두 얻을 수 있다. 컨텍스트별로 예외 클래스를
 * 새로 만들지 않고 enum 상수로 구분하는 이유가 이것이다.
 *
 * <p>{@link RuntimeException} 을 상속해 검사 예외(checked exception)로 만들지
 * 않았다. 업무 규칙 위반은 호출자가 대부분 복구할 수 없고, 시그니처마다
 * {@code throws} 를 달면 포트 인터페이스가 지저분해지기 때문이다.
 *
 * <p>Design Ref: §6.1 — Error Code Definition
 */
public class BusinessException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 직렬화 대상이 아니므로 transient. 예외는 프로세스 밖으로 나가지 않는다. */
    private final transient ErrorCode errorCode;

    /** 필드명 → 검증 실패 메시지. 상세 정보가 없으면 빈 맵이다. */
    private final transient Map<String, String> details;

    /**
     * 에러 코드와 상세 정보를 담은 예외를 만든다.
     *
     * <p>직접 호출하기보다 {@code ErrorCode.toException()} 을 쓰는 편이 읽기 쉽다.
     *
     * @param errorCode 이 예외가 나타내는 에러 코드
     * @param details   필드 단위 상세 정보. 방어적 복사되어 불변이 된다
     */
    public BusinessException(ErrorCode errorCode, Map<String, String> details) {
        super(errorCode.message());
        this.errorCode = errorCode;
        this.details = Map.copyOf(details);
    }

    /**
     * 예외 처리기가 HTTP 상태·코드·메시지를 얻는 통로다.
     *
     * @return 이 예외가 감싸고 있는 에러 코드
     */
    public ErrorCode errorCode() {
        return errorCode;
    }

    /**
     * 주로 입력 검증 실패에서 채워진다.
     *
     * @return 필드 단위 상세 정보 (불변, 없으면 빈 맵)
     */
    public Map<String, String> details() {
        return details;
    }
}
