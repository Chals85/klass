package com.toby.klass.enrollment.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * {@code application.yml} 의 수강신청 프로퍼티가 실제로 바인딩되는지 (L2).
 *
 * <h2>왜 기동 테스트로는 부족한가</h2>
 * 중첩 record 는 해당 블록이 yml 에 없으면 <b>예외가 아니라 {@code null}</b> 로 바인딩된다
 * (스파이크 실측, Design §4.1.1 ⑤). 즉 <b>애플리케이션은 정상 기동하고 첫 신청에서 NPE 가
 * 난다.</b> 컨텍스트가 뜨는지만 보는 테스트는 이것을 잡지 못한다.
 *
 * <p>{@link ConfigDataApplicationContextInitializer} 로 <b>실제 {@code application.yml} 을</b>
 * 읽는다 — 테스트에 값을 직접 넣으면 yml 이 비어 있어도 통과해 검증이 무의미해진다.
 *
 * <p>Design Ref: enrollment-management §5, §4.1.1 ⑤
 */
@DisplayName("EnrollmentProperties — application.yml 바인딩")
class EnrollmentPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(EnableProperties.class);

    @Test
    @DisplayName("전역 취소 기간과 출처별 만료 기한이 모두 채워진다")
    void bindsFromApplicationYml() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            EnrollmentProperties props = context.getBean(EnrollmentProperties.class);

            assertThat(props.defaultCancellationPeriodDays())
                    .as("0 이면 모든 강의가 결제 즉시 취소 불가가 된다")
                    .isEqualTo(7);

            assertThat(props.pendingExpiry())
                    .as("이 블록이 yml 에 없으면 null 이 들어오고, 기동은 성공한 채 "
                            + "첫 신청에서 NPE 가 난다")
                    .isNotNull();
            assertThat(props.pendingExpiry().direct()).isEqualTo(Duration.ofMinutes(30));
            assertThat(props.pendingExpiry().waitlist())
                    .as("승격은 뒷 순번을 오래 붙잡지 않도록 짧아야 한다")
                    .isEqualTo(Duration.ofMinutes(10));
        });
    }

    @Test
    @DisplayName("승격 기한이 직접 신청 기한보다 짧다 — ERD 정본 §2 ⑥ 의 의도")
    void waitlistExpiryIsShorter() {
        runner.run(context -> {
            EnrollmentProperties.PendingExpiry expiry =
                    context.getBean(EnrollmentProperties.class).pendingExpiry();

            assertThat(expiry.waitlist())
                    .as("두 값을 뒤집어 넣어도 개별 단언은 통과한다. 관계를 함께 못박는다")
                    .isLessThan(expiry.direct());
        });
    }

    @EnableConfigurationProperties(EnrollmentProperties.class)
    static class EnableProperties {
    }
}
