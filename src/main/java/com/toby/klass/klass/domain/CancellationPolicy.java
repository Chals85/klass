package com.toby.klass.klass.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 강의가 정한 취소 조건. {@link Klass#cancellationPolicy(int)} 가 만든다.
 *
 * <h2>왜 값 객체로 뽑았는가</h2>
 * {@code Enrollment.cancel} 이 취소 가능 여부를 판정하려면 강의의 {@code ends_on} 과
 * {@code cancellation_period_days} 를 알아야 한다. 방법이 셋 있었고 대가가 달랐다.
 *
 * <ol>
 *   <li><b>{@code Enrollment.klass} 프록시를 도메인 안에서 초기화</b> — 취소 트랜잭션은
 *       강의를 락으로 이미 로딩했으므로 추가 쿼리는 없다. 그러나 <b>도메인 메서드가 그
 *       사실에 암묵 의존</b>하게 되어, 다른 경로에서 부르면 조용히 쿼리가 나간다</li>
 *   <li><b>원시값 여러 개를 파라미터로</b> — {@code COALESCE(강의 값, 전역 기본)} 로직이
 *       <b>호출자마다 복제</b>된다</li>
 *   <li><b>값 객체로 추출</b> ← 택한 것</li>
 * </ol>
 *
 * <p>③ 은 {@code COALESCE} 를 {@link Klass} 안 한 곳에 모으고(취소 정책은 강의의 속성이다),
 * {@code Enrollment} 가 프록시를 모르게 하며, 무엇보다 <b>정책 판정 자체를 단위 테스트할 수
 * 있게</b> 한다 — 엔티티 두 개를 영속화하지 않고 record 하나로.
 *
 * <h2>판정이 두 관문인 이유</h2>
 * 하나로 합칠 수 없다. 기간 초과는 "다음엔 더 빨리 요청하라"이지만 강의 종료는 <b>아무리
 * 빨리 요청해도 성립하지 않는다.</b> 사용자에게 다른 이야기를 해야 하므로 판정도 나뉜다.
 *
 * <p>Design Ref: enrollment-management §3.2.2, D-37, FR-11 · FR-20
 *
 * @param klassEndsOn 강의 종료일. 이 날이 지나면 기간이 남아 있어도 취소할 수 없다 (FR-20)
 * @param periodDays  결제일 기준 취소 가능 기간(일). 강의가 지정하지 않았으면 전역 기본값이
 *                    들어온다. 0 이면 결제 즉시 취소 불가다
 */
public record CancellationPolicy(LocalDate klassEndsOn, int periodDays) {

    /**
     * 강의가 이미 끝났는지 판별한다.
     *
     * <p><b>{@code today} 를 파라미터로 받는 이유</b>: {@code LocalDate.now()} 를 여기서 부르면
     * 도메인이 시간대 결정을 떠안는다. ERD 정본 §2.2 가 경고한 "{@code DATE} 와 현재 시각을
     * 비교하는 경계"가 바로 이 지점이며, 그 환산은 주입된 {@code Clock} 이 해야 한다.
     *
     * @param today 오늘 날짜. 서비스가 {@code LocalDate.now(clock)} 으로 얻은 값
     * @return 종료일 <b>다음 날부터</b> {@code true}. 종료일 당일은 아직 끝나지 않은 것으로 본다
     */
    public boolean isKlassFinished(LocalDate today) {
        return today.isAfter(klassEndsOn);
    }

    /**
     * 결제일 기준 취소 가능 기간 안인지 판별한다.
     *
     * <p><b>경계를 포함한다</b> — {@code confirmedAt + periodDays} 와 정확히 같은 시각까지는
     * 취소할 수 있다. 경계를 배제하면 "7일 이내 취소 가능"이라 안내하고 7일째에 거부하는
     * 셈이 된다.
     *
     * @param confirmedAt 결제 확정 시각. {@code CONFIRMED} 가 아닌 신청에는 이 판정을 쓰지
     *                    않는다 — 그때는 이 값이 {@code null} 이다
     * @param now         현재 시각. 주입된 {@code Clock} 에서 얻은 값
     */
    public boolean isWithinPeriod(LocalDateTime confirmedAt, LocalDateTime now) {
        return !now.isAfter(confirmedAt.plusDays(periodDays));
    }
}
