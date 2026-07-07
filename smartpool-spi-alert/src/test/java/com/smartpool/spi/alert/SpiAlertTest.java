package com.smartpool.spi.alert;

import com.smartpool.common.spi.ExtensionLoader;
import com.smartpool.common.spi.SPI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC-18 告警渠道 SPI 接口契约测试。
 *
 * <p>验证：
 * <ol>
 *   <li>{@link AlertChannel} 标注 {@link SPI @SPI} 注解</li>
 *   <li>{@link ExtensionLoader} 加载时返回 {@code null}（V1 无实现类）</li>
 *   <li>{@link AlertMessage} 包含全部 5 个字段：severity / title / content / timestamp / sourcePool</li>
 *   <li>DTO 不可变性（final class，无 setter）</li>
 *   <li>{@link AlertSeverity} 枚举包含 INFO / WARNING / CRITICAL</li>
 * </ol>
 */
@DisplayName("SPEC-18 Alert Channel SPI")
class SpiAlertTest {

    // ──────────────────── SPI 注解验证 ────────────────────

    @Nested
    @DisplayName("SPI 注解")
    class SpiAnnotation {

        @Test
        @DisplayName("AlertChannel 标注 @SPI")
        void alertChannelShouldHaveSpiAnnotation() {
            SPI spi = AlertChannel.class.getAnnotation(SPI.class);
            assertNotNull(spi, "AlertChannel must be annotated with @SPI");
        }
    }

    // ──────────────────── ExtensionLoader 返回 null ────────────────────

    @Nested
    @DisplayName("ExtensionLoader")
    class ExtensionLoaderTests {

        @Test
        @DisplayName("加载 AlertChannel → 返回 null（无实现）")
        void loadAlertChannelShouldReturnNull() {
            AlertChannel channel = ExtensionLoader.getExtension(AlertChannel.class);
            assertNull(channel, "V1 should have no implementation, ExtensionLoader must return null");
        }
    }

    // ──────────────────── AlertSeverity 枚举 ────────────────────

    @Nested
    @DisplayName("AlertSeverity")
    class AlertSeverityTests {

        @Test
        @DisplayName("包含 INFO / WARNING / CRITICAL 三个值")
        void shouldHaveThreeValues() {
            AlertSeverity[] values = AlertSeverity.values();
            assertEquals(3, values.length);
            assertEquals(AlertSeverity.INFO, AlertSeverity.valueOf("INFO"));
            assertEquals(AlertSeverity.WARNING, AlertSeverity.valueOf("WARNING"));
            assertEquals(AlertSeverity.CRITICAL, AlertSeverity.valueOf("CRITICAL"));
        }
    }

    // ──────────────────── AlertMessage DTO ────────────────────

    @Nested
    @DisplayName("AlertMessage")
    class AlertMessageTests {

        @Test
        @DisplayName("包含全部 5 个字段：severity / title / content / timestamp / sourcePool")
        void shouldContainAllFiveFields() {
            long now = System.currentTimeMillis();
            AlertMessage msg = AlertMessage.builder()
                    .severity(AlertSeverity.CRITICAL)
                    .title("连接池耗尽")
                    .content("数据库连接池 smartpool-db 活跃连接 20/20，等待请求 15 个")
                    .timestamp(now)
                    .sourcePool("smartpool-db")
                    .build();

            assertEquals(AlertSeverity.CRITICAL, msg.getSeverity());
            assertEquals("连接池耗尽", msg.getTitle());
            assertTrue(msg.getContent().contains("20/20"));
            assertEquals(now, msg.getTimestamp());
            assertEquals("smartpool-db", msg.getSourcePool());
        }

        @Test
        @DisplayName("默认严重级别为 INFO")
        void shouldDefaultSeverityToInfo() {
            AlertMessage msg = AlertMessage.builder()
                    .title("测试")
                    .content("测试内容")
                    .sourcePool("test-pool")
                    .build();

            assertEquals(AlertSeverity.INFO, msg.getSeverity());
        }

        @Test
        @DisplayName("title 为空时抛异常")
        void shouldThrowWhenTitleEmpty() {
            assertThrows(IllegalArgumentException.class, () ->
                    AlertMessage.builder()
                            .title("")
                            .content("test")
                            .sourcePool("test")
                            .build());
        }

        @Test
        @DisplayName("content 为空时抛异常")
        void shouldThrowWhenContentEmpty() {
            assertThrows(IllegalArgumentException.class, () ->
                    AlertMessage.builder()
                            .title("test")
                            .content("")
                            .sourcePool("test")
                            .build());
        }

        @Test
        @DisplayName("sourcePool 为空时抛异常")
        void shouldThrowWhenSourcePoolEmpty() {
            assertThrows(IllegalArgumentException.class, () ->
                    AlertMessage.builder()
                            .title("test")
                            .content("test")
                            .sourcePool("")
                            .build());
        }
    }

    // ──────────────────── 不可变性验证 ────────────────────

    @Nested
    @DisplayName("不可变性")
    class Immutability {

        @Test
        @DisplayName("AlertMessage 为 final class")
        void alertMessageShouldBeFinal() {
            assertTrue(java.lang.reflect.Modifier.isFinal(AlertMessage.class.getModifiers()));
        }
    }
}
