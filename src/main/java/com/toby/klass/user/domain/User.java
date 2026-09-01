package com.toby.klass.user.domain;

import com.toby.klass.user.domain.error.UserError;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Getter;

/**
 * 사용자 애그리거트 루트.
 *
 * <h2>설계상 지켜야 하는 것들</h2>
 * <ul>
 *   <li><b>public setter 가 없다.</b> 상태 변경은 의도가 드러나는 메서드로만 허용한다.
 *       setter 를 열면 규칙이 서비스로 새어나가 빈혈 도메인이 된다.</li>
 *   <li><b>Spring 타입을 import 하지 않는다.</b> JPA·Jakarta 어노테이션만 예외다.
 *       그래서 Spring Data 의 {@code @CreatedDate} 를 쓸 수 없고, 생성 시각은
 *       {@link #register} 가 파라미터로 받는다.</li>
 *   <li><b>애플리케이션 포트를 파라미터로 받지 않는다.</b> 비밀번호 해싱에는
 *       {@code PasswordHasherPort} 가 필요하지만 도메인이 그것을 알면 의존 방향이
 *       역전된다. 그래서 {@link #register} 는 <b>이미 해싱된</b> 값을 받고,
 *       비밀번호 비교는 서비스가 수행한다.</li>
 * </ul>
 *
 * <h2>접근자가 {@code getXxx()} 가 아닌 이유</h2>
 * {@code id()}, {@code username()} 처럼 record 스타일 이름을 쓴다. Hibernate 는 이
 * 엔티티를 필드 접근으로 다루므로({@code @Id} 가 필드에 있다) 접근자 이름에 제약이 없고,
 * DTO 와 표기를 통일하는 편이 읽기 쉽다. 그래서 Lombok {@code @Getter} 도 쓰지 않는다.
 *
 * <p>Design Ref: §2.0 가드레일, §3.1 Entity Definition
 */
@Entity
@Table(
        // "user" 는 H2·MySQL 등 여러 DB 의 예약어다. 복수형으로 회피한다.
        name = "users",
        indexes = @Index(name = "idx_users_username", columnList = "username", unique = true))
@Getter
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 로그인 아이디. <b>실명이 아니다.</b>
     *
     * <p>{@code username} 은 Spring Security 생태계의 관례적 이름이라
     * ({@code UserDetails.getUsername()}, {@code UsernamePasswordAuthenticationToken})
     * 외부 문서·예제와 용어를 맞추기 위해 그대로 쓴다. 한국어로는 "사용자 이름"으로
     * 읽히기 쉬우나 여기 담기는 값은 {@code "chals"} 같은 로그인 식별자다.
     *
     * <p>PK 인 {@link #id} 와 혼동하지 말 것. 토큰의 {@code sub} 클레임과
     * {@code RefreshToken.userId} 가 참조하는 것은 {@code username} 이 아니라 {@code id} 다.
     */
    @Column(nullable = false, unique = true, length = 50)
    private String username;

    /**
     * BCrypt 해시. 평문은 이 필드에도, 로그에도, 응답에도 들어가지 않는다.
     *
     * <p>이름이 {@code password} 이지만 담기는 값은 항상 해시다. 이 값을 평문과 직접
     * 비교하면 언제나 실패한다 — BCrypt 해시에는 솔트가 섞여 있기 때문이다.
     * 비교는 Spring Security 의 인증 파이프라인이 수행한다(아래 {@link #getPassword()} 참조).
     */
    @Column(nullable = false, length = 100)
    private String password;

    /**
     * 권한 목록.
     *
     * <p>{@code @ElementCollection} 이므로 {@code user_roles} 테이블에 저장되지만 별도
     * 애그리거트가 아니라 {@code User} 의 일부다. 인증 시 매번 필요하므로 {@code EAGER} 로 둔다
     * — 권한 개수가 한 자리이고, {@code LAZY} 로 두면 토큰 발급마다 추가 쿼리가 발생한다.
     *
     * <p>{@link LinkedHashSet} 을 쓰는 이유는 순서 안정성이다. 순서가 흔들리면
     * {@code roles} 클레임 배열의 순서가 매번 바뀌어 응답 스냅샷 테스트가 깨진다.
     */
    // roles() 가 불변 뷰를 돌려주도록 손으로 유지한다. Lombok getter 는 필드를 그대로
    // 노출하므로 외부에서 add/remove 가 가능해져 캡슐화가 무너진다.
    @Getter(AccessLevel.NONE)
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private Set<Role> roles = new LinkedHashSet<>();

    @Column(name = "is_enabled", nullable = false)
    private boolean isEnabled = true;

    /**
     * 생성 시각. JPA Auditing 을 쓰지 않으므로 팩토리에서 채운다.
     *
     * <p>{@link LocalDateTime} 이므로 시간대 정보가 없다. 어느 시간대의 벽시계인지는
     * 주입된 {@code Clock} 이 결정한다({@code ClockConfig} 참조).
     */
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** JPA 가 리플렉션으로 인스턴스를 만들 때 쓴다. 직접 호출하지 말 것. */
    protected User() {
    }

    private User(String username, String password, Set<Role> roles, LocalDateTime createdAt) {
        this.username = username;
        this.password = password;
        this.roles = new LinkedHashSet<>(roles);
        this.isEnabled = true;
        this.createdAt = createdAt;
    }

    /**
     * 사용자를 생성한다.
     *
     * @param username       로그인 아이디. 유일해야 한다
     * @param password  <b>이미 해싱된</b> 비밀번호. 평문을 넘기면 로그인이 영구히 실패한다.
     *                  해싱은 호출자(서비스)가 {@code PasswordHasherPort} 로 수행한다
     * @param roles          부여할 권한. 비어 있으면 안 된다
     * @param createdAt 생성 시각. {@code LocalDateTime.now(clock)} 으로 얻은 값을 넘긴다
     * @return 아직 영속화되지 않은 새 사용자
     * @throws IllegalArgumentException 권한이 비어 있는 경우
     */
    public static User register(String username, String password, Set<Role> roles, LocalDateTime createdAt) {
        if (roles == null || roles.isEmpty()) {
            throw new IllegalArgumentException("사용자는 최소 하나의 권한을 가져야 합니다");
        }
        return new User(username, password, roles, createdAt);
    }

    /**
     * 계정이 활성 상태인지 확인한다.
     *
     * <p>로그인 흐름에서 <b>비밀번호 검증 이후에</b> 호출해야 한다. 먼저 호출하면
     * 비밀번호를 모르는 사람도 계정의 존재·활성 여부를 알아낼 수 있다.
     *
     * @throws com.toby.klass.common.domain.error.BusinessException 비활성 계정인 경우
     *         ({@link UserError#USER_DISABLED})
     */
    public void verifyEnabled() {
        if (!isEnabled) {
            throw UserError.USER_DISABLED.toException();
        }
    }

    /**
     * 권한을 JWT 클레임에 실을 문자열 목록으로 변환한다.
     *
     * @return {@code ["ROLE_USER"]} 형태. 선언 순서가 유지된다
     */
    public List<String> roleNames() {
        return roles.stream().map(Role::name).toList();
    }

    /**
     * 권한 집합(불변 뷰). 외부에서 추가·삭제할 수 없다
     *
     * @return 권한 집합(불변 뷰)
     */
    public Set<Role> roles() {
        // Set.copyOf 가 아니다 — 그쪽은 순서를 보장하지 않아 필드가 LinkedHashSet 인 의미가 사라진다.
        return Collections.unmodifiableSet(new LinkedHashSet<>(roles));
    }

}
