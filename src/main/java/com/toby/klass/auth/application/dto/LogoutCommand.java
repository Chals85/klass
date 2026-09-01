package com.toby.klass.auth.application.dto;

import java.time.Instant;

/**
 * 로그아웃 요청.
 *
 * <p>{@code userId} 가 함께 필요한 이유는 <b>소유자 확인</b> 때문이다. 토큰 해시만으로
 * 삭제하면 남의 Refresh 토큰을 알아냈을 때 그것을 지울 수 있다.
 *
 * <h2>왜 Access 토큰 정보까지 받는가</h2>
 * 로그아웃은 두 가지를 해야 한다 — Refresh 토큰을 지워 <b>갱신을 막고</b>, 현재 Access
 * 토큰을 폐기 목록에 올려 <b>즉시 차단</b>한다. 앞의 것만 하면 이미 발급된 Access
 * 토큰이 남은 유효 기간 동안 계속 통과한다.
 *
 * <p>토큰 <b>원문</b>이 아니라 {@code jti} 를 받는다. 필터가 이미 파싱해 principal 에
 * 실어둔 값이므로 다시 파싱할 필요가 없고, 원문을 계층 사이로 흘리지 않아도 된다.
 *
 * @param userId               인증된 사용자 id. 컨트롤러가 principal 에서 꺼내 채운다
 * @param refreshToken         폐기할 Refresh 토큰 원문
 * @param accessTokenId        현재 Access 토큰의 {@code jti}. principal 에서 온다
 * @param accessTokenExpiresAt 현재 Access 토큰의 만료 시각. 폐기 기록을 언제 지워도 되는지
 *                             판단하는 기준이 된다
 *
 * <p>Design Ref: §2.2 로그아웃 흐름
 */
public record LogoutCommand(Long userId, String refreshToken,
                            String accessTokenId, Instant accessTokenExpiresAt) {
}
