package com.toby.klass.waitlist.application.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toby.klass.common.domain.error.BusinessException;
import com.toby.klass.waitlist.domain.error.WaitlistError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 대기 목록 조회 조건 검증 (L1 — 순수 단위).
 *
 * <h2>이 검증은 두 방어선 중 <b>둘째</b>다</h2>
 * HTTP 요청은 컨트롤러의 {@code @Min}/{@code @Max} 가 먼저 잡아 {@code VALIDATION_ERROR}(400)
 * 로 답한다. 여기의 {@code WAITLIST_PAGE_SIZE_OUT_OF_RANGE} 는 <b>포트를 직접 호출하는
 * 경로</b>를 막으며, 이 테스트가 그 유일한 도달 경로를 검증한다.
 *
 * <p>{@code KlassQueryTest} · {@code EnrollmentQueryTest} 와 같은 구조다.
 *
 * <p>Design Ref: enrollment-management D-46
 */
@DisplayName("WaitlistQuery — 조회 조건 (둘째 방어선)")
class WaitlistQueryTest {

    @ParameterizedTest(name = "size = {0}")
    @ValueSource(ints = {1, 20, 100})
    @DisplayName("size 는 1~100 이 허용된다")
    void acceptsSizeInRange(int size) {
        assertThatCode(() -> new WaitlistQuery(null, size)).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "size = {0}")
    @ValueSource(ints = {0, -1, 101, Integer.MAX_VALUE})
    @DisplayName("범위를 벗어난 size 는 WAITLIST_PAGE_SIZE_OUT_OF_RANGE 다")
    void rejectsSizeOutOfRange(int size) {
        assertThatThrownBy(() -> new WaitlistQuery(null, size))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(WaitlistError.WAITLIST_PAGE_SIZE_OUT_OF_RANGE);
    }

    @Test
    @DisplayName("상태 필터가 없다 — 대기는 상태가 셋뿐이고 목록이 짧아 걸러낼 이유가 적다")
    void hasNoStatusFilter() {
        assertThat(WaitlistQuery.class.getRecordComponents())
                .as("EnrollmentQuery 를 재사용하면 쓰지도 않는 status 때문에 "
                        + "waitlist 가 enrollment 패키지를 경유한다 (D-46)")
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("cursor", "size");
    }

    @Test
    @DisplayName("fetchLimit 은 size 보다 하나 많다 — hasNext 를 COUNT 없이 알기 위해서다")
    void fetchLimitIsSizePlusOne() {
        assertThat(new WaitlistQuery(null, 20).fetchLimit()).isEqualTo(21);
        assertThat(new WaitlistQuery(null, WaitlistQuery.MAX_SIZE).fetchLimit())
                .isEqualTo(WaitlistQuery.MAX_SIZE + 1);
    }

    @Test
    @DisplayName("커서는 생략할 수 있다 — 첫 페이지를 뜻한다")
    void cursorIsOptional() {
        WaitlistQuery query = new WaitlistQuery(null, WaitlistQuery.DEFAULT_SIZE);

        assertThat(query.cursor()).isNull();
        assertThat(query.size()).isEqualTo(20);
    }
}
