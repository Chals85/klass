package com.toby.klass.infrastructure.bootstrap;

import com.toby.klass.auth.application.port.out.PasswordHasherPort;
import com.toby.klass.user.adapter.out.persistence.UserJpaRepository;
import com.toby.klass.user.domain.User;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 기동 시 기본 계정을 한 번 심는다.
 *
 * <h2>왜 {@code data.sql} 이 아닌가</h2>
 * 비밀번호가 BCrypt 해시라 SQL 에 넣으려면 해시 문자열을 미리 만들어 하드코딩해야 한다.
 * 그러면 설정에서 비밀번호를 바꿔도 반영되지 않고, 읽는 사람도 그 문자열이 무엇인지 알 수 없다.
 * {@link ApplicationRunner} 에서 {@link PasswordHasherPort} 로 해싱하면 설정값이 그대로 살아난다.
 *
 * <h2>멱등성</h2>
 * 이미 있으면 건너뛴다. 지금은 {@code ddl-auto: create-drop} 이라 매 기동마다 비어 있지만,
 * 나중에 스키마를 유지하도록 바꿔도 중복 삽입으로 실패하지 않는다.
 *
 * <p>Design Ref: FR-07, §8.5 Seed Data Requirements
 */
@Component
public class DefaultUserInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DefaultUserInitializer.class);

    private final UserJpaRepository userJpaRepository;
    private final PasswordHasherPort passwordHasherPort;
    private final DefaultUserProperties properties;
    private final Clock clock;

    /**
     * 시딩에 필요한 리포지토리·포트·설정·시계를 주입받는다.
     *
     * @param userJpaRepository 사용자 영속 접근
     * @param passwordHasherPort 비밀번호 해싱 포트
     * @param properties 바인딩된 설정값
     * @param clock 주입된 시계. 시간 의존 로직을 테스트 가능하게 한다
     */
    public DefaultUserInitializer(UserJpaRepository userJpaRepository,
                                  PasswordHasherPort passwordHasherPort,
                                  DefaultUserProperties properties,
                                  Clock clock) {
        this.userJpaRepository = userJpaRepository;
        this.passwordHasherPort = passwordHasherPort;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 기본 계정을 생성한다. 이미 존재하면 아무것도 하지 않는다.
     *
     * @param args 기동 인자. 사용하지 않는다
     */
    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        // Design §12 D-6 — 계정이 여럿이므로 순회한다. 하나가 이미 있어도 나머지는 심는다.
        properties.defaultUsers().forEach(this::seed);
    }

    private void seed(DefaultUserProperties.DefaultUser account) {
        if (userJpaRepository.existsByUsername(account.username())) {
            log.info("기본 계정이 이미 존재합니다 — username={}", account.username());
            return;
        }

        User user = User.register(
                account.username(),
                passwordHasherPort.hash(account.password()),
                new LinkedHashSet<>(account.roles()),
                LocalDateTime.now(clock));
        userJpaRepository.save(user);

        // 학습용 예제라 평문 비밀번호를 로그에 남긴다. 실서비스에서는 절대 해서는 안 된다.
        log.info("기본 계정을 생성했습니다 — username={}, password={}, roles={} (예제 전용)",
                account.username(), account.password(), account.roles());
    }
}
