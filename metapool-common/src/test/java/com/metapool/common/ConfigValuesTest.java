package com.metapool.common;

import com.metapool.common.exception.MetaPoolConfigException;
import com.metapool.common.spi.ConfigValues;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 共用配置解析的验收。这段逻辑此前在四个适配器工厂里各有一份完全相同的实现，抽取到此处后
 * <b>只需要在一个地方保证正确性</b>。
 */
class ConfigValuesTest {

    @Test
    void duration_acceptsAllDocumentedForms() {
        assertEquals(Duration.ofMillis(500), ConfigValues.duration("k", 500), "Number 视为毫秒");
        assertEquals(Duration.ofMillis(500), ConfigValues.duration("k", "500"), "纯数字字符串视为毫秒");
        assertEquals(Duration.ofMillis(500), ConfigValues.duration("k", "500ms"));
        assertEquals(Duration.ofSeconds(30), ConfigValues.duration("k", "30s"));
        assertEquals(Duration.ofMinutes(2), ConfigValues.duration("k", "2m"));
        assertEquals(Duration.ofSeconds(90), ConfigValues.duration("k", "PT1M30S"), "ISO-8601");
        assertEquals(Duration.ofSeconds(3), ConfigValues.duration("k", "pt3s"), "ISO-8601 小写也认");
    }

    @Test
    void duration_toleratesSurroundingWhitespace() {
        assertEquals(Duration.ofSeconds(5), ConfigValues.duration("k", "  5s  "));
        assertEquals(Duration.ofMillis(20), ConfigValues.duration("k", " 20 ms "));
    }

    /**
     * <b>刻意不校验正负</b>：负值在某些底层库里有确定含义（Commons Pool2 约定负的
     * {@code max-wait} 表示无限等待）。解析器只管「能不能读懂」，不管「合不合理」——
     * 是否允许负数是各适配器自己的语义。
     */
    @Test
    void duration_doesNotRejectNegatives_becauseSomeAdaptersGiveThemMeaning() {
        assertEquals(Duration.ofMillis(-1), ConfigValues.duration("max-wait", -1));
        assertEquals(Duration.ofSeconds(-1), ConfigValues.duration("max-wait", "-1s"));
    }

    /**
     * RULES §3.2：非法配置必须报 {@link MetaPoolConfigException}，
     * <b>不能漏出裸的 {@code NumberFormatException} / {@code DateTimeParseException}</b> ——
     * 那是底层解析器的实现细节，不该成为使用方看到的错误类型。
     */
    @Test
    void duration_invalidInput_reportsMetaPoolConfigException_withKeyAndValue() {
        MetaPoolConfigException e = assertThrows(MetaPoolConfigException.class,
                () -> ConfigValues.duration("keep-alive", "soon"));
        assertTrue(e.getMessage().contains("keep-alive"), "消息要带配置项名：" + e.getMessage());
        assertTrue(e.getMessage().contains("soon"), "消息要带原值：" + e.getMessage());

        assertThrows(MetaPoolConfigException.class, () -> ConfigValues.duration("k", "PT-bogus"));
        assertThrows(MetaPoolConfigException.class, () -> ConfigValues.duration("k", "1x"));
        assertThrows(MetaPoolConfigException.class, () -> ConfigValues.duration("k", ""));
        assertThrows(MetaPoolConfigException.class, () -> ConfigValues.duration("k", null));
    }

    /** 原始异常要挂在 cause 上，排查时能看到底层解析器到底怎么了。 */
    @Test
    void duration_keepsTheUnderlyingCause() {
        MetaPoolConfigException e = assertThrows(MetaPoolConfigException.class,
                () -> ConfigValues.duration("k", "abc"));
        assertTrue(e.getCause() instanceof NumberFormatException, String.valueOf(e.getCause()));
    }
}
