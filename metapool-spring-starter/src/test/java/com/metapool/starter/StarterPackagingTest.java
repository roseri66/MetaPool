package com.metapool.starter;

import org.junit.jupiter.api.Test;

import java.net.URL;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 打包纪律守护：starter 作为<b>类库</b>，绝不能把日志配置带进 classpath 根。
 *
 * <p>2.0.0 曾打包 {@code logback-spring.xml}：Spring Boot 的 {@code LogbackLoggingSystem} 在 classpath 根
 * 发现它就当成<em>使用方应用自己的</em>日志配置，覆盖 Boot 默认值。而该文件的 appender 全包在
 * {@code <springProfile>} 里，使用方不激活 dev/prod 时 root logger 一个 appender 都没有 ——
 * 使用方的全部日志（连 MetaPool 自己的治理审计流水）静默消失。详见 RULES 台账 P-08。
 */
class StarterPackagingTest {

    /** Boot 的 LoggingSystem 会在 classpath 根查找的配置文件名。 */
    private static final List<String> HIJACKING_CONFIGS = List.of(
            "logback-spring.xml", "logback-spring.groovy", "logback.xml", "logback.groovy");

    @Test
    void starter_mustNotShipLogbackConfigOnClasspathRoot() {
        for (String name : HIJACKING_CONFIGS) {
            URL found = getClass().getClassLoader().getResource(name);
            assertNull(found, () -> "metapool-spring-starter 不得打包 " + name
                    + "（会劫持使用方应用的日志配置），实测发现于: " + found);
        }
    }
}
