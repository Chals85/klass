package com.toby.klass;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 애플리케이션 진입점.
 *
 * <p>{@code @ConfigurationPropertiesScan} 이 없으면 {@code JwtProperties}·
 * {@code DefaultUserProperties} 가 빈으로 등록되지 않는다. {@code @SpringBootApplication}
 * 은 이 스캔을 포함하지 않으므로 명시해야 한다 — 빠뜨리면 기동이 통째로 실패한다.
 *
 * <p>Design Ref: §12 D-2 — 원본 {@code JwtAuthApplication} 에서 이름이 바뀌었다.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class KlassApplication {

    /**
     * 애플리케이션을 기동한다.
     */
    public static void main(String[] args) {
        SpringApplication.run(KlassApplication.class, args);
    }
}
