package com.toby.klass.spike;

import java.util.ArrayList;
import java.util.List;
import org.hibernate.resource.jdbc.spi.StatementInspector;

/**
 * Hibernate 가 실제로 내보내는 SQL 을 가로채 기록한다.
 *
 * <p><b>{@code show-sql} 로는 단정할 수 없어서 필요하다.</b> 콘솔 출력은 사람이 눈으로
 * 확인할 뿐이라 "{@code FOR UPDATE} 가 붙었다"를 테스트가 증명하지 못한다. 스파이크의
 * 목적이 바로 그 증명이므로 문자열을 잡아 둔다.
 *
 * <p>Hibernate 가 <b>무인자 생성자로 직접 인스턴스화</b>하므로(프로퍼티에 클래스명만 준다)
 * 수집 지점을 {@code static} 으로 둔다. 테스트마다 {@link #reset()} 을 부른다.
 */
public class SqlCapture implements StatementInspector {

    private static final List<String> STATEMENTS = new ArrayList<>();

    public static void reset() {
        STATEMENTS.clear();
    }

    public static List<String> captured() {
        return List.copyOf(STATEMENTS);
    }

    /**
     * 잡은 SQL 을 파일로 남긴다. <b>스파이크의 산출물이 판정 근거가 되게 하려고</b> 둔다 —
     * 테스트가 통과했다는 사실만으로는 "무엇이 나갔는지"를 설계서에 옮길 수 없다.
     * Gradle 이 기본 설정에서 테스트 표준출력을 보여주지 않으므로 파일로 뽑는다.
     */
    public static void dump(String label) {
        try {
            java.nio.file.Path out = java.nio.file.Path.of("build", "spike", "lock-sql.txt");
            java.nio.file.Files.createDirectories(out.getParent());
            java.nio.file.Files.writeString(out,
                    "### " + label + System.lineSeparator()
                            + String.join(System.lineSeparator(), STATEMENTS)
                            + System.lineSeparator() + System.lineSeparator(),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("스파이크 SQL 을 남기지 못했다", e);
        }
    }

    /** 마지막으로 나간 SELECT. 락 검증은 이것 하나면 된다. */
    public static String lastSelect() {
        return STATEMENTS.reversed().stream()
                .filter(sql -> sql.toLowerCase().startsWith("select"))
                .findFirst()
                .orElse("");
    }

    @Override
    public String inspect(String sql) {
        STATEMENTS.add(sql);
        return sql;   // 변형하지 않는다. 관찰만 한다
    }
}
