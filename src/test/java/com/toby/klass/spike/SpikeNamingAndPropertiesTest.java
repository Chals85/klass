package com.toby.klass.spike;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toby.klass.klass.domain.Klass;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.data.repository.query.parser.PartTree;

/**
 * 설계서 §4.2 1번 · §5.2 의 남은 주장 2건을 판정하는 스파이크.
 *
 * <p>{@link SpikeLockTest} 가 "동작한다"를 확인했다면 여기는 <b>"틀린 이름은 정말 깨지는가"</b>와
 * <b>"프로퍼티 바인딩이 되는가"</b>를 확인한다. 전자는 컨텍스트를 띄우지 않고 Spring Data 의
 * 파서를 직접 부른다 — 부트스트랩 실패를 재현하려고 앱을 통째로 띄울 이유가 없다.
 *
 * <p><b>판정용이며 module-2·module-3 완료 시 삭제한다.</b>
 */
@DisplayName("스파이크: 파생 쿼리 이름 규칙과 프로퍼티 바인딩")
class SpikeNamingAndPropertiesTest {

    @Nested
    @DisplayName("④ 파생 쿼리 이름 규칙 — 수식어를 어디 두는지가 운명을 가른다")
    class DerivedQueryNaming {

        @Test
        @DisplayName("findWithLockById: WithLock 은 find~By 사이라 무시된다")
        void withLockIsIgnored() {
            PartTree tree = new PartTree("findWithLockById", Klass.class);

            assertThat(tree.getParts())
                    .as("id 하나짜리 조건으로 해석돼야 한다")
                    .hasSize(1);
            assertThat(tree.getParts().iterator().next().getProperty().toDotPath())
                    .isEqualTo("id");
        }

        @Test
        @DisplayName("findByIdForUpdate: ForUpdate 가 By 뒤라 속성 경로로 해석돼 깨진다")
        void forUpdateBreaks() {
            assertThatThrownBy(() -> new PartTree("findByIdForUpdate", Klass.class))
                    .as("Klass 에 idForUpdate 속성이 없으므로 파싱에서 실패해야 한다. "
                            + "실제 앱에서는 이것이 Hibernate 부트스트랩 실패로 나타난다")
                    .isInstanceOf(PropertyReferenceException.class);
        }

        @Test
        @DisplayName("findFirstWithLockBy…: First 는 무시되지 않고 limit 로 해석된다")
        void firstIsALimitKeyword() {
            PartTree tree = new PartTree(
                    "findFirstWithLockByKlassIdAndStatusAndPositionGreaterThanOrderByPositionAsc",
                    com.toby.klass.waitlist.domain.Waitlist.class);

            assertThat(tree.isLimiting())
                    .as("First 가 limit 키워드로 인식돼야 1건만 가져온다")
                    .isTrue();
            assertThat(tree.getMaxResults()).isEqualTo(1);
            assertThat(tree.getSort().getOrderFor("position"))
                    .as("OrderByPositionAsc 가 정렬로 해석돼야 승격 순서가 보장된다")
                    .isNotNull();
        }

        @Test
        @DisplayName("존재하지 않는 속성은 즉시 걸린다 — 오타 방어선이 있다는 확인")
        void typoIsCaught() {
            assertThatThrownBy(() -> new PartTree("findByKlassIdd", Klass.class))
                    .isInstanceOf(PropertyReferenceException.class);
        }
    }

    @Nested
    @DisplayName("⑤ record + @ConfigurationProperties 중첩 바인딩")
    class PropertiesBinding {

        private final ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations
                        .of(ConfigurationPropertiesAutoConfiguration.class))
                .withUserConfiguration(EnableSpikeProperties.class);

        @Test
        @DisplayName("중첩 record 까지 생성자 바인딩된다")
        void bindsNestedRecord() {
            runner.withPropertyValues(
                            "app.enrollment.default-cancellation-period-days=7",
                            "app.enrollment.pending-expiry.direct=PT30M",
                            "app.enrollment.pending-expiry.waitlist=PT10M")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        SpikeEnrollmentProperties props =
                                context.getBean(SpikeEnrollmentProperties.class);

                        assertThat(props.defaultCancellationPeriodDays()).isEqualTo(7);
                        assertThat(props.pendingExpiry().direct())
                                .as("ISO-8601 Duration 이 그대로 바인딩돼야 한다")
                                .isEqualTo(Duration.ofMinutes(30));
                        assertThat(props.pendingExpiry().waitlist())
                                .isEqualTo(Duration.ofMinutes(10));
                    });
        }

        @Test
        @DisplayName("프로퍼티가 없으면 중첩 객체가 null 이 된다 — 기본값을 코드로 줘야 한다")
        void missingPropertiesYieldNull() {
            runner.run(context -> {
                assertThat(context).hasNotFailed();
                SpikeEnrollmentProperties props =
                        context.getBean(SpikeEnrollmentProperties.class);

                assertThatCode(props::pendingExpiry).doesNotThrowAnyException();
                assertThat(props.pendingExpiry())
                        .as("yml 에 블록이 없으면 중첩 record 가 null 이다. "
                                + "설계서는 값을 항상 준다고 전제하므로 이 사실을 기록해 둔다")
                        .isNull();
            });
        }
    }

    @EnableConfigurationProperties(SpikeEnrollmentProperties.class)
    static class EnableSpikeProperties {
    }

    /** 설계서 §5.2 가 제안한 모양 그대로. */
    @ConfigurationProperties(prefix = "app.enrollment")
    record SpikeEnrollmentProperties(int defaultCancellationPeriodDays,
                                     PendingExpiry pendingExpiry) {

        record PendingExpiry(Duration direct, Duration waitlist) {
        }
    }
}
