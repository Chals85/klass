package com.toby.klass.enrollment.application.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toby.klass.common.domain.error.BusinessException;
import com.toby.klass.enrollment.domain.EnrollmentStatus;
import com.toby.klass.enrollment.domain.error.EnrollmentError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 신청·대기 목록 조회 조건 검증 (L1 — 순수 단위).
 *
 * <h2>이 검증은 두 방어선 중 <b>둘째</b>다</h2>
 * HTTP 요청은 컨트롤러의 {@code @Min}/{@code @Max} 가 먼저 잡아
 * {@code CommonError.VALIDATION_ERROR}(400)로 답한다 —
 * {@code EnrollmentControllerTest} 가 그 사실을 단정한다. 여기의
 * {@code INVALID_ENROLLMENT_PAGE_SIZE} 는 <b>포트를 직접 호출하는 경로</b>를 막는다.
 *
 * <p><b>그래서 이 테스트가 없으면 그 에러 코드는 어디서도 검증되지 않는다.</b> 유일한 도달
 * 경로가 여기이기 때문이다. {@code KlassQueryTest} 가 같은 이유로 존재하며, 갭 분석에서
 * 이 대응물이 빠져 있음이 드러나 추가했다 (G-2).
 *
 * <h2>왜 {@code KlassQuery} 와 따로 두는가</h2>
 * 모양은 같지만 <b>상태 필터의 타입이 다르고</b> 범위 위반 시 던지는 에러 코드도 다르다.
 * 제네릭으로 묶으면 그 두 차이를 타입 파라미터와 생성자 인자로 밀어내야 하는데, 얻는 것보다
 * 읽기가 나빠진다.
 *
 * <p>Design Ref: enrollment-management §6.3, §7.1 두 방어선
 */
@DisplayName("EnrollmentQuery — 조회 조건 (둘째 방어선)")
class EnrollmentQueryTest {

    @ParameterizedTest(name = "size = {0}")
    @ValueSource(ints = {1, 20, 100})
    @DisplayName("size 는 1~100 이 허용된다")
    void acceptsSizeInRange(int size) {
        assertThatCode(() -> new EnrollmentQuery(null, size, null)).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "size = {0}")
    @ValueSource(ints = {0, -1, 101, Integer.MAX_VALUE})
    @DisplayName("범위를 벗어난 size 는 INVALID_ENROLLMENT_PAGE_SIZE 다")
    void rejectsSizeOutOfRange(int size) {
        assertThatThrownBy(() -> new EnrollmentQuery(null, size, null))
                .as("상한이 없으면 한 번의 요청으로 테이블 전체를 끌어갈 수 있다")
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(EnrollmentError.INVALID_ENROLLMENT_PAGE_SIZE);
    }

    @Test
    @DisplayName("에러 코드가 KlassError 와 구분된다 — 응답에 enum 타입이 실리지 않는다")
    void errorCodeIsDistinctFromKlass() {
        BusinessException e = (BusinessException) org.assertj.core.api.Assertions
                .catchThrowable(() -> new EnrollmentQuery(null, 0, null));

        assertThat(e.errorCode().name())
                .as("KlassError 의 INVALID_KLASS_PAGE_SIZE 와 이름이 겹치면 "
                        + "클라이언트가 어느 목록에서 난 오류인지 구분할 수 없다")
                .isEqualTo("INVALID_ENROLLMENT_PAGE_SIZE");
        assertThat(e.errorCode().httpStatus()).isEqualTo(400);
    }

    /**
     * {@code size + 1} 을 조회하는 것이 커서 페이지네이션의 핵심이다.
     *
     * <p>딱 {@code size} 개만 가져오면 <b>다음 페이지가 있는지 알 수 없다</b> — 전체가
     * 3건이어서 3개가 온 것과, 7건 중 3개가 온 것을 응답만으로 구분할 방법이 없다.
     */
    @Test
    @DisplayName("fetchLimit 은 size 보다 하나 많다 — hasNext 를 COUNT 없이 알기 위해서다")
    void fetchLimitIsSizePlusOne() {
        assertThat(new EnrollmentQuery(null, 20, null).fetchLimit()).isEqualTo(21);
        assertThat(new EnrollmentQuery(null, 1, null).fetchLimit()).isEqualTo(2);
        assertThat(new EnrollmentQuery(null, EnrollmentQuery.MAX_SIZE, null).fetchLimit())
                .isEqualTo(EnrollmentQuery.MAX_SIZE + 1);
    }

    @Test
    @DisplayName("커서와 상태 필터는 생략할 수 있다 — 첫 페이지·전체 상태를 뜻한다")
    void cursorAndStatusAreOptional() {
        EnrollmentQuery query = new EnrollmentQuery(null, EnrollmentQuery.DEFAULT_SIZE, null);

        assertThat(query.cursor()).isNull();
        assertThat(query.status()).isNull();
        assertThat(query.size()).isEqualTo(20);
    }

    @Test
    @DisplayName("상태 필터를 생략해도 유효하다 — 내 신청 목록의 기본은 전체다")
    void statusIsOptional() {
        assertThatCode(() -> new EnrollmentQuery(100L, 20, null))
                .as("취소한 것까지 보이는 것이 기본이다 — 내 기록이므로 가리지 않는다")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("상태를 지정하면 그대로 보존된다")
    void keepsStatusFilter() {
        assertThat(new EnrollmentQuery(null, 20, EnrollmentStatus.CONFIRMED).status())
                .isEqualTo(EnrollmentStatus.CONFIRMED);
    }
}
