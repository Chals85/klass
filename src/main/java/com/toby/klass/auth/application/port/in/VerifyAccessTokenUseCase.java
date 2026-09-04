package com.toby.klass.auth.application.port.in;

import com.toby.klass.auth.application.port.out.dto.TokenClaims;

/**
 * Access 토큰 검증 유즈케이스. 보호된 자원 접근의 관문이다.
 *
 * <h2>왜 필터가 {@code TokenParserPort} 를 직접 부르지 않는가</h2>
 * Access 토큰이 유효하다는 판정은 이제 두 단계다 — <b>서명·만료·타입 검증</b>(파싱)과
 * <b>폐기 여부 대조</b>(저장소 조회). 이 둘을 어떤 순서로 조합해야 하는지는
 * 비즈니스 규칙이므로 애플리케이션 계층에 있어야 한다.
 *
 * <p>필터가 {@code parse()} 를 부르고 그 뒤에 {@code if (isRevoked) throw} 를 직접
 * 쓰면, 인증 규칙의 절반이 infrastructure 로 새어나간다. 그 상태에서 검증 단계가
 * 하나 더 늘면(예: 기기 바인딩 확인) 필터가 계속 두꺼워진다.
 *
 * <p>Design Ref: §2.2 인증된 요청 흐름, §2.0 아키텍처 비교
 */
public interface VerifyAccessTokenUseCase {

    /**
     * Access 토큰을 검증하고 클레임을 돌려준다.
     *
     * <p>이 메서드가 값을 돌려줬다면 <b>서명·만료·타입·폐기 여부를 모두 통과</b>한
     * 토큰이다. 호출자가 추가로 검사할 것은 없다.
     *
     * @param accessToken {@code Bearer } 접두어를 제거한 토큰 원문
     * @return 검증된 클레임
     * @throws com.toby.klass.common.domain.error.BusinessException
     *         {@code TOKEN_INVALID} 서명·형식 오류 /
     *         {@code TOKEN_EXPIRED} 만료 /
     *         {@code TOKEN_TYPE_MISMATCH} Refresh 토큰을 제시한 경우 /
     *         {@code TOKEN_REVOKED} 로그아웃된 토큰인 경우
     */
    TokenClaims verify(String accessToken);
}
