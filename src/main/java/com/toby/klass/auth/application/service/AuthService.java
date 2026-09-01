package com.toby.klass.auth.application.service;

import com.toby.klass.auth.application.dto.LoginCommand;
import com.toby.klass.auth.application.dto.LogoutCommand;
import com.toby.klass.auth.application.dto.ReissueCommand;
import com.toby.klass.auth.application.dto.TokenResult;
import com.toby.klass.auth.application.port.in.LoginUseCase;
import com.toby.klass.auth.application.port.in.LogoutUseCase;
import com.toby.klass.auth.application.port.in.ReissueTokenUseCase;
import com.toby.klass.auth.application.port.out.CredentialsVerifierPort;
import com.toby.klass.auth.application.port.out.RefreshTokenCommandPort;
import com.toby.klass.auth.application.port.out.RefreshTokenQueryPort;
import com.toby.klass.auth.application.port.out.RevokedAccessTokenCommandPort;
import com.toby.klass.auth.application.port.out.TokenGeneratorPort;
import com.toby.klass.auth.application.port.out.TokenHasherPort;
import com.toby.klass.auth.application.port.out.TokenParserPort;
import com.toby.klass.auth.application.port.out.dto.GeneratedToken;
import com.toby.klass.auth.application.port.out.dto.VerifiedCredentials;
import com.toby.klass.auth.domain.RefreshToken;
import com.toby.klass.auth.domain.RevokedAccessToken;
import com.toby.klass.auth.domain.TokenType;
import com.toby.klass.auth.domain.error.AuthError;
import com.toby.klass.common.domain.error.BusinessException;
import com.toby.klass.user.application.port.out.UserQueryPort;
import com.toby.klass.user.domain.User;
import com.toby.klass.user.domain.error.UserError;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 인증 유즈케이스 구현. 로그인·재발급·로그아웃을 담당한다.
 *
 * <h2>이 클래스가 하지 않는 일</h2>
 * 비즈니스 규칙을 직접 판단하지 않는다. 회전 가능 여부는 {@link RefreshToken#rotate},
 * 계정 활성 여부는 {@link User#verifyEnabled} 가 정한다. 이 클래스는 <b>순서를 조율</b>하고
 * 포트를 호출할 뿐이다. 여기에 {@code if (token.isRevoked())} 같은 코드가 생기면 규칙이
 * 도메인 밖으로 샌 신호다.
 *
 * <h2>JWT 라이브러리를 모른다</h2>
 * {@code com.nimbusds}·{@code org.springframework.security} 를 import 하지 않는다.
 * 포트 뒤에 무엇이 있든 이 코드는 그대로다 — 헥사고날의 실익이 드러나는 지점이다.
 *
 * <p>Design Ref: §2.2 Data Flow, §2.0 가드레일
 */
@Service
@Transactional
public class AuthService implements LoginUseCase, ReissueTokenUseCase, LogoutUseCase {

    private final UserQueryPort userQueryPort;
    private final CredentialsVerifierPort credentialsVerifierPort;
    private final TokenGeneratorPort tokenGeneratorPort;
    private final TokenParserPort tokenParserPort;
    private final TokenHasherPort tokenHasherPort;
    private final RefreshTokenQueryPort refreshTokenQueryPort;
    private final RefreshTokenCommandPort refreshTokenCommandPort;
    private final RevokedAccessTokenCommandPort revokedAccessTokenCommandPort;
    private final RefreshTokenBreachHandler breachHandler;
    private final Clock clock;

    /**
     * 인증에 필요한 포트들을 주입받는다. 전부 인터페이스이므로 구현을 교체해도 이 클래스는 그대로다.
     *
     * @param userQueryPort 사용자 조회 포트
     * @param credentialsVerifierPort 자격 증명 검증 포트
     * @param tokenGeneratorPort 토큰 발급 포트
     * @param tokenParserPort 토큰 검증·파싱 포트
     * @param tokenHasherPort 토큰 해싱 포트
     * @param refreshTokenQueryPort Refresh 토큰 조회 포트
     * @param refreshTokenCommandPort Refresh 토큰 변경 포트
     * @param revokedAccessTokenCommandPort Access 토큰 폐기 목록 변경 포트
     * @param breachHandler 재사용 감지 시 침해 대응 (REQUIRES_NEW)
     * @param clock 주입된 시계. 시간 의존 로직을 테스트 가능하게 한다
     */
    public AuthService(UserQueryPort userQueryPort,
                       CredentialsVerifierPort credentialsVerifierPort,
                       TokenGeneratorPort tokenGeneratorPort,
                       TokenParserPort tokenParserPort,
                       TokenHasherPort tokenHasherPort,
                       RefreshTokenQueryPort refreshTokenQueryPort,
                       RefreshTokenCommandPort refreshTokenCommandPort,
                       RevokedAccessTokenCommandPort revokedAccessTokenCommandPort,
                       RefreshTokenBreachHandler breachHandler,
                       Clock clock) {
        this.userQueryPort = userQueryPort;
        this.credentialsVerifierPort = credentialsVerifierPort;
        this.tokenGeneratorPort = tokenGeneratorPort;
        this.tokenParserPort = tokenParserPort;
        this.tokenHasherPort = tokenHasherPort;
        this.refreshTokenQueryPort = refreshTokenQueryPort;
        this.refreshTokenCommandPort = refreshTokenCommandPort;
        this.revokedAccessTokenCommandPort = revokedAccessTokenCommandPort;
        this.breachHandler = breachHandler;
        this.clock = clock;
    }

    /**
     * {@inheritDoc}
     *
     * <p>자격 증명 검증을 직접 하지 않고 {@link CredentialsVerifierPort} 에 맡긴다.
     * 그 뒤에는 Spring Security 의 표준 인증 파이프라인
     * ({@code AuthenticationManager} → {@code DaoAuthenticationProvider})이 있지만,
     * 이 클래스는 그 사실을 모른다.
     *
     * <p><b>검증 순서는 어댑터가 보장한다.</b> 아이디 존재 여부와 비밀번호 불일치는 모두
     * {@code INVALID_CREDENTIALS} 로 통일되고, 계정 활성 검사는 비밀번호 검증 뒤에 일어난다.
     * 순서를 바꾸면 비밀번호를 몰라도 계정의 존재·상태를 알아낼 수 있다.
     */
    @Override
    public TokenResult login(LoginCommand command) {
        VerifiedCredentials verified =
                credentialsVerifierPort.verify(command.username(), command.password());

        return issueTokenPair(verified.userId(), verified.username(), verified.roles());
    }

    /**
     * {@inheritDoc}
     *
     * <p>회전(RTR)의 전체 흐름이다. 파싱 → 조회 → 회전 → 재발급 순서이며,
     * 회전 단계에서 재사용이 감지되면 침해 대응 후 예외를 그대로 올린다.
     */
    @Override
    public TokenResult reissue(ReissueCommand command) {
        // 서명·만료·타입을 한 번에 검증한다. REFRESH 를 기대 타입으로 넘기므로
        // Access 토큰으로는 재발급할 수 없다.
        tokenParserPort.parse(command.refreshToken(), TokenType.REFRESH);

        String tokenHash = tokenHasherPort.sha256Hex(command.refreshToken());
        RefreshToken stored = refreshTokenQueryPort.findByTokenHash(tokenHash)
                .orElseThrow(AuthError.REFRESH_TOKEN_NOT_FOUND::toException);

        rotateOrHandleBreach(stored);

        // 토큰 클레임이 아니라 DB 에서 사용자를 다시 읽는다. 권한 변경과 계정 비활성화가
        // 재발급 시점에 즉시 반영되도록 하기 위함이다.
        User user = userQueryPort.findById(stored.getUserId())
                .orElseThrow(UserError.USER_NOT_FOUND::toException);
        user.verifyEnabled();

        return issueTokenPair(user.getId(), user.getUsername(), user.roleNames());
    }

    /**
     * JWT 경계의 {@link Instant} 를 도메인의 {@link LocalDateTime} 으로 옮긴다.
     *
     * <p>JWT 의 {@code iat}/{@code exp} 는 epoch 초라 절대 시각({@code Instant})이어야 하고,
     * 도메인 엔티티는 사람이 읽기 쉬운 벽시계 시각({@code LocalDateTime})을 쓴다.
     * 두 표현이 <b>같은 시점</b>을 가리키도록 주입된 {@code Clock} 의 시간대로 변환한다.
     *
     * <p>여기서 시간대가 어긋나면 JWT 는 유효한데 DB 기준으로는 만료된(또는 그 반대) 상태가
     * 되어 재발급이 이상하게 동작한다. 변환 지점을 이 메서드 하나로 모아 둔 이유다.
     *
     * @param instant 토큰 생성 포트가 돌려준 절대 시각
     * @return 같은 시점을 가리키는 벽시계 시각
     */
    private LocalDateTime toLocalDateTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, clock.getZone());
    }

    /**
     * 토큰을 회전시키되, 재사용이 감지되면 침해 대응을 먼저 수행한다.
     *
     * <p>대응을 {@link RefreshTokenBreachHandler} 에 위임하는 이유는 트랜잭션 때문이다.
     * 여기서 직접 무효화하면 아래 {@code throw} 로 트랜잭션이 롤백되면서 무효화까지
     * 사라진다. 자세한 근거는 그 클래스의 문서를 참조.
     *
     * @param stored 조회된 토큰 기록
     */
    private void rotateOrHandleBreach(RefreshToken stored) {
        try {
            stored.rotate(LocalDateTime.now(clock));
        } catch (BusinessException e) {
            if (e.errorCode() == AuthError.REFRESH_TOKEN_REUSED) {
                breachHandler.revokeAllOf(stored.getUserId());
            }
            throw e;
        }
    }

    /**
     * {@inheritDoc}
     *
     * <h4>두 가지를 함께 해야 로그아웃이 성립한다</h4>
     * <ol>
     *   <li><b>Refresh 토큰 삭제</b> — 갱신을 막는다. 이것만 하면 이미 발급된 Access
     *       토큰이 남은 유효 기간(기본 30분) 동안 계속 통과한다</li>
     *   <li><b>Access 토큰 폐기 등록</b> — 즉시 차단한다.
     *       {@code AccessTokenVerificationService} 가 매 요청 이 목록을 대조한다</li>
     * </ol>
     * 순서는 중요하지 않다. 같은 트랜잭션이므로 둘 다 커밋되거나 둘 다 롤백된다 —
     * "갱신은 막혔는데 Access 는 살아 있는" 어중간한 상태가 남지 않는다.
     *
     * <p>Refresh 삭제 건수는 확인하지 않는다. 0 건이어도 정상으로 취급해야 멱등하고,
     * 토큰의 존재 여부를 응답으로 흘리지 않는다. Access 폐기 등록도 마찬가지로
     * 이미 등록된 {@code jti} 면 조용히 넘어간다(포트 계약).
     */
    @Override
    public void logout(LogoutCommand command) {
        String tokenHash = tokenHasherPort.sha256Hex(command.refreshToken());
        refreshTokenCommandPort.deleteByTokenHashAndUserId(tokenHash, command.userId());

        revokedAccessTokenCommandPort.revoke(RevokedAccessToken.revoke(
                command.accessTokenId(),
                command.userId(),
                toLocalDateTime(command.accessTokenExpiresAt()),
                LocalDateTime.now(clock)));
    }

    /**
     * Access/Refresh 토큰 쌍을 발급하고 Refresh 기록을 저장한다.
     *
     * <p>만료 시각은 생성 포트가 돌려준 값을 <b>그대로</b> 쓴다. 여기서 다시 계산하면
     * JWT 의 {@code exp} 와 DB 의 {@code expires_at} 이 어긋날 수 있다.
     *
     * <p>도메인 엔티티가 아니라 값 세 개를 받는다. 로그인은 인증 어댑터가 돌려준
     * {@code VerifiedCredentials} 에서, 재발급은 DB 에서 읽은 {@code User} 에서 오기 때문이다.
     *
     * @param userId   사용자 PK
     * @param username 로그인 아이디
     * @param roles    권한 이름 목록
     * @return 발급 결과
     */
    private TokenResult issueTokenPair(Long userId, String username, List<String> roles) {
        GeneratedToken access = tokenGeneratorPort.generateAccessToken(userId, username, roles);
        GeneratedToken refresh = tokenGeneratorPort.generateRefreshToken(userId);

        LocalDateTime accessExpiresAt = toLocalDateTime(access.expiresAt());
        LocalDateTime refreshExpiresAt = toLocalDateTime(refresh.expiresAt());

        // 토큰 원문이 아니라 해시를 저장한다. DB 가 유출돼도 그 값으로는 API 를 부를 수 없다.
        String tokenHash = tokenHasherPort.sha256Hex(refresh.value());
        refreshTokenCommandPort.save(RefreshToken.issue(
                userId, tokenHash, toLocalDateTime(refresh.issuedAt()), refreshExpiresAt));

        // 응답의 만료 일시와 DB 의 expires_at 은 같은 값이다. 따로 계산하지 않는다.
        return new TokenResult(
                TokenResult.BEARER,
                access.value(), access.expiresInSeconds(), accessExpiresAt,
                refresh.value(), refresh.expiresInSeconds(), refreshExpiresAt);
    }
}
