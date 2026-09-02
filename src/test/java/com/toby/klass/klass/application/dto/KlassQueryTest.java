package com.toby.klass.klass.application.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toby.klass.common.domain.error.BusinessException;
import com.toby.klass.klass.domain.error.KlassError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 목록 조회 조건 검증 (L1 — 순수 단위).
 *
 * <h2>왜 어댑터 테스트에서 분리했는가</h2>
 * 원래 {@code KlassRepositoryAdapterTest} 안에 있었는데, <b>거기서는 어댑터를 한 번도
 * 호출하지 않으면서 {@code @DataJpaTest} + {@code @Import} 3종 컨텍스트를 띄웠다.</b>
 * record 생성자만 검사하는 테스트에 DB 와 QueryDSL 배선이 필요할 이유가 없다.
 *
 * <h2>이 검증은 두 방어선 중 <b>둘째</b>다</h2>
 * HTTP 요청은 컨트롤러의 {@code @Min}/{@code @Max} 가 먼저 잡아 {@code VALIDATION_ERROR}(400)
 * 로 답한다. 여기의 {@code INVALID_KLASS_PAGE_SIZE} 는 <b>포트를 직접 호출하는 경로</b>를
 * 막는다 — 배치나 내부 서비스가 {@code KlassQuery} 를 만들어 쓰는 경우다.
 *
 * <p>Design Ref: §4.3 커서, §6.1 INVALID_KLASS_PAGE_SIZE, §6.5 두 방어선
 */
class KlassQueryTest {

    @ParameterizedTest(name = "size = {0}")
    @ValueSource(ints = {1, 20, 100})
    @DisplayName("size 는 1~100 이 허용된다")
    void acceptsSizeInRange(int size) {
        assertThatCode(() -> new KlassQuery(null, size, null)).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "size = {0}")
    @ValueSource(ints = {0, -1, 101, Integer.MAX_VALUE})
    @DisplayName("범위를 벗어난 size 는 INVALID_KLASS_PAGE_SIZE 다")
    void rejectsSizeOutOfRange(int size) {
        assertThatThrownBy(() -> new KlassQuery(null, size, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(KlassError.INVALID_KLASS_PAGE_SIZE);
    }

    /**
     * {@code size + 1} 을 조회하는 것이 커서 페이지네이션의 핵심이다.
     *
     * <p>딱 {@code size} 개만 가져오면 <b>다음 페이지가 있는지 알 수 없다</b> — 전체가
     * 3건이어서 3개가 온 것과, 7건 중 3개가 온 것을 응답만으로 구분할 방법이 없다.
     * 하나를 더 요청해 그것이 왔는지로 판정하면 {@code COUNT(*)} 없이 알 수 있다.
     */
    @Test
    @DisplayName("fetchLimit 은 size 보다 하나 많다 — hasNext 를 COUNT 없이 알기 위해서다")
    void fetchLimitIsSizePlusOne() {
        assertThat(new KlassQuery(null, 20, null).fetchLimit()).isEqualTo(21);
        assertThat(new KlassQuery(null, 1, null).fetchLimit()).isEqualTo(2);
        assertThat(new KlassQuery(null, KlassQuery.MAX_SIZE, null).fetchLimit())
                .isEqualTo(KlassQuery.MAX_SIZE + 1);
    }

    @Test
    @DisplayName("커서와 상태 필터는 생략할 수 있다 — 첫 페이지·전체 상태를 뜻한다")
    void cursorAndStatusAreOptional() {
        KlassQuery query = new KlassQuery(null, KlassQuery.DEFAULT_SIZE, null);

        assertThat(query.cursor()).isNull();
        assertThat(query.status()).isNull();
        assertThat(query.size()).isEqualTo(20);
    }
}
